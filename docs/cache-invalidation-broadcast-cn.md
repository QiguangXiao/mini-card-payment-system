# Statement Read Cache 跨 pod 失效广播设计

本文档回答两个问题，并给出 statement read cache 的失效方案：

1. 缓存失效广播该用 **Redis Pub/Sub** 还是 **Kafka**？
2. 一个 statement 业务事件，既要驱动 **L1 缓存失效**，又要驱动 **Notification**，是不是干脆都走 Kafka？

结论先行：**两件事都要做，但不要用同一个 transport。L1 失效广播用 Redis Pub/Sub，Notification 用
Kafka。它们由同一个业务事实触发，但投递语义相反，所以选不同管道不是冗余，而是各取所长。**

---

## 1. 核心对比：缓存失效广播 vs Notification

| 维度 | L1 缓存失效广播 | Statement Notification |
| --- | --- | --- |
| 扇出模型 | **广播**：每个 pod 都要收到并清自己的 L1 | **竞争消费**：N 个 pod 里只有 1 个发通知 |
| 可靠性 | **best-effort 即可**：L1 有 `local-ttl` 兜底，丢一条最多 stale 一个 TTL | **at-least-once + 幂等**：通知不能丢、不能重复发 |
| 持久化 / 重放 | **不需要**：pod 重启后 L1 本来就是空的 | **需要**：消费者宕机期间的事件必须留存、恢复后补发 |
| 延迟 | 越快越好（目的就是压窗口，ms 级） | 秒级完全可以 |
| 天生匹配 | **Redis Pub/Sub** | **Kafka（消费者组）** |

**同一个业务触发（statement 关账 / 还款）≠ 同一个 transport。** 触发源可以是一个，但下游按各自的
可靠性需求选管道。

### 1.1 为什么 Kafka 做缓存失效广播是别扭的

Kafka 的消费者组是**竞争消费**：N 个 pod 放进同一个 group，每条消息只有 1 个 pod 收到——而缓存
失效要的是**所有** pod 都清 L1。要让 Kafka 广播给所有 pod，只能给每个 pod 分配一个**唯一 group**
（`cache-evict-<instanceId>`），于是：

- group 数量随 pod / 重启膨胀，`__consumer_offsets` 里一堆临时 group；
- 每次重启是新 group，要么 `latest`（要小心配置）要么 `earliest`（重放历史失效消息）；
- 失效要经过 **Outbox 轮询 → Kafka → 消费 lag**，通常 1~3s 才传到其他 pod，把"压窗口"的低延迟优势丢掉了。

Redis Pub/Sub 则**天生广播**：每个订阅者都收到每条消息，零 group 管理，亚毫秒延迟，fire-and-forget
正好配 TTL 兜底。这就是缓存失效的教科书工具。

### 1.2 为什么 Redis Pub/Sub 做 Notification 是危险的

Redis Pub/Sub 是 **at-most-once、无持久化、无重放**。如果通知消费者那一刻断连（发版、网络抖动、
Redis failover），消息**直接丢失**且无法补发。这对 Notification 不可接受。Notification 必须走
Outbox → Kafka → Inbox（事务保证 + at-least-once + 幂等 + 可重放）。

### 1.3 推荐架构（两条流）

```text
还款 / 关账 (同一个业务事实)
   │
   ├─(写侧 after-commit)──► Redis Pub/Sub PUBLISH ──► 所有 pod 订阅者 invalidate L1   [best-effort, ms]
   │
   └─(领域事件)──► Outbox ──► Kafka statement-events ──► StatementNotificationListener  [at-least-once, 幂等]
                                                     （消费者组，一个 pod 处理一次）
```

- **Notification**：新增 `statement.closed` 等领域事件 → Outbox → Kafka → `StatementNotificationListener`
  （消费者组 + Inbox 幂等）。与现有 Authorization/CardTransaction/Repayment notification listener 同构。
- **L1 evict**：在 `StatementReadService.evictAfterCommit` 的 after-commit 回调里，除了删 L2，再
  PUBLISH 一条 Redis 消息，所有 pod 的订阅者清各自 Caffeine L1。

---

## 2. 本次实现：Redis Pub/Sub L1 失效广播

### 2.1 它解决的窗口

之前 `evict()` 只做两件事：清**本 pod** L1 + 删共享 L2。其他 pod 的 Caffeine L1 仍留旧值，最长
stale 一个 `local-ttl`（默认 30s）。广播把这个**跨 pod L1 窗口**从 `local-ttl` 压到一次 Pub/Sub 延迟。

> 注意它**不**解决 L2 的 late-write 竞态（见第 3 节）。Pub/Sub（关 L1 窗口）和版本号/CAS（关 L2 窗口）
> 是互补的两件事，不能互相替代。

### 2.2 组件与数据流

```text
RepaymentService.applyRepayment (commit)
  └─ StatementReadService.evictAfterCommit(id)         // after-commit
       └─ evict(id):
            1. localCache.invalidate(id)               // 清本 pod L1
            2. redisTemplate.delete(L2 key)            // 删共享 L2
            3. broadcaster.broadcastEvict(id)          // PUBLISH 给所有 pod
                 └─ RedisStatementReadCacheBroadcaster: convertAndSend(EVICT_CHANNEL, id)

每个 pod:
  RedisMessageListenerContainer (独立订阅连接 + 独立线程)
    └─ StatementReadCacheEvictListener.onMessage(id)
         └─ StatementReadService.invalidateLocal(id)   // 只清 L1
```

### 2.3 最佳实践要点（实现里逐条对应注释）

1. **广播是 best-effort + TTL 兜底**：Pub/Sub at-most-once，订阅者断连就漏收；漏收时退回 `local-ttl`。
   所以广播失败只告警不抛（它发生在 after-commit，业务已提交，不能反过来影响请求）。
2. **避免广播风暴 / 无限循环**：订阅者回调只调 `invalidateLocal`（**L1-only**），**绝不**调用会再删 L2
   并再次广播的 `evict`。否则 N 个 pod 会互相广播形成风暴。这是整个设计最关键的约束。
3. **写侧本地失效同步做，不依赖自己的广播回环**：`evict()` 先同步 `localCache.invalidate` 清自己，
   再广播给别人。即使本 pod 的订阅连接此刻有抖动，发起 pod 自身也已经一致。
4. **失效顺序**：先删 L2 再 PUBLISH。订阅者收到广播去重读时，L2 已经删掉，会直接回源 DB，不会再读到旧 L2。
5. **频道名带 cache 版本号**（`...:statement-read-model-v1`），与 L2 key 的 `CACHE_NAME` 对齐；换 read
   model schema 时一起换。
6. **毒消息不毒死订阅者**：订阅者解析 UUID 失败时只告警丢弃，本 pod 退回 TTL 兜底。
7. **可开关**：`statement.read-cache.broadcast.enabled`（默认 `false`）。关闭时注入 no-op 广播器，
   `evict` 退化成原来的"只清本 pod + 删 L2"，单实例 / 测试零负担。订阅容器也只在开启时才装配。
8. **独立订阅连接 / 线程**：`RedisMessageListenerContainer` 在专用连接上 SUBSCRIBE、用自己的线程回调，
   不占业务请求线程，也独立于 publish 用的连接。

### 2.4 端口与实现

- `StatementReadCacheBroadcaster`（application port）：`broadcastEvict(UUID)`。
- `RedisStatementReadCacheBroadcaster`（开启时）：`convertAndSend`。
- `NoOpStatementReadCacheBroadcaster`（默认）：什么都不做。
- `StatementReadCacheEvictListener`（`MessageListener`）：`onMessage → invalidateLocal`。
- `StatementReadCacheBroadcastConfiguration`：装配 `RedisMessageListenerContainer` + listener，
  `@ConditionalOnProperty` 仅开启时存在。

---

## 3. L2 late-write 竞态：版本号 + Lua CAS + 墓碑（已实现）

广播（第 2 节）只压跨 pod L1 窗口，对 L2 的"迟到写"无能为力。这一节先把所有相关 race 摆出来逐个 trace，
再做选型对比，最后给出本仓库采用的实现。约定：`version = paidAmount 的最小货币单位`（本域单调非减，
不存在 un-repay；新关账 paid=0 → version 0，还款到 ¥100 → version 100）。

### 3.1 竞态目录（先看清有几条 race）

cache-aside 的写其实有两条独立 race，很多人只看到一条。

#### Race A：慢的旧 reader 覆盖"已存在的新值"

```text
t0  GET-A 读 DB: paid=0（还款前），但写 L2 很慢
t1  还款 commit; evict 失效 L2
t2  GET-B 读 DB: paid=100 → 写 L2 {paid=100}      ← L2 已是新值
t3  GET-A（迟到）写 L2 {paid=0}                    ← 覆盖新值！
```

#### Race B：迟到写落在"刚被失效的空 key"上（就是主 race）

```text
t0  GET-A 读 DB: paid=0，但写 L2 很慢
t1  还款 commit; evict 失效 L2（若是 delete → L2 变空）
t2  GET-A（迟到）写 L2 {paid=0}                    ← 落在空 key 上，旧值活到 remote-ttl
```

区别：Race A 发生在"L2 里已有新值"时；Race B 发生在"L2 被清空"时。还款后 evict 通常先把 key 清掉，
而 GET-A 往往是第一个回填的（它 t0 就开始读了），所以 **Race B 是更高频、更难缠的那条**。

### 3.2 同一场景，三种做法的对比 trace（关键：看 Race B）

用同一个时间线 `t0 读到 paid=0 / t1 还款 paid=100 / t2 迟到写`，看三种做法的结局。

**做法①：现状（无版本，evict=DELETE，写=无条件 SET）**

```text
t1  evict DELETE → L2 空
t2  GET-A 无条件 SET {paid=0} → L2={paid=0}  ❌ Race A、Race B 都中
```

**做法②：只加版本号（evict 仍 DELETE，写=Lua "SET if version >= stored OR 不存在"）**

```text
Race A 场景: L2 已是 {paid=100,v=100}
  t3  GET-A: 0 >= 100? 否 → 拒绝          ✅ Race A 解决

Race B 场景: t1 evict DELETE → L2 空
  t2  GET-A: key 不存在 → 命中 "OR 不存在" 分支 → 写 {paid=0,v=0}  ❌ Race B 没解决！
```

> 洞在这：版本只在"有旧值可比"时拦得住。delete 把可比的版本也删了，迟到写落在空 key 上，版本判断走不到。
> 危险的是它**看着像修好了**（有版本有 CAS），主 race 还开着——还容易被一个只测 Race A 的绿测试掩盖。

**做法③：版本号 + 墓碑（evict 不 delete，改写"版本地板"墓碑；读回填同样走 CAS）**

```text
Race B 场景:
  t1  evict 写 {tombstone, v=100}（不是 delete）→ L2=地板(100)
  t2  GET-A: incoming v=0 >= stored v=100? 否 → 拒绝          ✅ Race B 解决
后续 新鲜 reader(v=100): 100>=100 → 用真实值替换墓碑; 读到墓碑视作 miss → 回源 DB
```

墓碑给了一个"哪怕失效后也存在的版本地板"，于是 CAS 在空 key 问题上也站得住。

### 3.3 选型：墓碑 vs 直接写新值（write-through）

版本地板既可以是"墓碑"，也可以是"真值"（write-through）。两者都关掉 Race B，区别在写侧放什么。

| 维度 | 写墓碑（本实现） | 写新值 write-through |
| --- | --- | --- |
| 关掉 Race A / B | ✅ / ✅ | ✅ / ✅ |
| 写后首读 | miss → 回源 1 次 DB | 命中，不回源（pay-then-view 更顺） |
| 谁构造缓存值 | **只有读路径**（单一来源，永不发散） | 写路径也得会构造 → 两条构造路径，可能发散 |
| 写侧知识/耦合 | 只需版本（paidAmount），零 read model 知识 | 写侧要拼完整 read model |
| 写 L2 负载 | 极小（只版本） | 整份 JSON |
| 契合本仓库哲学 | ✅ "invalidate 不 update" 的版本化延续 | 改成写侧也更新缓存，和既有注释相悖 |

> 关于 write-through 的数据完整性：本仓库 `findByIdForUpdate` 经 `toDomainWithItems` 已加载全量明细，
> 所以还款路径**有**完整数据，write-through 在这里不会缓存残缺值——它是可行的。选墓碑是基于：
> statement 查询低 QPS（热缓存收益在噪声里）+ 守住"读路径是缓存值唯一来源"这条不变式（抗未来发散）+
> 不破坏既有 invalidate-over-update 立场。若要 write-through，正确写法是给读服务加 `refreshAfterCommit(Statement)`，
> 让**读服务**用 `StatementReadModel.from(statement)` + CAS 写回，而不是让 RepaymentService 自己拼模型写 Redis。

**墓碑相对 delete 的代价（都不是新的正确性 race）**：

- 墓碑只活 `tombstone-ttl`，要 ≥ 慢 GET 从"读 DB"到"写回 L2"的最大时滞，否则地板先过期、Race B 重开
  （但仍 ≤ delete 的暴露——delete 根本没地板）。
- 读路径要多一个分支：识别墓碑、当 miss。
- `>=` 写规则下，同版本的"真值↔墓碑"可互相覆盖：一个晚到的 evict 可能把已存真值再压成墓碑 → 下次读多回源
  一次（良性，最终收敛；本域同一 version 只有一次 evict，不会无限抖动）。选 `>=` 而非 `>`，是为了让新鲜
  reader 能用真值替换同版本墓碑；若用 `>`，墓碑会卡住直到 TTL、期间每次读都回源，更糟。

> 直觉校准："墓碑比 delete 更容易不一致吗？" —— **设计语义上不会**（它关掉了 delete 留的 Race B，读到墓碑=miss
> =回源，永不 serve 错值）；**实现/代码风险上会**（delete 是空=问 DB 真相，最笨最安全；墓碑是要正确解释的存储
> 状态，多了 Lua、版本比较、墓碑识别、TTL 调参的出错面）。这正是下面强调要用真 Redis 测脚本的原因。

### 3.4 本仓库的最终做法

- **版本**：`StatementReadModel.version()` = paidAmount 最小货币单位（`minorUnits`）。evict 侧用还款后
  `statement.paidAmount()` 算同一口径，作为墓碑版本。
- **L2 信封格式**（CACHE_NAME bump 到 `...-v2`，因为 value contract 变了）：`<version>|<payload>`
  - 真实值：`<version>|<read model JSON>`
  - 墓碑：`<version>|`（`|` 后为空 = tombstone，读到当作 miss）
  - 用"数字前缀 + 分隔符"而非 JSON 包版本，是为了让 Lua 端不依赖 `cjson`，只做一次数字前缀解析。
- **Lua CAS（读回填与墓碑写共用）**：

```lua
-- KEYS[1]=key  ARGV[1]=incoming version  ARGV[2]=信封  ARGV[3]=TTL 毫秒
local incoming = tonumber(ARGV[1])
local current = redis.call('GET', KEYS[1])
if current then
  local cur = tonumber(string.match(current, '^([%-%d]+)|'))  -- 取信封前缀版本
  if cur ~= nil and cur > incoming then
    return 0   -- 已存更新版本/墓碑，拒绝这次（较旧的）写
  end
end
redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
return 1
```

- **TTL**：真实值用 `remote-ttl + jitter`；墓碑用更短的 `tombstone-ttl`（默认 10s，只需活到首个新鲜读替换它）。
- **evict 三步**（还款 after-commit）：清本 pod L1 → 写墓碑(CAS 版本地板) → 广播让其他 pod 清 L1（第 2 节）。

### 3.5 实现后的行为 walk-through（多角度验证）

**用例 1：还款后并发 GET，迟到写被挡（Race B）**

```text
t0  GET-A 读 DB paid=0（v=0），慢
t1  还款 commit → paid=100；evict 写墓碑 "100|"
t2  GET-A 回填: CAS(incoming=0) vs stored 墓碑 v=100 → 0<100 拒绝。L2 仍是墓碑(100)  ✅
t3  GET-B 读 DB paid=100（v=100）→ CAS(100) vs 墓碑(100) → 100>=100 写真实值 "100|{...paid=100}"
后续 读命中真实新值
```

**用例 2：连续两笔还款，乱序 evict 不会降低地板**

```text
R1 paid=100 → 墓碑(100)；R2 paid=200 → 墓碑(200)
若 R2 的 evict 先到: L2=墓碑(200)；R1 的 evict 后到: CAS 100 vs 200 → 100<200 拒绝。地板保持 200  ✅
```

**用例 3：读到墓碑 = miss**

```text
GET 读 L2 得 "100|"（'|' 后空）→ 当作 miss → 回源 DB 重建 → CAS 写回真实值
```

**用例 4：跨 pod，广播 + 墓碑协同**

```text
pod-1 还款 after-commit: 清自己 L1 → L2 写墓碑(100) → PUBLISH 失效
pod-2 订阅者收到 → invalidateLocal（只清 L1）→ pod-2 下次 GET: L1 miss → 读 L2 见墓碑 → 回源 DB  ✅
（其他 pod 不写墓碑、不再广播，避免风暴；共享 L2 的墓碑只由发起 pod 写一次）
```

### 3.6 仍要补的：真 Redis 测试

单测目前 mock `StringRedisTemplate`，只能验证 **Java 接缝**（信封格式、是否调用 CAS、墓碑当 miss、版本计算），
**验证不了 Lua 脚本在真 Redis 上的 CAS/并发行为**。墓碑方案的正确性恰恰集中在那段 Lua 里。

因此后续必做：**加 Redis Testcontainers，对脚本写并发场景测试**（迟到写被拒、同版本真值替换墓碑、乱序 evict
保持高地板等），把上面 4 个 walk-through 用真 Redis 钉死。在那之前，本实现的 Lua 属于"已写、已 seam 测、
待真环境验证"状态。

---

## 4. 整体复盘：statement 两层缓存全景

把前面分散的点收成一张全景图：这套缓存到底解决什么、用了哪些 trick、关掉了哪些 race、还剩哪些 race。

### 4.1 它解决什么（也只解决什么）

- **降读延迟**：L1 Caffeine 同 JVM 命中是纳秒级、零网络；L2 Redis 命中是一次内网 RTT，省掉 MySQL 查询 +
  `StatementReadModel` 组装（statement 头 + 全量 lines 两次表访问）。
- **降 DB 读压**：L2 跨 pod 共享，一个 pod 回源后整个 fleet 复用同一份快照，账单查询不再每次打 MySQL。
- **边界（最重要）**：缓存只是 performance layer，**不是正确性来源**。它缓存的是可从 MySQL 完整重建的
  `StatementReadModel`；额度、幂等 winner、还款入账这些强一致仍由 DB 事务 / row lock 保证。秒级 stale 可接受，
  要强一致就直接读 DB——这条边界是整套设计能"放心做 best-effort"的前提。

### 4.2 全套 trick 清单（每个对应一个具体问题）

| Trick | 解决的问题 | 代码位置 |
| --- | --- | --- |
| L1 Caffeine（同 JVM） | 热点查询纳秒命中、零网络 | `localCache` |
| L2 Redis（跨 pod） | 一个 pod 回源后全 fleet 复用，少打 DB | `readRedis/writeRedis` |
| cache-aside | 读 miss 回源写回、写侧失效；**读路径是缓存值唯一构造者** | `loadThroughRedis` |
| L1 TTL < L2 TTL | 多 pod 下短 L1 降低跨 pod stale | `local-ttl 30s / remote-ttl 5m` |
| TTL jitter | 防 **cache avalanche**（一批 key 同秒过期一起回源） | `remoteTtlWithJitter` |
| maximumSize | 防 L1 被无限/恶意 key 撑爆 heap（隐形数据库） | `Caffeine.maximumSize` |
| per-key single-flight | 防**同 JVM cache stampede**（同 key 并发 miss 只回源一次） | `Cache.get(k, loader)` |
| Redis 故障 fallback 回源 DB | 缓存**不是 hard dependency**（可用性：cache 挂≠GET 挂） | `readRedis` catch |
| 坏 JSON / 非本版本格式自愈 | 防一个坏值永久挡路 | `deleteCorruptRedisValue` |
| cache name 版本号（v2） | value contract 演进安全（换格式不误读旧值） | `CACHE_NAME` |
| after-commit 失效 | 只有 DB commit 成功才失效；避免提交前删被旧值"固化" | `evictAfterCommit` |
| 版本号 + Lua CAS + 墓碑 | 根治**迟到写**竞态（Race A/B） | `CAS_WRITE_SCRIPT / evict` |
| Redis Pub/Sub 广播 | 压**跨 pod L1** 失效窗口（local-ttl → 一次广播延迟） | 第 2 节 |

### 4.3 race / 故障 scorecard

**✅ 已经关掉：**

- 缓存雪崩（avalanche）：TTL jitter 错开过期。
- 缓存击穿（同 JVM stampede）：Caffeine 单飞。
- Race A（慢旧 reader 覆盖已存新值）：CAS 版本比较。
- Race B（迟到写落在空 key）：墓碑版本地板 + CAS。
- 提交前失效→旧值被固化：失效排到 after-commit。
- 乱序还款 / 乱序 evict：版本单调，CAS 让高版本恒胜。
- 跨 pod L1 stale：Pub/Sub 广播（**仅在 broadcast.enabled=true 时**）。
- 缓存故障放大成 GET 故障：读失败 fallback 回源 DB。

**⚠️ 仍然存在（按重要性）：**

1. **after-commit 执行前宕机 / 失效本身失败**（见 4.4 详解）——最重要的一条。
2. **tombstone-ttl < 读写时滞**：极慢的 GET（读 DB 到写回 L2 的间隔）超过墓碑存活时间，地板先过期，
   Race B 在该边缘重开。靠把 tombstone-ttl 设得 ≥ 合理最大时滞来兜（仍 ≤ delete 的暴露）。
3. **广播丢失**（Pub/Sub at-most-once）：某 pod 订阅断连时漏收，其 L1 stale 到 local-ttl。已接受。
4. **version 完整性耦合**：若将来加"改 read model 却不改 paidAmount"的字段（如争议标记），版本不再完整，
   同版本不同值会被 CAS 误判。需换专用 version 列。已在注释/3.x 标注。
5. **跨 pod 冷 key thundering herd**：单飞只在 JVM 内；N 个 pod 首次 miss 仍各回源一次。可上 Redis 互斥锁，
   本项目按低 QPS 选择不做。
6. **缓存穿透（不存在的 id）**：无 negative cache，反复查同一个不存在 id 每次打 DB。低风险，可按需加短 TTL 空值。

### 4.4 重点：after-commit 执行前宕机会怎样？

这是"失效是事务的**副作用**而非事务的一部分"必然带来的窗口。时间线：

```text
t0  还款 transaction COMMIT 成功（DB 里 paid=100 已落盘）
t1  JVM 在 afterCommit 回调执行前崩溃（kill -9 / OOM / 宿主掉电）
    → 墓碑没写、广播没发
结果：L2 仍是旧值或旧墓碑；其他 pod L1 仍是旧值；本 pod L1 随进程消失。
     stale 一直持续到 L2 的 remote-ttl / 各 pod 的 local-ttl 自然过期。
```

同一类还有：**afterCommit 跑了，但那一刻 Redis 挂了**——`evict` 里写墓碑 try/catch 住，只清了本 pod L1，
L2/其他 pod 仍旧值到 TTL（代码注释 `statement_read_cache_tombstone_failed action=l1_evicted_only` 就是这条）。

**根因**：commit 与 evict 之间没有原子性。after-commit 失效是 **best-effort / at-most-once** 的旁路动作，
DB 提交成功不代表失效一定发生。

**怎么根治（如果业务要"崩溃也不丢失效"）**：把失效做成**事务内 outbox 事件 + relay 至少一次**：

```text
还款 transaction 内（同一个 DB 事务，原子）：
   UPDATE statements ...            -- 业务写
   INSERT INTO outbox (statement.invalidate, statementId, version)   -- 失效意图
COMMIT                              -- 业务和"失效意图"要么一起成功要么一起回滚

Outbox relay（已有基础设施，至少一次）：
   poll 出 statement.invalidate → 执行"写墓碑 + 广播"
   → 崩溃只会让它延迟/重试，绝不丢失（at-least-once）；写墓碑/广播本身幂等
```

代价：失效从"亚毫秒 after-commit"变成"outbox poll 延迟（~1s）"，且多一次 DB 写。**取舍**：当前用 after-commit
（快、简单，崩溃窗口由 TTL 兜底，账单容忍秒级 stale）；要"崩溃也不丢失效"再升级到 outbox-driven。本项目已有
outbox + 计划中的 statement 事件，可平滑切换——这正是"我知道怎么把 best-effort 升级成 exactly-once-effect"
的现成答案。

> 一句话记忆：**after-commit 失效是"提交成功后大概率会失效"，不是"提交成功就一定失效"。要后者，把失效写进
> 事务（outbox）让 relay 至少一次去做。**

### 4.5 够不够硬核面试？——够了，别再镀金

这套实现已经覆盖了缓存面试几乎所有硬核点，且**深度远超"cache-aside + TTL"的标准答案**：

- 两级缓存 / cache-aside / 读路径唯一构造者；
- 三大经典问题词汇 + 对策：穿透、击穿（单飞）、雪崩（jitter）；
- 缓存上的**乐观并发**：版本号 + Lua 原子 CAS；
- "delete 擦除版本 → 墓碑版本地板"这个**很少人想到的 subtle 点**；
- 失效时机：after-commit，以及它**关不掉迟到写**的精确原因；
- 跨 pod 一致性：Pub/Sub 广播 vs Kafka 的选型；
- **成熟度信号**：能主动说清剩余 race（崩溃窗口）+ 升级路径（outbox-driven 失效）。

**结论：不要再往缓存里加功能了。** 继续做 outbox-driven 失效 / negative cache / 跨 pod 互斥锁都是镀金，
面试 ROI 低，还容易过度设计——而"知道何时停"本身就是高级信号。真正还值得做的只有一件、且是**另一个维度**
（测试严谨度，不是缓存功能）：**Redis Testcontainers 把那段 Lua CAS 在真环境钉死**（见 3.6）。它既补上唯一的
正确性验证缺口，又是"我用 Testcontainers 验证并发脚本"的加分谈资。

面试时的最佳用法不是再写代码，而是能对着本文 **3.1 的 race 目录 + 3.2 的三做法对比 + 4.4 的崩溃窗口**
把故事讲透——这套文档就是讲稿。
