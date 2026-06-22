# Snapshot Cache 设计说明

这份文档专门解释当前项目的 cache 设计：Caffeine L1 + Redis L2、命名取舍、
缓存边界、过期和 evict 策略，以及为什么只缓存低风险 snapshot。

## 1. 当前缓存范围

当前有两个 cache：

| cache name | key | value | 用途 | 风险等级 |
| --- | --- | --- | --- | --- |
| `statement-read-model-v1` | statement id | `StatementReadModel` | `GET /api/statements/{id}` 查询响应快照 | 中低 |
| `card-snapshot-v1` | card id | `CardSnapshot` | authorization/posting/expiry 读取 card 状态和 account 归属 | 中 |

都使用同一套通用 infrastructure：

```text
SnapshotCache<K, V>
-> TwoLevelSnapshotCache
   -> Caffeine L1
   -> Redis L2
   -> loader 回源 MySQL

TransactionAwareSnapshotCacheEvictor
-> 写路径需要失效 cache 时，统一注册 after-commit evict
```

配置入口：

```text
src/main/resources/application.yml
snapshot-cache.caches.*
```

## 2. 为什么改名成 SnapshotCache

上一版叫 `ReadModelCache`，对 statement 很准确，因为 `GET /api/statements/{id}` 缓存的是
`StatementReadModel`。

加入 Card 后，这个名字开始偏窄：

- `StatementReadModel` 是 API 查询 read model。
- `CardSnapshot` 是 reference data snapshot，会参与 authorization/posting 决策。
- 两者都不是写模型，也都可以从 MySQL 重建，但它们不完全等同于“read model”。

所以通用 infra 改成：

- `SnapshotCache`：表达“缓存可重建快照”，不是任意对象。
- `SnapshotCacheFactory`：统一创建 Caffeine + Redis two-level cache。
- `TwoLevelSnapshotCache`：具体实现 L1/L2/read-through/fallback/evict。
- `TransactionAwareSnapshotCacheEvictor`：统一处理 transaction commit 后再 evict。
- `snapshot-cache`：配置前缀，下面按 cache name 拆配置。

保留业务侧具体名字：

- Statement 侧仍叫 `StatementReadModelService`，因为它确实服务 GET read model。
- Card 侧叫 `CardSnapshot` 和 `CachedCardRepository`，因为它是 repository port 的缓存 decorator。

这样命名的好处是：通用层不被 statement 绑死，业务层又不会变成模糊的 `ObjectCache`。

### 2.1 这次命名复盘结论

这里不要理解成“把 ReadModel 又改回 Snapshot”。更准确的边界是：

| 层级 | 推荐理解 | 当前名字 | 为什么 |
| --- | --- | --- | --- |
| 通用缓存能力 | 只能缓存可重建快照 | `SnapshotCache<K, V>` | 强调 cache 不是 source of truth，也不是任意写模型缓存 |
| Statement 查询对象 | API response read model | `StatementReadModel` | 它没有业务行为，只服务 `GET /api/statements/{id}` |
| Card 参考数据 | reference data snapshot | `CardSnapshot` | 它不是公开查询模型，而是授权/posting/expiry 决策前的卡状态快照 |
| 具体 L1/L2 实现 | Caffeine + Redis read-through | `TwoLevelSnapshotCache` | 当前名字能表达两级结构；如果以后重命名，`CaffeineRedisSnapshotCache` 会更直说技术栈 |
| 事务后失效 helper | after-commit evict | `TransactionAwareSnapshotCacheEvictor` | 当前名字准确但偏泛；如果以后重命名，`AfterCommitSnapshotCacheEvictor` 更直观 |

所以现阶段文档口径是：通用接口继续叫 `SnapshotCache`，但具体业务对象不要强行统一成
snapshot。Statement 仍然是 read model，Card 仍然是 snapshot。

## 3. 通用读取和失效策略

读取顺序：

```text
1. 查 Caffeine L1
2. L1 miss 后查 Redis L2
3. L2 hit 后回填 L1
4. L1/L2 都 miss 时调用 loader，从 MySQL/source of truth 重建 snapshot
5. 成功加载后写 L1 和 Redis L2
```

失败策略：

- Redis read 失败：记录 warning，回源 MySQL。
- Redis write 失败：保留 L1，本次请求仍然返回成功。
- Redis JSON 损坏：删除坏 value，下次从 MySQL 重建。
- loader 返回 `null`：不写 cache，用于避免 negative cache。

失效策略：

- 如果没有事务同步，立即 evict。
- 如果当前线程有 Spring transaction synchronization，注册 after-commit evict。
- 业务写路径不直接操作 Redis key，而是依赖业务 port，例如 `StatementSnapshotCacheInvalidator`、
  `CardSnapshotCacheInvalidator`。

并发策略：

- `TwoLevelSnapshotCache` 在单 JVM 内做 per-key single-flight。
- 同一个 key 在 L1/L2 都 miss 时，同 JVM 内只有一个线程真正回源。
- 跨 pod 的 cache stampede 主要靠 Redis TTL、TTL jitter 和短期 L1 缓解。
- 如果以后出现超热点 key，可以再引入 Redis mutex，但当前项目先保持简单。

如果没有这些额外处理：

| 省掉的处理 | 可能问题 |
| --- | --- |
| Redis fallback 回源 DB | Redis 短暂故障会直接让 GET API 失败，cache 反而变成可用性风险 |
| Redis JSON 损坏后删除坏值 | 一个旧格式或坏 JSON 会让同一个 key 后续一直解析失败 |
| per-key single-flight | 热点 statement/card 在 L1/L2 同时 miss 时，多个线程一起打 MySQL，形成 cache stampede |
| TTL jitter | 一批 key 同秒过期，下一波请求同时回源，制造 cache avalanche |
| after-commit evict | 事务提交前删 cache 时，另一个 GET 可能读旧 DB 值并把 stale snapshot 写回 Redis |

## 4. Statement read model cache

请求链路：

```text
GET /api/statements/{id}
-> StatementController.get(id)
-> StatementReadModelService.get(id)
-> SnapshotCache<UUID, StatementReadModel>
-> StatementService.get(id)
-> StatementRepository.findById(id)
```

配置：

```yaml
snapshot-cache:
  caches:
    statement-read-model-v1:
      local-ttl: 30s
      maximum-size: 1000
      remote-ttl: 5m
      remote-ttl-jitter: 30s
```

为什么可以缓存：

- `StatementReadModel` 没有 domain behavior，只是 GET 响应需要的字段。
- `statement_items`、`total_amount`、`minimum_payment_amount` 是账单生成时固定的审计快照。
- 这个 cache miss 后可以从 MySQL 完整重建。

为什么仍然需要 evict：

- `paid_amount/status` 会被 repayment 更新。
- 如果只靠 TTL，用户还款成功后可能短时间看到旧状态。
- `RepaymentService` 更新 statement 后调用：

```text
StatementSnapshotCacheInvalidator.evictAfterCommit(statement.id())
```

为什么是 after commit：

- 如果事务提交前 evict，另一个 GET 可能读到旧 DB 值并重新写入 Redis。
- after-commit evict 避开这个 stale reload race。

## 5. Card snapshot cache

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
- 现在让 missing card 每次回源，安全性优先。

风险边界：

- Card snapshot 会影响 authorization 决策，风险高于纯展示型 statement read model。
- stale `ACTIVE` 可能让刚 blocked 的卡在短 TTL 内继续通过 card lifecycle check。
- 当前项目没有 card block/unblock API；未来加入时，必须在 card 状态变更提交后调用
  `CardSnapshotCacheInvalidator.evictAfterCommit(cardId)`。
- 如果业务要求 block 后全局立即生效，可以选择：
  - block/unblock 写路径 after-commit evict Redis。
  - 增加 Redis Pub/Sub 或 Kafka cache invalidation event 清其他 pod 的 L1。
  - 对高风险卡状态检查直接 bypass cache。

## 6. 为什么不能缓存额度

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

## 7. 命名规则

cache name 建议格式：

```text
{business-snapshot-name}-v{version}
```

当前例子：

- `statement-read-model-v1`
- `card-snapshot-v1`

为什么带版本：

- Redis value 是 JSON contract。
- 如果字段语义不兼容，改 cache name 比清全量 Redis 更可控。
- 旧 key 会自然过期。

类命名建议：

- 通用层：`SnapshotCache`、`SnapshotCacheFactory`、`TwoLevelSnapshotCache`。
- 查询型业务：`StatementReadModelService`、`StatementReadModel`。
- reference data：`CardSnapshot`、`CachedCardRepository`。
- 写路径失效：`StatementSnapshotCacheInvalidator`、`CardSnapshotCacheInvalidator`，
  底层都交给 `TransactionAwareSnapshotCacheEvictor`。

命名取舍：

- `TwoLevelSnapshotCache` 是 package-private 实现类，当前不影响业务读代码；如果未来真的要做代码级命名整理，优先考虑改成 `CaffeineRedisSnapshotCache`。
- `StatementSnapshotCacheInvalidator` 当前表达“statement 相关缓存失效”，但它实际失效的是 `StatementReadModel`。如果以后做命名整理，`StatementReadModelCacheInvalidator` 会更精确。
- `CachedCardRepository` 当前表达 repository decorator；如果想让动词更自然，`CachingCardRepository` 也可以，但不是必须改。

## 8. interview 解释口径

可以这样回答：

> 我们没有把 cache 当成 source of truth。MySQL 仍然负责状态和一致性，cache 只保存可从 DB 重建的 snapshot。Statement read model 是展示型 cache，repayment 后 after-commit evict；Card snapshot 是低频变化 reference data，用较短 TTL，未来 card block/unblock 必须提交后失效。额度、幂等 claim 和还款入账不缓存，因为这些必须依赖 transaction boundary 和 row lock。

常见追问：

- 为什么不用 `@Cacheable`？
  - 因为想把“哪些对象允许缓存”的边界写得更显式，避免写路径被随手加缓存。
- 为什么 L1 TTL 比 L2 短？
  - 多 pod 下本地 Caffeine 不会天然互相失效，短 L1 TTL 可以降低跨实例 stale risk。
- 为什么 Redis TTL 要 jitter？
  - 避免大量 key 同时过期，引发 cache avalanche。
- 为什么不缓存 404？
  - 当前更重视新数据可见性和安全边界；需要防攻击流量时可以单独设计短 TTL negative cache。
