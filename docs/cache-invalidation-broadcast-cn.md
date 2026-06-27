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

## 3. L2 late-write 竞态与版本号/CAS（设计，暂未实现）

### 3.1 为什么 Pub/Sub 不够

即使有 after-commit evict + 跨 pod L1 广播，cache-aside 仍有一个 L2 竞态：

```text
t0  GET 线程读 MySQL 拿到旧快照(paid=0)，但写 L2 很慢
t1  还款 commit；evict 删掉 L2 key
t2  GET 线程“迟到”地 writeRedis(paid=0)  ← 旧值重新落到 L2，直到 remote-ttl 才过期
```

广播只压 L1 窗口，对这个"迟到写覆盖 L2"无能为力。

### 3.2 版本号 + Lua CAS + tombstone（推荐方案）

核心：给 L2 value 带一个**单调版本**，写 L2 走 Lua CAS——只有"版本 ≥ 已存版本"才允许覆盖。

- **版本取什么**：本域里 `paidAmount`（最小货币单位）是**单调非减**的（不存在 un-repay），可直接当版本，
  无需加 schema 列。若将来引入退款/冲正使 `paidAmount` 可降，再换成专用 `version` 列。
- **delete 会擦除版本**，所以 evict 不能简单 delete，而要写一个**带版本的 tombstone**
  （`{version: newPaidAmount, tombstone: true}`，带 TTL）作为"版本地板"。还款 after-commit 此时正好
  知道新的 `paidAmount`。
- **读回填用同一个 Lua CAS**：迟到写(version=0) vs 已存 tombstone(version=100) → 0 < 100 被拒；
  新鲜读(version=100) → 覆盖 tombstone 写入真实值。读到 tombstone 视为 miss、回源 DB。

```text
KEYS[1]=L2 key   ARGV[1]=incomingVersion   ARGV[2]=payload   ARGV[3]=ttlMillis
local cur = redis.call('GET', KEYS[1])
if cur then
  local curVer = tonumber(cjson.decode(cur).version)
  if curVer ~= nil and curVer > tonumber(ARGV[1]) then
    return 0   -- 已存更新，拒绝这次迟到写
  end
end
redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
return 1
```

### 3.3 为什么本次先不实现

1. **正确性依赖 tombstone 方案**，比"加个版本号"复杂；做不对反而误导学习。
2. **Lua CAS 是正确性关键脚本，但本仓库没有 Testcontainers**，单测只能 mock `StringRedisTemplate`，
   无法在真 Redis 上验证脚本行为。在"最佳实践 + 用于学习"的要求下，宁可作为带真 Redis 集成测试的
   独立后续项，也不把未经真环境验证的 Lua 脚本仓促合入。

建议后续顺序：先补 Testcontainers（Redis），再落地本节的版本号/CAS，让 Lua 脚本有真环境测试钉住。
