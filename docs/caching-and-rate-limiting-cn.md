# 缓存与限流统一说明（Caching & Rate Limiting）

> 本文合并自三份旧文档：`cache-snapshot-design-cn.md`（statement 两级缓存）、
> `cache-invalidation-broadcast-cn.md`（跨 pod 失效 + 迟到写竞态）、
> `distributed-cache-cn.md`（限流 / Lua / Redisson / 通用规则）。
> 合并时删除了三份之间的重复内容，**改正了旧文档里"跨 pod 重建锁没做"的过时说法**
> （代码里已实现，默认开启），并把缓存名从 `v1` 对齐到代码现状 `v2`。
> 原三份已归档在 `docs/archive/`。

> 关键词：distributed cache, Redis, Caffeine, cache-aside, L1/L2, 缓存穿透/击穿/雪崩,
> versioned CAS, tombstone, Lua atomicity, rate limiting, sliding window, fail-open,
> Redisson, 分散キャッシュ, レート制限。

相关代码：

- `statement/application/StatementReadService.java`（statement GET 两级缓存核心）
- `statement/application/StatementReadCacheProperties.java`（缓存配置）
- `statement/infrastructure/cache/RedisStatementReadCacheBroadcaster.java` / `StatementReadCacheEvictListener.java`（跨 pod L1 失效广播）
- `risk/infrastructure/redis/RedisRiskVelocityCounter.java`（Redis 滑动窗口限流）
- `risk/infrastructure/jdbc/JdbcRiskVelocityCounter.java`（SQL `COUNT(*)` 精确对照实现）
- `risk/application/RiskVelocityCounter.java`（port）

---

## 第一部分：缓存的本质判断（最重要，先想清楚再写代码）

面试里"什么时候**不**该缓存"比"怎么加缓存"更能体现成熟度。一条判断线：

> 不要因为"数据读得多"就缓存，要因为"同一份数据被很多请求复用、且能卸掉真实成本"才缓存。

### 1.1 读缓存的唯一价值来源：命中率（temporal locality）

读缓存只有一个收益来源：**同一个 key 在 TTL 窗口内被重复读到的概率**。命中率低，缓存就是纯负担（多一层一致性问题、多一次网络、多一份内存）。

### 1.2 为什么删掉了 card 快照缓存（一个值得讲的反例）

项目早期缓存过 `card` 快照（卡状态 + 账户归属），后来**删掉了**。删它的理由正是上面那条判断线的反向应用：

1. **单卡请求率低**：一个持卡人一天就几笔授权，分散在全天；短 TTL 内重复刷同一张卡几乎不发生 → 命中率低。
2. **安全 TTL 与命中率是死结**：card status 是授权的 gate（blocked 卡必须立刻拒），TTL 必须短到能及时失效；而"必须短"恰好压死命中率。**对"stale 会出事"的数据，你能安全使用的 TTL，正好是命中率最差的 TTL。**
3. **它不是瓶颈**：授权热路径真正的瓶颈是 `credit_accounts` 的 `SELECT ... FOR UPDATE`（同账户串行）；读 card 只是一次非锁定主键查询，本来就便宜。缓存一个非瓶颈的廉价读，收益有限。
4. **幂等重试根本不读 card**：`AuthorizationService` 先 claim，`if (!claimed) return`，重试在读 card **之前**就返回了 → 享受不到缓存。

> **反向事实**：如果硬把 card 缓存留着，为了安全只能设很短 TTL（比如 10s），命中率极低，几乎每次还是回源 DB；却凭空多出"刚 block 的卡在 TTL 内仍被放行"的风险。**删掉它本身就是 interview 加分点：会判断"该不该缓存"。**

### 1.3 为什么 velocity 计数适合 Redis（而 card 不适合）

velocity 计数和读缓存**不是一回事**：

- 读缓存：缓存"某次读的结果"，**依赖命中率**。
- velocity：把**整个计数 workload 从主库搬到 Redis**，**不依赖命中率**。

原本每笔授权都要 `SELECT COUNT(*) FROM authorizations WHERE card_id=? AND created_at>=?`，**每次都打主库**——而主库正是被 `FOR UPDATE` 占着的瓶颈。改成 Redis 后，**每一笔授权都少打一次主库**，对每个请求无条件成立。这才是支付系统里 Redis 最对口的用法：**high-traffic + 分布式 + 原子计数**。

### 1.4 为什么永远不能缓存额度

不要缓存：`availableCredit`、`reservedAmount`、`postedBalance`、幂等 claim 结果、repayment 入账结果。

- 额度是**高并发写模型**，正确性依赖 `credit_accounts` row lock。
- 缓存额度会绕开 `SELECT ... FOR UPDATE`，可能导致**超额授权**。
- 在金融后台里，cache 可以减轻读压力，但**不能替代 transaction boundary**。

正确做法仍然是：

```text
creditAccountRepository.findByIdForUpdate(accountId)
-> account.reserve(...)
-> creditAccountRepository.update(account)
```

> 一句话边界：**钱用 DB 的强一致（行锁 / 唯一约束 / 版本号）；只读、可重建的展示快照才进缓存。**

---

## 第二部分：statement GET 两级缓存（本项目核心实现）

唯一的业务读缓存：`GET /api/statements/{id}`。缓存的是 `StatementReadModel`（只读展示模型，没有 domain behavior），不是 `Statement` aggregate（写模型）。Redis 存 JSON read model，避免把缓存当 domain object store。

### 2.1 缓存范围与配置（对齐代码默认值）

cache name = `statement-read-model-v3`（带版本号，见 §2.6）。配置前缀 `statement.read-cache.*`：

| 配置 | 默认值 | 作用 |
| --- | --- | --- |
| `local-ttl` | 30s | Caffeine L1 TTL。**必须比 L2 短**：多 pod 下别的实例写完不会 evict 本机 L1，短 TTL 限制 stale 窗口 |
| `local-maximum-size` | 1000 | L1 容量上限。生产 cache 必须有上限，否则热点/恶意 key 把 heap 变成"隐形数据库" |
| `remote-ttl` | 5m | Redis L2 基础 TTL |
| `remote-ttl-jitter` | 30s | 实际 TTL = `remote-ttl + [0, jitter]`，错开过期，防 **cache avalanche** |
| `tombstone-ttl` | 10s | 失效写入的"版本地板"墓碑存活时间（见 §3.3） |
| `rebuild-lock-enabled` | **true** | 跨 pod 重建锁防击穿（见 §3.2），**默认开启** |
| `rebuild-lock-ttl` | 2s | 重建锁持有时间（也是持锁者崩溃后的自动释放上限） |
| `rebuild-lock-wait-attempts` × `-wait-interval` | 5 × 20ms | loser 最多自旋 ~100ms 等 winner 填好 L2，超时 fail-open 自己回源 |
| `broadcast.enabled` | **false** | 跨 pod L1 失效广播（Redis Pub/Sub，见 §3.5），**默认关闭** |

### 2.2 读路径

```text
GET /api/statements/{id}
-> StatementController.get(id)
-> StatementReadService.get(id)
   -> Caffeine L1（localCache.get(id, loader)，自带同 JVM single-flight）
   -> [miss] readRedis(id)            // L2
   -> [L2 miss] 重建：rebuildWithSingleFlight 或 rebuildFromDb
       -> StatementGenerationService.get(id) -> MySQL
       -> CAS 写回 L2
```

`Cache.get(key, loader)` 自带**同 JVM per-key single-flight**：同一个 statementId 同时 miss 时，本 JVM 内只有一个线程进入 L2/DB loader，其他线程等结果。这只挡得住本 JVM；跨 pod 的并发 miss 由 §3.2 的重建锁处理。

### 2.3 cache-aside：读路径是缓存值的唯一构造者

- 读：先 cache，miss 回源 DB，再写回 cache。
- 写：**不更新 cache，而是失效 cache**，让下一次读重新从 DB 构造。

> **反向事实（为什么删而不是更新）**：并发下"写时更新 cache"很容易把旧计算结果写进去；而且写侧要会拼完整 read model，于是出现两条构造路径，可能发散。删/失效让"只有读路径构造缓存值"，单一来源永不发散，更好排查。

### 2.4 失败与自愈策略（cache 不是 hard dependency）

| 情况 | 处理 | 不这么做会怎样 |
| --- | --- | --- |
| Redis 读失败 | 记 warning，回源 MySQL | Redis 抖动直接让 GET API 失败，cache 反成可用性风险 |
| Redis 写失败 | 保留本 JVM L1，本次请求仍成功 | 一次写失败就让请求失败 |
| Redis JSON 坏 / 非本版本格式 | 删坏值，回源重建 | 一个坏值让同 key 后续永久解析失败 |
| loader 返回 `null` | 不写 cache | 形成 negative cache |

### 2.5 为什么不缓存 404（不做 negative cache）

statement id 是系统内部生成的 UUID，不可猜测，**不存在"海量查不存在 key"的攻击面**，所以故意不加布隆过滤器 / 空值缓存。需要防攻击流量时才单独设计短 TTL negative cache。这点要会主动说明，而不是无脑加布隆过滤器。

### 2.6 为什么 cache name 带版本号

Redis value 是 JSON contract。字段语义不兼容时，**改 cache name（例如 `v2`→`v3`）比清全量 Redis 更可控**，旧 key 自然过期。本项目曾因为 L2 value 从"纯 JSON"变成"`<version>|<payload>`信封"（见 §3.3）把名字 bump 到 `v2`；这次又因为 `<version>` 的语义从 `paidAmount` 派生值改成 `statements.version`，继续 bump 到 `v3`。

---

## 第三部分：并发与竞态深挖（counterfactual 重点）

这一节是整套缓存的精华，也是面试最能抗压的部分。statement 缓存按"由近及远"叠了四层防护：同 JVM 单飞 → 跨 pod 重建锁 → 迟到写 CAS+墓碑 → 跨 pod L1 广播。

### 3.1 三大经典问题在本项目的落点

| 问题 | 是什么 | 本项目对策 |
| --- | --- | --- |
| **穿透 penetration** | 查**不存在**的 key 每次打 DB（常是攻击） | **故意不做**（内部 UUID 不可猜，见 §2.5） |
| **击穿 breakdown** | **单个热 key 过期瞬间**大量请求同时回源 | 同 JVM 单飞 + **跨 pod 重建锁**（§3.2） |
| **雪崩 avalanche** | **大量 key 同时过期**或 Redis 整体挂 | **TTL jitter** + 多级缓存 + fail-open 回源 |

### 3.2 跨 pod 重建锁（防击穿，代码里默认开启）

> ⚠️ 旧文档曾说"跨 pod 互斥锁本项目没做 / 是未来选项"——**这是过时说法**。`StatementReadService.rebuildWithSingleFlight` 已实现，`rebuild-lock-enabled` 默认 `true`。

Caffeine 的 single-flight 只在**同一个 JVM** 内生效；热点 statement 的 L2 过期时，10 个 pod 仍会有 10 个线程同时回源 MySQL——这就是教科书"缓存击穿"，只是发生在多实例维度。做法：

```text
L2 miss 后：SET <lockKey> <token> NX PX <2s>
  winner（抢到锁）  : 回源 DB + CAS 写回 L2，finally 释放锁
  loser （没抢到）  : 自旋最多 5×20ms 重读 L2；winner 填好后直接复用，不打库
                     超时仍空 → fail-open 自己回源（绝不无限等）
```

几个**反向事实 / 设计点**：

- **`SET NX` 为什么必须带 `PX`(TTL)**：持锁者崩溃/卡住时锁要能自动过期，否则热点 key 被永久锁死。TTL 也是"持锁者死后多久放下一个重建者进来"的上限。
- **释放锁为什么走 Lua（`GET==token 再 DEL`）**：必须两步原子。直接 `DEL` 会误删"我超时后、别人刚抢到的那把锁"（经典误删他人锁 bug）。
- **为什么不需要 fencing token**：被锁保护的动作是"只读重建 + CAS 写回"，**本身幂等**；锁偶尔不互斥最坏只是多一次回源，不破坏数据。所以这把锁是 best-effort **优化**，不是正确性闸——抢锁失败/超时/Redis 故障一律 fail-open。
- 指标 `statement.read_cache.rebuild_lock{outcome=winner|contended_hit|fallback_db|error}` 观测命中/竞争/降级比例。

### 3.3 迟到写竞态（Race A / B）与"版本号 + Lua CAS + 墓碑"

`after-commit delete` 关不掉 cache-aside 固有的"迟到写"。约定：`version = statements.version`，新关账从 `0` 开始；每次会改变 statement read model 的状态推进都在同一 DB transaction 内递增。这里不要再用 `paidAmount` 代替 version，因为 overdue 标记、due date 调整、争议标记或展示字段修正都可能让 read model 改变但金额不变。

**两条 race（很多人只看到一条）：**

```text
Race A（慢旧 reader 覆盖已存新值）：
  t0 GET-A 读 DB version=0，写 L2 很慢
  t1 还款 commit，evict
  t2 GET-B 读 DB version=1 → 写 L2 {1}      ← L2 已是新值
  t3 GET-A 迟到写 {0}                        ← 覆盖新值！

Race B（迟到写落在刚被清空的 key 上，更高频、更难缠）：
  t0 GET-A 读 DB version=0，写 L2 很慢
  t1 还款 commit，evict（delete → L2 空）
  t2 GET-A 迟到写 {0}                        ← 落空 key，旧值活到 remote-ttl
```

**三种做法对比（关键看 Race B）：**

| 做法 | Race A | Race B |
| --- | --- | --- |
| ① 无版本，evict=DELETE，无条件 SET | ❌ | ❌ |
| ② 只加版本（evict 仍 DELETE，写=CAS"version≥stored 或不存在才写"） | ✅ | ❌ delete 把可比版本也删了，迟到写落空 key 命中"或不存在"分支 |
| ③ **版本 + 墓碑**（evict 不 delete，改写"版本地板"墓碑；读回填同走 CAS） | ✅ | ✅ |

> 做法②的危险在于**看着像修好了**（有版本有 CAS），主 race 还开着，还容易被只测 Race A 的绿测试掩盖。**墓碑给了一个"哪怕失效后也存在的版本地板"**，于是 CAS 在空 key 问题上也站得住。

**本仓库的最终实现（做法③）：**

- L2 信封 = `<version>|<payload>`：真实值 `<version>|<JSON>`；墓碑 `<version>|`（`|` 后为空 = tombstone，读到当 miss 回源）。用"数字前缀 + 分隔符"而非 JSON 包版本，是为了让 Lua 端不依赖 `cjson`。
- 版本来源 = `statements.version`：`Statement.applyRepayment(...)` 等写模型状态变化会递增它，`StatementReadModel.from(statement)` 和 `StatementReadService.evictAfterCommit(statement)` 使用同一个版本值。
- evict 三步（还款 after-commit）：清本 pod L1 → 写**墓碑**(CAS 版本地板，`tombstone-ttl` 10s) → 广播让其他 pod 清 L1（§3.5）。
- 读回填与墓碑写**共用同一段 Lua CAS**：

```lua
-- KEYS[1]=key  ARGV[1]=incoming version  ARGV[2]=信封  ARGV[3]=TTL毫秒
local incoming = tonumber(ARGV[1])
local current = redis.call('GET', KEYS[1])
if current then
  local cur = tonumber(string.match(current, '^([%-%d]+)|'))
  if cur ~= nil and cur > incoming then
    return 0   -- 已有更新版本/墓碑，拒绝这次较旧的写
  end
end
redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
return 1
```

**为什么选墓碑而不是 write-through（写新值）**：两者都关掉 Race B，区别在写侧放什么。墓碑让"读路径是缓存值唯一来源"（不发散）、写侧只需版本零 read-model 知识、写 L2 负载极小；代价是写后首读会 miss 回源一次。statement 查询低 QPS，热缓存收益在噪声里，所以选墓碑守不变式。

### 3.4 after-commit 失效不是强一致（必须答准的高频追问）

`evictAfterCommit` 只决定"写侧什么时候失效"（排到 commit 之后，避免"事务内提前删→提交前 GET 又写回旧值"）。但它**消除不了**所有 stale：

| stale 来源 | 最长窗口 | 触发条件 |
| --- | --- | --- |
| 跨 pod L1 未失效 | `local-ttl`（30s） | 还款在 pod A，pod B 的 L1 还没过期（除非开 §3.5 广播） |
| 墓碑/CAS 兜底失败 | `remote-ttl`（5m） | 墓碑写失败（Redis 抖动），退化成只清本 pod L1 |
| **after-commit 执行前宕机** | `remote-ttl` / `local-ttl` | commit 成功后、afterCommit 回调执行前 JVM 崩溃 → 墓碑没写、广播没发 |

最后一条最重要：**commit 与 evict 之间没有原子性**，after-commit 失效是 best-effort 的旁路动作。

> **怎么根治（如果业务要"崩溃也不丢失效"）**：把失效做成**事务内 outbox 事件 + relay 至少一次**——在还款事务里同时 `INSERT INTO outbox (statement.invalidate, id, version)`，由已有的 Outbox relay 至少一次地执行"写墓碑 + 广播"（写墓碑/广播本身幂等）。代价是失效从亚毫秒变成 ~1s 的 poll 延迟 + 一次 DB 写。本项目刻意停在 after-commit（账单容忍秒级 stale，强一致直接读 DB），但**能说清升级路径**就是成熟度信号。

### 3.5 跨 pod L1 失效广播：Redis Pub/Sub（默认关闭）

evict 只清了本 pod 的 L1，其他 pod 的 Caffeine 仍留旧值最长 `local-ttl`。开启 `broadcast.enabled=true` 后，evict 再 PUBLISH 一条 Redis 消息，所有 pod 的订阅者 `invalidateLocal`，把跨 pod L1 窗口从 `local-ttl` 压到一次广播延迟。

**为什么失效广播用 Pub/Sub 而不是 Kafka（重要选型题）：**

| 维度 | L1 失效广播 | Statement Notification |
| --- | --- | --- |
| 扇出 | **广播**：每个 pod 都要收到清自己 L1 | **竞争消费**：N 个 pod 只 1 个发通知 |
| 可靠性 | **best-effort 即可**（L1 有 TTL 兜底，丢一条最多 stale 一个 TTL） | **at-least-once + 幂等**（不能丢/不能重复） |
| 持久化/重放 | 不需要（pod 重启 L1 本来就空） | 需要 |
| 天生匹配 | **Redis Pub/Sub** | **Kafka 消费者组** |

> **同一个业务事实（还款/关账）≠ 同一个 transport**：L1 失效走 Redis Pub/Sub（天生广播、亚毫秒、fire-and-forget 配 TTL 兜底）；Notification 走 Outbox→Kafka→Inbox（事务保证 + at-least-once + 幂等 + 可重放）。用 Kafka 做广播会被迫给每个 pod 一个唯一 group（group 膨胀 + 重放历史失效消息 + 1~3s 延迟丢掉低延迟优势）；用 Pub/Sub 做 Notification 会丢消息（at-most-once）。
>
> **为什么也不用 Redis Stream**：Stream 原生是 consumer group 竞争消费，方向和"fan-out 广播"相反；要广播得给每个 pod 独立 group，把 Kafka 那套 group 膨胀问题搬到 Redis，而它的持久化/重放成本只是为缩小一个 `local-ttl` 已兜住的窗口，不划算。

**广播实现的关键约束（最容易出 bug 处）：**

1. **订阅者回调只调 `invalidateLocal`（L1-only），绝不调 `evict`**——否则 `evict` 会再删 L2 + 再广播，N 个 pod 互相广播形成**风暴/无限循环**。失效广播必须单向：写侧 evict 负责"删 L2 + 广播"，接收侧只清自己 L1。
2. 写侧先同步 `localCache.invalidate` 清自己，再广播给别人（不依赖自己的广播回环）。
3. 先删/墓碑 L2 再 PUBLISH（订阅者去重读时 L2 已失效，直接回源）。
4. 频道名带 cache 版本号，与 L2 key 对齐；毒消息（解析 UUID 失败）只告警丢弃，退回 TTL 兜底。
5. 用独立 `RedisMessageListenerContainer`（专用连接 + 线程），不占业务请求线程；`@ConditionalOnProperty` 仅开启时装配，默认注入 no-op 广播器，单实例/测试零负担。

### 3.6 这把重建锁和"分布式锁保护转账"的本质区别

| | statement 缓存重建锁 | 转账 / 扣额度 |
| --- | --- | --- |
| 被保护动作 | 只读重建 + CAS 写回（**幂等**） | 对共享可变状态的**非幂等**写 |
| 锁的角色 | best-effort **性能优化** | **正确性闸** |
| 失败策略 | fail-open（多一次回源而已） | 绝不能放过 |
| 用什么 | 几行 `SET NX` + Lua 释放，全程 fail-open | **DB 行锁 `FOR UPDATE` / 唯一约束 / 版本号**（单一权威） |
| Redlock / fencing token | 不需要（幂等） | Redis 锁在 GC 暂停 + 时钟漂移下并非绝对互斥，不能用作钱的唯一保证 |

> 一句话：**钱用 DB 的强一致，缓存重建用 best-effort Redis 锁。**

---

## 第四部分：限流（Rate Limiting）

本项目的 velocity 限流 = Redis 滑动窗口日志。每张卡一个 ZSET：key=`risk:velocity:{cardId}`，member=每次尝试的唯一标识，score=毫秒时间戳。`risk.velocity.store` 默认 `redis`，可切 `jdbc`（精确 `COUNT(*)` 对照）。

```lua
-- KEYS[1]=窗口key; ARGV[1]=now(ms); ARGV[2]=窗口下界(ms); ARGV[3]=唯一member; ARGV[4]=TTL秒
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3])                    -- 记入本次尝试
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[2]) -- 裁掉窗口外旧记录
local count = redis.call('ZCARD', KEYS[1])                       -- 窗口内尝试数(含本次)
redis.call('EXPIRE', KEYS[1], ARGV[4])                          -- 续期,清理空闲卡的key
return count
```

**三个反向事实（不写会怎样）：**

- **member 必须唯一**：同一毫秒并发尝试 score 相同，ZSET 成员若不唯一会被去重 → 少计。所以用 `nowMillis + "-" + UUID`。
- **必须 EXPIRE**：不续期的话，海量历史卡的空 ZSET 永久堆积 → Redis 内存泄漏。
- **必须 Lua 原子**：不然并发请求在 ZADD 和 ZCARD 之间交错 → 计数错乱、限流失效。

### 4.1 五种限流算法与选型

| 算法 | 思路 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **Fixed Window** | 时间桶 `INCR` | 最简单、省内存 | **边界突刺**：交界处可达 2x |
| **Sliding Window Log** | 存每次请求时间戳(ZSET) | 精确、无突刺 | 内存随请求数增长 |
| **Sliding Window Counter** | 多个小桶加权 | 近似无突刺、省内存 | 略不精确 |
| **Token Bucket** | 匀速发令牌，桶有容量 | **允许突发**(burst 到容量) | 不直接给"窗口内次数" |
| **Leaky Bucket** | 请求入桶匀速流出 | 输出绝对平滑 | 不允许突发、可能排队 |

> 本项目**两种都用了，按语义分层**（这是 interview 的核心对比点）：
>
> - **风控 velocity 选 sliding window log**（`RedisRiskVelocityCounter`，ZSET）：风控要的是"过去 N 秒**精确发生几次**"这个**信号**喂给决策，不是边缘 reject 的硬阀门。内存不会爆：`ZREMRANGEBYSCORE` 裁窗口外 + `EXPIRE` 清空闲 key，单卡 ZSET 被窗口内尝试数限制。
> - **API 入口保护选 token bucket**（`RedisTokenBucketRateLimiter`，HASH `{tokens, ts}` + lazy refill Lua）：API 保护要的恰恰是"准不准过"这个硬阀门，capacity 表达允许的突发、refill 速率表达允许的持续吞吐，状态 O(1)、key 基数大（每个调用方一个桶）也不怕。挂在 `AuthorizationRateLimitInterceptor` 上按调用方 IP 限 `POST /api/authorizations`，超限 `429 + Retry-After`。
>
> 同一个"限流"，风控层给 decline（业务语义），API 层给 429（协议语义）——维度、算法、出口三个都不同。

### 4.2 在哪一层限流

- **边缘/网关**（API Gateway throttling、WAF rate-based rule、Nginx/Envoy）：粗粒度、按 IP/API key，请求进应用前挡掉。
- **应用内分布式**（跨实例共享计数）→ Redis（本项目两处都是：velocity 按卡、API token bucket 按调用方 IP，桶在 Redis 里所以多实例共享同一个调用方的额度）。
- **应用内单机**（Resilience4j `RateLimiter`）：per-instance，多副本下对小额 per-key 配额不准；适合的场景是**出站**客户端限流（provider 配额可按实例数摊分）。

### 4.3 fail-open：Redis 挂了怎么办

实现里 catch `DataAccessException` 后返回 `VelocityCheckResult(count=0, degraded=true, source=REDIS)`，**显式 fail-open**：Redis 抖动时降级放行，而不是让授权失败。

- **counterfactual**：若 fail-closed（抛异常），一次 Redis 抖动会让**全系统所有授权被拒**——为一个非关键风控信号牺牲整体可用性，得不偿失。
- **前提**：velocity 只是**多个风控规则之一**（限额、外部风控、credit limit 仍生效）。若它是唯一安全闸，就要重新权衡（fail-closed 或备用路径）。
- **observability**：adapter 记 `risk.velocity.redis.unavailable`，service 记 `risk.velocity.fallback.allow{source=redis}`。短时间连续 fallback allow 要报警。
- **不默认回查 DB**：Redis 故障时全量 fallback 到 `COUNT(*)` 会把每笔授权压回主库，可能把 Redis brownout 放大成 MySQL/Hikari brownout。真要做 DB fallback 也要加 bulkhead/timeout/rate limit，最好走 read replica，只对高风险或采样请求启用。

---

## 第五部分：Redis Lua 原子性

### 5.1 为什么 Lua 能保证原子

Redis 命令**单线程串行**执行；一段 Lua 脚本执行期间**不会被其他命令打断**。所以"裁剪+ZADD+ZCARD+EXPIRE"四步、或"GET 比较+SET"两步放进一个脚本 = 一个**无需显式分布式锁**的原子操作。

### 5.2 EVAL vs EVALSHA

- `EVAL`：每次把脚本文本发过去。
- `EVALSHA`：先 `SCRIPT LOAD` 得 SHA1，之后只发 SHA1，省带宽。Spring 的 `DefaultRedisScript` 默认走 EVALSHA，遇 `NOSCRIPT` 回退 EVAL。

### 5.3 坑

- **脚本会阻塞单线程**：Lua 里别写重循环/大范围操作，会卡住整个 Redis。本项目脚本都是 O(log N) 级 ZSET 操作或单 key GET/SET，可接受。
- **Redis Cluster 多 key 必须同 slot**：一个脚本里所有 KEYS 必须 hash 到同一 slot，否则 `CROSSSLOT`。需要时用 hash tag `{cardId}` 固定到同 slot。本项目脚本只用单 key，天然安全。
- **确定性/副本一致**：脚本里产生的随机/时间若用于写入，主从复制下要小心。本项目把 `now` 和唯一 member 作为 **ARGV 从客户端传入**，正是为了确定性。

---

## 第六部分：Redisson —— 什么时候 buy，什么时候 build

**所有现成限流库（Redisson `RRateLimiter`、Bucket4j-redis、Spring Cloud Gateway `RedisRateLimiter`）底层都是 Lua。** "用库"没绕开 Lua，只是把它藏起来——懂 Lua 才有资格选库、调库。

- **Redisson 是什么**：完整 Redis 客户端 + 分布式对象框架，提供 `RLock`（可重入分布式锁，带 watchdog 自动续租）、`RMap`、`RSemaphore`、`RRateLimiter` 等。核心 Apache-2.0，部分高级特性在 PRO。
- **何时 buy**：**同时需要一批分布式原语**，尤其是**分布式锁**——`RLock` 的边界 case（续租、误删别人的锁、可重入）手写极易出 bug。
- **何时 build**：像本项目的滑动窗口计数 / 缓存重建锁——语义特殊、只有几行、要完全掌控数据模型和 fail-open 行为，引入 Redisson 不划算。

> 判断线（可背）：通用、边缘、粗粒度限流 → API Gateway；应用内跨实例按业务身份限流 → Bucket4j/Redisson；语义特殊且简单的（风控 velocity）→ 手写 Lua；需要分布式锁等复杂原语 → Redisson。

---

## 第七部分：缓存设计通用规则（interview 必备）

### 7.1 读写模式

| 模式 | 说明 | 备注 |
| --- | --- | --- |
| **Cache-Aside（旁路）** | 读 miss 回源回填；写更新 DB 后**删/失效缓存** | 最常用（本项目） |
| **Read-Through** | 应用只问缓存，缓存自己回源 | |
| **Write-Through** | 写时同步写缓存+DB | 强一致、写慢 |
| **Write-Behind** | 先写缓存，异步刷 DB | 写快、有丢数据风险 |
| **Refresh-Ahead** | 热点 key 过期前主动刷新 | 防击穿 |

### 7.2 一致性要点

- **更新时删缓存而不是更新缓存**（并发下更新易写旧值）。
- **写失效要放在事务提交之后**（after-commit），否则提交前的 GET 可能把旧值写回（见 §3.4）。
- 极端"读旧值并回填"竞争 → 延迟双删 / 短 TTL 兜底 / **版本号 CAS + 墓碑**（本项目，§3.3）。

### 7.3 其他高频点

- **热 key**：某明星卡/商户被打爆 → 本地缓存(L1)、key 拆分、读副本。
- **大 key**：单个集合无限增长 → 拆分、设上限。本项目 ZSET 用 `ZREMRANGEBYSCORE` + `EXPIRE` 防膨胀。
- **淘汰策略**：`maxmemory-policy`（LRU/LFU/TTL/noeviction）；缓存可丢，DB 才是 source of truth。
- **L1+L2**：本地 Caffeine（纳秒级、不跨实例）+ 分布式 Redis（跨实例、有网络成本）；L1 TTL 要短。

---

## 第八部分：race / 故障 scorecard

**✅ 已经关掉：**

- 缓存雪崩：TTL jitter 错开过期。
- 缓存击穿：同 JVM 单飞 + 跨 pod 重建锁（默认开）。
- Race A（慢旧 reader 覆盖新值）：CAS 版本比较。
- Race B（迟到写落空 key）：墓碑版本地板 + CAS。
- 提交前失效→旧值固化：失效排到 after-commit。
- 乱序还款/乱序 evict：版本单调，CAS 让高版本恒胜。
- 跨 pod L1 stale：Pub/Sub 广播（**仅在 `broadcast.enabled=true` 时**，默认关）。
- 缓存故障放大成 GET 故障：读失败 fallback 回源 DB。

**⚠️ 仍然存在（按重要性，且都已知/可接受）：**

1. **after-commit 执行前宕机 / 失效本身失败**（§3.4）——最重要，升级路径是 outbox-driven 失效。
2. **`tombstone-ttl` < 读写时滞**：极慢 GET 超过墓碑存活，地板先过期、Race B 在边缘重开（设 ttl ≥ 合理最大时滞兜底）。
3. **广播丢失**（Pub/Sub at-most-once）：订阅断连漏收，L1 stale 到 `local-ttl`。已接受。
4. **version 推进遗漏**：若将来新增会改变 read model 的写路径，却忘记递增 `statements.version`，CAS 会误判新旧快照。新增状态转换时要把 version bump 当成 domain invariant。
5. **跨 pod 冷 key**：重建锁已覆盖（默认开）；关闭时退回 N pod 各回源一次。
6. **穿透**：无 negative cache（内部 UUID，低风险，见 §2.5）。

---

## 第九部分：硬核面试 Q&A

**Q1. 你给 velocity 用 Redis，却把 card 缓存删了，为什么？**
读缓存只有"TTL 内重复读同一 key"才有收益；card 单卡请求率低、status 是授权 gate 必须短 TTL，命中率被压死，且它不是瓶颈、幂等重试还不读它。velocity 不同：把每笔授权对主库的 COUNT 往返整体搬到 Redis，**不依赖命中率、对每个请求都减负**，而主库正是被 `FOR UPDATE` 占着的瓶颈。
- *追问：card 读会不会成新瓶颈？* 不会，它是非锁定主键查询；真正要优化的是账户那条锁路径，而那条因强一致不能缓存。

**Q2. statement 缓存失效后，并发 GET 把旧值写回怎么办？**
单纯 after-commit delete 关不掉：delete 把可比版本也删了，迟到写落在空 key 上（Race B）。我用"正式 statement version + Lua CAS + 墓碑"：evict 不 delete，而写一个 `version=statements.version` 的墓碑做地板；读回填和墓碑写共用一段 CAS（`stored.version > incoming 则拒绝`），旧版本迟到写被挡下。
- *追问：墓碑比 delete 更容易不一致吗？* 设计语义上不会（读到墓碑=miss=回源，永不 serve 错值）；实现风险上会（多了 Lua/版本/墓碑识别/TTL 调参的出错面）——所以剩余 gap 是"用真 Redis Testcontainers 把那段 Lua 钉死"。

**Q3. 热点账单缓存过期瞬间打爆 DB 怎么办？**
同 JVM 用 Caffeine `Cache.get(key,loader)` 单飞；跨 pod 用 Redis 重建锁（`SET NX PX` + Lua 安全释放）做分布式 single-flight，winner 回源、loser 自旋复用，超时 fail-open。锁是 best-effort 优化，不是正确性依赖，所以不需要 fencing token。

**Q4. 缓存失效广播为什么用 Redis Pub/Sub 而不是 Kafka？Notification 为什么反过来？**
L1 失效是 fan-out 广播 + best-effort（TTL 兜底）→ Pub/Sub 天生匹配；Kafka 是竞争消费，广播得给每个 pod 唯一 group（膨胀 + 重放 + 延迟）。Notification 要 at-least-once + 幂等 + 可重放 → Outbox→Kafka→Inbox；用 Pub/Sub 会丢消息。同一业务事实 ≠ 同一 transport。

**Q5. Redis 滑动窗口为什么必须用 Lua？Redis 挂了怎么办？**
"裁剪→ZADD→ZCARD→EXPIRE"多步，Redis 单线程串行执行 Lua 期间不被打断=原子；不用 Lua 并发会在 ZADD 和 ZCARD 间交错。Redis 挂了 fail-open（degraded 放行 + 报警），因为 velocity 只是多条风控之一；若 fail-closed，一次抖动拒掉全系统授权。

**Q6. sliding window log 和 token bucket 怎么选？**
velocity 是风控**信号**（要"过去 N 秒精确几次"喂决策）→ sliding window log（精确、无边界突刺）。API 网关限流、允许突发 → token bucket。

**Q7. cache-aside 更新为什么删缓存不更新缓存？强一致怎么办？**
更新缓存并发下可能写旧计算结果，且写侧要会拼完整 read model → 两条构造路径会发散；失效让"只有读路径构造缓存值"。本项目的 L2 失效不是裸 `DEL`，而是写 `statements.version` 的 tombstone 版本地板，挡住迟到写。强一致不靠 cache：`paid_amount/status/version` 由 `credit_accounts` 行锁 + DB 事务保证，要强一致就直接读 DB，缓存只在 GET 展示路径提速，秒级 stale 已知且可接受。

**Q8. 为什么不用 Redisson？什么时候会用？**
Redisson/Bucket4j 底层也是 Lua；我的需求（滑动窗口计数、缓存重建锁）语义特殊、只有几行、要掌控 fail-open，引入重依赖不划算。真要用 Redisson 是需要 `RLock` 这种自己写易出 bug 的复杂分布式锁时。

**Q9. 你这套 Lua 在 Redis Cluster 下有什么坑？**
一个脚本里所有 KEYS 必须同 hash slot，否则 CROSSSLOT。我的脚本都是单 key，天然同 slot；要在一个脚本里操作多 key，用 hash tag `{...}` 固定或改多次调用。

**Q10. 这条授权热路径还有别的可优化点吗？**
真正瓶颈是 `credit_accounts` 行锁（同账户串行）。可讨论：账户分桶/分片降低单点争用；把只读参考数据（FX 汇率、BIN、商户类目）做成全 fleet 共享的 read-through 缓存（命中率高，一个 key 服务所有请求）；读写分离把只读查询打到副本。注意：强一致的额度写不能缓存。

---

## 第十部分：一句话总结 + 剩余 gap

- **判断**：先想命中率和"卸掉的真实成本"，再决定缓不缓；钱永远走 DB 强一致。
- **statement 两级缓存**：Caffeine L1 + Redis L2 + cache-aside + 版本化 CAS 墓碑 + 跨 pod 重建锁 + （可选）Pub/Sub 广播 + 全程 fail-open，深度远超"cache-aside + TTL"的标准答案。
- **限流**：Redis ZSET 滑动窗口 + Lua 原子 + fail-open。
- **唯一还值得补的（另一个维度，不是再加功能）**：给 L2 的 Lua CAS / 墓碑加 **Redis Testcontainers 真环境并发测试**（当前单测 mock `StringRedisTemplate`，验证不了脚本在真 Redis 上的 CAS/并发）。这既补正确性验证缺口，又是"我用 Testcontainers 验证并发脚本"的加分谈资。

> **别再往缓存里加功能了**（outbox-driven 失效 / negative cache / 更多锁都是镀金，面试 ROI 低）。"知道何时停"本身就是高级信号。
