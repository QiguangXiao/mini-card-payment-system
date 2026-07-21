# Statement Read Cache 设计说明

> **归档对齐说明（2026-07）**：正文已对齐当前 `statement-read-model-v3`、
> `statements.version` + Lua CAS + tombstone、默认开启的跨 pod rebuild lock，以及默认关闭的
> Redis Pub/Sub L1 广播。历史通用 `SnapshotCache` 和 Card cache 只保留在明确标注的历史章节。

这份文档解释当前 statement GET cache：Caffeine L1 + Redis L2、cache-aside、TTL jitter、
JVM/跨 pod single-flight、versioned CAS、tombstone 和 after-commit eviction。它也保留
Card snapshot cache 的删除取舍，方便比较“该缓存”和“不该缓存”的边界。

## 1. 当前缓存范围

当前代码只恢复一个业务 cache：

| cache name | key | value | 用途 | 风险等级 |
| --- | --- | --- | --- | --- |
| `statement-read-model-v3` | statement id | `<statements.version>\|<StatementReadModel JSON>` | `GET /api/statements/{id}` 查询响应快照 | 中低 |

Card snapshot cache 仍然删除：

| cache name | key | value | 用途 | 风险等级 |
| --- | --- | --- | --- | --- |
| `card-snapshot-v1` | card id | `CardSnapshot` | authorization/posting/expiry 读取 card 状态和 account 归属 | 已删除 |

当前 statement cache 没有恢复旧版通用 `SnapshotCacheFactory`/`TwoLevelSnapshotCache` 框架，
而是把最核心逻辑放在 `StatementReadService` 里，方便学习：

```text
StatementController.get
-> StatementReadService.get
   -> Caffeine L1
   -> Redis L2 `<version>|<payload>` envelope
   -> StatementGenerationService.get
   -> MySQL
```

配置入口：

```text
src/main/resources/application.yml
statement.read-cache.*
```

关键配置：

```yaml
statement:
  read-cache:
    local-ttl: 30s
    local-maximum-size: 1000
    remote-ttl: 5m
    remote-ttl-jitter: 30s
    tombstone-ttl: 10s
    rebuild-lock-enabled: true
    rebuild-lock-ttl: 2s
    rebuild-lock-wait-attempts: 5
    rebuild-lock-wait-interval: 20ms
    broadcast:
      enabled: false
```

## 2. 为什么只恢复 statement GET cache

恢复它的目的主要是学习两级 cache 的基本用法：

- Caffeine L1：同 JVM 内低延迟读取，并用 `Cache.get(key, loader)` 做 per-key single-flight。
- Redis L2：跨实例共享 statement read model。
- cache-aside：L1/L2 都 miss 时回源 MySQL，成功后用 versioned CAS 写回 Redis 和 L1。
- after-commit eviction：还款更新 statement 后，事务提交成功再写 version tombstone，而不是 DELETE。
- TTL jitter：Redis TTL 在基础 TTL 上加随机偏移，避免同一批 key 同时过期造成 cache avalanche。
- Redis rebuild lock：L2 miss 后用 `SET NX PX` 做跨 pod best-effort single-flight，默认开启。
- Redis Pub/Sub：可选广播让其他 pod 清 L1，默认关闭，漏消息由 30s L1 TTL 兜底。

不恢复 Card snapshot cache 的原因：

- Card 状态参与 authorization 决策，stale `ACTIVE` 会影响批准/拒绝。
- 为了安全，Card cache TTL 必须很短；短 TTL 又削弱命中率。
- 授权热路径真正瓶颈是 `credit_accounts` 的 `SELECT ... FOR UPDATE`，缓存 card 主键读取不能解决同账户写串行化。
- Risk velocity 计数比 Card snapshot 更适合 Redis：每笔授权都会用到，不依赖 cache hit rate。

## 3. 为什么使用 read model 而不是 aggregate

`GET /api/statements/{id}` 缓存的是 `StatementReadModel`，不是 `Statement` aggregate。

- `Statement` aggregate 有还款状态转换和 invariant，是写模型。
- `StatementReadModel` 只有 response 需要的字段，没有 business behavior。
- Redis JSON 存 read model，避免把缓存当成 domain object store。

这条边界很重要：cache 只是 query acceleration，MySQL 才是 source of truth。

## 4. 历史命名复盘：为什么以前叫 SnapshotCache

旧版通用层曾经叫过 `SnapshotCache`。上一版叫 `ReadModelCache`，对 statement 很准确，因为
`GET /api/statements/{id}` 缓存的是 `StatementReadModel`。

加入 Card 后，这个名字开始偏窄：

- `StatementReadModel` 是 API 查询 read model。
- `CardSnapshot` 是 reference data snapshot，会参与 authorization/posting 决策。
- 两者都不是写模型，也都可以从 MySQL 重建，但它们不完全等同于“read model”。

所以旧版通用 infra 改成：

- `SnapshotCache`：表达“缓存可重建快照”，不是任意对象。
- `SnapshotCacheFactory`：统一创建 Caffeine + Redis two-level cache。
- `TwoLevelSnapshotCache`：具体实现 L1/L2/read-through/fallback/evict。
- `TransactionAwareSnapshotCacheEvictor`：统一处理 transaction commit 后再 evict。
- `snapshot-cache`：配置前缀，下面按 cache name 拆配置。

当时保留业务侧具体名字：

- Statement 侧叫 `StatementReadModelService`，因为它确实服务 GET read model。
- Card 侧叫 `CardSnapshot` 和 `CachedCardRepository`，因为它是 repository port 的缓存 decorator。

这样命名的好处是：通用层不被 statement 绑死，业务层又不会变成模糊的 `ObjectCache`。

### 4.1 这次命名复盘结论

这里不要理解成“把 ReadModel 又改回 Snapshot”。更准确的边界是：

| 层级 | 推荐理解 | 历史名字 | 为什么 |
| --- | --- | --- | --- |
| 通用缓存能力 | 只能缓存可重建快照 | `SnapshotCache<K, V>` | 强调 cache 不是 source of truth，也不是任意写模型缓存 |
| Statement 查询对象 | API response read model | `StatementReadModel` | 它没有业务行为，只服务 `GET /api/statements/{id}` |
| Card 参考数据 | reference data snapshot | `CardSnapshot` | 它不是公开查询模型，而是授权/posting/expiry 决策前的卡状态快照 |
| 具体 L1/L2 实现 | Caffeine + Redis read-through | `TwoLevelSnapshotCache` | 这个名字能表达两级结构；如果以后重命名，`CaffeineRedisSnapshotCache` 会更直说技术栈 |
| 事务后失效 helper | after-commit evict | `TransactionAwareSnapshotCacheEvictor` | 这个名字准确但偏泛；如果以后重命名，`AfterCommitSnapshotCacheEvictor` 更直观 |

所以当时的文档口径是：通用接口叫 `SnapshotCache`，但具体业务对象不要强行统一成 snapshot。
Statement 是 read model，Card 是 snapshot。

## 5. 通用读取和失效策略

当前 statement cache 的读取顺序：

```text
1. 查 Caffeine L1
2. L1 miss 后查 Redis L2
3. L2 hit 后回填 L1
4. L1/L2 都 miss 时调用 loader，从 MySQL/source of truth 重建 snapshot
5. 成功加载后携带 `statements.version` 走 Lua CAS 写 Redis L2；Caffeine 的 `Cache.get` 保存返回值
```

失败策略：

- Redis read 失败：记录 warning，回源 MySQL。
- Redis write 失败：保留 L1，本次请求仍然返回成功。
- Redis JSON 或 envelope 损坏：删除坏 value，下次从 MySQL 重建。
- statement 不存在时 `StatementGenerationService.get` 抛 not-found，不构造/写入 cache entry；当前没有
  404 negative cache。

当前失效策略：

- 如果没有事务同步，立即 evict。
- 如果当前线程有 Spring transaction synchronization，注册 after-commit evict。
- `RepaymentService` 不直接操作 Redis key，只调用 `StatementReadService.evictAfterCommit(statement)`，把
  已在 transaction 内推进后的 `statement.version()` 传给失效逻辑。

并发策略：

- `StatementReadService` 通过 Caffeine `Cache.get(key, loader)` 做 per-key single-flight。
- 同一个 key 在 L1/L2 都 miss 时，同 JVM 内只有一个线程真正回源。
- 跨 pod 的 cache stampede 由 Redis rebuild lock 缓解：winner 回源并回填，loser 最多等待
  `5 × 20ms` 重读 L2，超时或 Redis 故障后 fail-open 自己回源。
- lock 只保护幂等的“DB read + CAS cache fill”，是性能优化而不是资金正确性闸；释放时用 Lua
  compare-token-then-delete，避免 stale owner 删除新 owner 的锁。

如果没有这些额外处理：

| 省掉的处理 | 可能问题 |
| --- | --- |
| Redis fallback 回源 DB | Redis 短暂故障会直接让 GET API 失败，cache 反而变成可用性风险 |
| Redis JSON 损坏后删除坏值 | 一个旧格式或坏 JSON 会让同一个 key 后续一直解析失败 |
| per-key single-flight | 热点 statement/card 在 L1/L2 同时 miss 时，多个线程一起打 MySQL，形成 cache stampede |
| TTL jitter | 一批 key 同秒过期，下一波请求同时回源，制造 cache avalanche |
| after-commit evict | 事务提交前失效时，另一个 GET 可能读旧 DB 值并把 stale snapshot 写回 Redis；当前再用 version + tombstone 关掉迟到写（详见 6.1） |
| Redis rebuild lock | 多 pod 同时 L2 miss 时会各自回源，热点 key 可能击穿 MySQL |

## 6. Statement read model cache

请求链路：

```text
GET /api/statements/{id}
-> StatementController.get(id)
-> StatementReadService.get(id)
-> Caffeine L1
-> Redis L2
-> StatementGenerationService.get(id)
-> StatementRepository.findById(id)
```

配置：

```yaml
statement:
  read-cache:
    local-ttl: 30s
    local-maximum-size: 1000
    remote-ttl: 5m
    remote-ttl-jitter: 30s
    tombstone-ttl: 10s
    rebuild-lock-enabled: true
    rebuild-lock-ttl: 2s
    rebuild-lock-wait-attempts: 5
    rebuild-lock-wait-interval: 20ms
    broadcast:
      enabled: false
```

为什么可以缓存：

- `StatementReadModel` 没有 domain behavior，只是 GET 响应需要的字段。
- `statement_lines`、`total_amount`、`minimum_payment_amount` 是账单生成时固定的审计快照。
- 这个 cache miss 后可以从 MySQL 完整重建。

为什么仍然需要 evict：

- `paid_amount/status` 会被 repayment 更新。
- 如果只靠 TTL，用户还款成功后可能短时间看到旧状态。
- `RepaymentService` 更新 statement 后调用：

```text
StatementReadService.evictAfterCommit(statement)
```

为什么是 after commit：

- 如果事务提交前 evict，另一个 GET 可能读到旧 DB 值并重新写入 Redis，旧值反而被“固化”。
- after-commit evict 把失效时机排到 commit 之后，避开上面这条最常见的 stale reload。

### 6.1 after-commit + version/CAS/tombstone 的边界（重要）

`evictAfterCommit` 只决定“写侧什么时候失效 cache”；当前实现再用 `statements.version`、Lua CAS 和墓碑
关闭 cache-aside 的 late-write race。时间线如下：

```text
时间线（pod 内或跨 pod 都可能发生）：
  t0  GET 线程读 MySQL，拿到旧快照（MySQL RR 下是事务开始时的一致性快照），但后续写 Redis 很慢
  t1  还款事务 commit
  t2  afterCommit 触发，L2 写入新 version 的墓碑（版本地板）
  t3  GET 线程“迟到”地回填 t0 的旧值
      → Lua 发现 incoming.version < stored.version，拒绝旧值
  t4  新鲜 GET 读到墓碑（按 miss 处理），回源 DB，并以同/新 version 的真值替换墓碑
```

为什么只加 version 还不够：如果 evict 仍 DELETE，迟到旧值面对空 key 没有版本可比较，仍会成功写回；墓碑
保留了版本地板，才同时关闭“覆盖新值”和“落入空 key”两条 race。

这仍然不是端到端强一致，剩余窗口是：

| stale 来源 | 最长窗口 | 触发条件 |
| --- | --- | --- |
| 跨 pod L1 未失效 | `localTtl`（30s） | 广播默认关闭，或开启后某 pod 漏收 Pub/Sub |
| 墓碑写失败 | `remoteTtl`（5m） | Redis 抖动，仅本 pod L1 被清；旧 L2 留到 TTL |
| commit 后、afterCommit 前宕机 | `remoteTtl` / `localTtl` | DB 已提交，但墓碑和广播尚未执行 |
| 墓碑过早过期 | 取决于慢 GET | 从读 DB 到回填的时滞超过 10s tombstone TTL，版本地板先消失 |

当前工程手段：短 L1 TTL + L2 TTL/jitter + version/CAS/tombstone + 默认开启的 rebuild lock；需要缩短
跨 pod L1 窗口时开启 Redis Pub/Sub。若业务要求“commit 成功后失效意图绝不丢”，升级路径是把
`statement.invalidate(id, version)` 写入同事务 Outbox，再由幂等 relay 至少一次写墓碑和广播；代价是多一次
DB 写与约 1s poll 延迟。delayed double-delete 只能按经验等待，不能证明覆盖所有慢 reader，当前不采用。

底线口径：**cache 是性能层不是正确性来源**。`paid_amount/status` 的强一致由 `credit_accounts` row lock 和 DB 事务保证；
cache 只在 GET 展示路径上提速，秒级 stale 是已知且可接受的 tradeoff。

## 7. Card snapshot cache（已删除设计）

使用链路：

```text
AuthorizationService.decideAndReserve(...)
-> CardRepository.findById(cardId)
-> CachedCardRepository
-> SnapshotCache<String, CardSnapshot>
-> MyBatisCardRepository.findById(cardId)
-> cards
```

`PostingService` 和 `AuthorizationExpiryService` 也通过同一个 `CardRepository` port 读取 card，
所以会自动走 `CachedCardRepository`。

配置：

```yaml
snapshot-cache:
  caches:
    card-snapshot-v1:
      local-ttl: 10s
      maximum-size: 5000
      remote-ttl: 1m
      remote-ttl-jitter: 10s
```

为什么只缓存 snapshot：

- `CardSnapshot` 只保存 `id`、`creditAccountId`、`status`。
- 不缓存 `CreditAccount`，不缓存 available credit。
- `CachedCardRepository` 每次从 snapshot 重建新的 `Card` domain object，避免把 Redis JSON 当成 domain aggregate 存储。

为什么不做 negative cache：

- `card-not-found` 可能是测试数据或发卡数据刚创建前的瞬时状态。
- 如果缓存 404，新卡会在 TTL 内继续不可见。
- 当时让 missing card 每次回源，安全性优先。

风险边界：

- Card snapshot 会影响 authorization 决策，风险高于纯展示型 statement read model。
- stale `ACTIVE` 可能让刚 blocked 的卡在短 TTL 内继续通过 card lifecycle check。
- 当时项目没有 card block/unblock API；如果恢复这类 cache，card 状态变更提交后必须调用
  `CardSnapshotCacheInvalidator.evictAfterCommit(cardId)`。
- 如果业务要求 block 后全局立即生效，可以选择：
  - block/unblock 写路径 after-commit evict Redis。
  - 增加 Redis Pub/Sub 或 Kafka cache invalidation event 清其他 pod 的 L1。
  - 对高风险卡状态检查直接 bypass cache。

## 8. 为什么不能缓存额度

不要缓存：

- `availableCredit`
- `reservedAmount`
- `postedBalance`
- 幂等 claim 结果
- repayment posting 结果

原因：

- 额度是高并发写模型。
- 正确性依赖 `credit_accounts` row lock。
- 缓存额度会绕开 `SELECT ... FOR UPDATE`，可能导致超额授权。
- 在金融后台里，cache 可以减轻读压力，但不能替代 transaction boundary。

正确做法仍然是：

```text
creditAccountRepository.findByIdForUpdate(accountId)
-> account.reserve(...)
-> creditAccountRepository.update(account)
```

## 9. 命名规则

cache name 建议格式：

```text
{business-snapshot-name}-v{version}
```

当前/历史例子：

- `statement-read-model-v3`
- `card-snapshot-v1`（已删除的 Card snapshot cache）

为什么带版本：

- Redis value 是 JSON contract。
- 如果字段语义不兼容，改 cache name 比清全量 Redis 更可控。
- 旧 key 会自然过期。

类命名建议：

- 当前 statement 查询缓存：`StatementReadService`、`StatementReadModel`、`StatementReadCacheProperties`。
- 如果以后恢复通用层，再考虑 `SnapshotCache`、`SnapshotCacheFactory`、`TwoLevelSnapshotCache`。
- 如果以后恢复 reference data cache，再考虑 `CardSnapshot`、`CachedCardRepository`。
- 写路径失效当前直接调用 `StatementReadService.evictAfterCommit(statement)`；不再额外拆 invalidator helper。

命名取舍：

- `TwoLevelSnapshotCache` 当时是 package-private 实现类；如果以后恢复代码级设计，`CaffeineRedisSnapshotCache` 会更直说技术栈。
- 旧版 `StatementSnapshotCacheInvalidator` 表达“statement 相关缓存失效”，但它实际失效的是 `StatementReadModel`。如果以后恢复独立 invalidator，`StatementReadModelCacheInvalidator` 会更精确。
- `CachedCardRepository` 表达 repository decorator；如果想让动词更自然，`CachingCardRepository` 也可以。

## 10. interview 解释口径

可以这样回答：

> 当前项目有一个很窄的 statement GET read model cache：Caffeine 做同 JVM L1，Redis 做跨实例 L2，
> L1/L2 miss 时回 MySQL 重建；同 JVM 用 Caffeine single-flight，跨 pod 用默认开启的 Redis rebuild lock。
> repayment commit 后写 `statements.version` 墓碑，读回填走 Lua CAS，挡住迟到旧值。跨 pod L1 广播已实现、
> 默认关闭，漏消息由 30s L1 TTL 兜底。
> Card snapshot cache 没有恢复，因为 card 状态会影响 authorization 判断，安全 TTL 太短。
> 无论是否使用 cache，MySQL 仍然负责状态和一致性，额度、幂等 claim 和还款入账不能放进 cache。

常见追问：

- 为什么不用 `@Cacheable`？
  - 因为想把“哪些对象允许缓存”的边界写得更显式，避免写路径被随手加缓存。
- 为什么 L1 TTL 比 L2 短？
  - 多 pod 下本地 Caffeine 不会天然互相失效，短 L1 TTL 可以降低跨实例 stale risk。
- 为什么 Redis TTL 要 jitter？
  - 避免大量 key 同时过期，引发 cache avalanche。
- 为什么不缓存 404？
  - statement id 是系统生成 UUID，当前没有可猜测的海量不存在 key 攻击面；需要防这类流量时再单独设计
    短 TTL negative cache，而不是无条件增加一致性状态。
- after-commit evict 是不是就保证还款后读不到旧账单了？（高频追问，必须答准）
  - 不是。after-commit 只保证失效排在 commit 之后；version/CAS/tombstone 进一步挡住 commit 前读、commit 后迟到回填的旧值。
  - 仍剩 commit 后回调前宕机、Redis 墓碑写失败、默认关闭广播时其他 pod 的 L1，以及 tombstone TTL 小于极慢 reader 时滞等窗口。
  - 所以这仍是可自愈的 performance cache，不是资金正确性来源；强一致路径读 DB，需要 durable invalidation 时用 transaction Outbox 升级。
  - 详见 6.1 节。
