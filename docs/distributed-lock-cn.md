# Distributed lock 专题：是什么、生产怎么做、为什么本项目不做

> 关键词：分布式锁, distributed lock, Redis SETNX, Redlock, ZooKeeper lease,
> DB row lock, fencing token, 分散ロック(ぶんさんロック), リース。

本文原为 JD 对照手册的第 33 章，独立成篇：它是与具体项目代码解耦的通用生产知识，
同时解释了为什么本项目的 money path 坚持 DB row lock / DB lease，
以及项目里唯一一把真正的 Redis 锁——statement cache 重建 single-flight——
为什么可以是 best-effort（见第 7 节）。面试问答里所有 distributed lock
相关追问的底层弹药都在这里。

它专门回答一个容易被追问的问题：

```text
你用了 Redis cache，也做了高并发授权，为什么没有用 distributed lock？
```

> [!IMPORTANT]
> 先给结论：

```text
本项目的 money path 没有使用 distributed lock，不是忘了，而是需要保护的共享事实都在 MySQL。
额度、幂等 winner、Outbox claim、DelayJob claim 都已经由 MySQL unique constraint、
SELECT ... FOR UPDATE、FOR UPDATE SKIP LOCKED、PROCESSING lease 和 transaction boundary 保护。
在这个系统里再引入 Redis/ZooKeeper/etcd distributed lock，反而会增加第二套一致性边界。
唯一的 Redis 锁是 statement cache 重建 single-flight——它是 best-effort 性能优化，
丢锁的最坏后果只是多一次 DB 回源（第 7 节），恰好反证了为什么 money path 不能用这种锁。
```

## 1. Distributed lock 是什么

Distributed lock 是跨多个进程、多个机器、多个 pod 协调“同一时间只有一个执行者可以处理某个资源”的机制。

普通 Java lock：

```text
synchronized
ReentrantLock
```

只能保护同一个 JVM 进程内的线程。

Distributed lock 要保护的是：

```text
app-pod-1
app-pod-2
app-pod-3
```

这些不同进程对同一个业务资源的并发访问。

典型资源 key：

```text
lock:account:{accountId}
lock:authorization:{authorizationId}
lock:job:{jobId}
lock:statement-cycle:{accountId}:{period}
```

它想表达：

```text
谁拿到 lock，谁暂时拥有处理这个资源的权利。
其他实例要等待、失败返回、重试，或者跳过。
```

Distributed lock 常见使用场景：

- 多 pod 中只允许一个 scheduler 执行某个全局任务。
- 防止多个 worker 同时处理同一个外部文件。
- 防止多个实例同时刷新同一个超热点 cache key。
- 对没有数据库 row 可以锁的外部资源做互斥。
- 做 leader election。
- 做一次性 migration 或 maintenance job 的执行保护。

注意：distributed lock 只是一种 coordination mechanism。

它本身不等于：

- transaction。
- idempotency。
- exactly-once。
- 数据库约束。
- 状态机。
- 审计记录。

这是很多人容易混淆的地方。

## 2. 生产里常见 distributed lock 做法

#### 方式 1：Redis `SET key value NX PX ttl`

最常见的 Redis lock 是：

```text
SET lock:resource unique-owner-token NX PX 30000
```

含义：

- `NX`：key 不存在时才写入，表示抢锁。
- `PX 30000`：设置 30 秒 TTL，避免持锁进程宕机后锁永远不释放。
- `unique-owner-token`：锁 owner 身份，释放锁时要校验。

释放锁不能简单 `DEL key`。

必须用 Lua script 做 compare-and-delete：

```text
if redis.call("GET", key) == ownerToken then
  return redis.call("DEL", key)
else
  return 0
end
```

原因：

- A 拿锁后卡住。
- TTL 到期，B 拿到新锁。
- A 恢复后如果直接 `DEL key`，会误删 B 的锁。

生产要点：

- value 必须是 unique token。
- unlock 必须校验 token。
- TTL 必须有限。
- 业务执行时间必须小于 TTL，或者要有续期机制。
- Redis timeout 要短。
- lock acquire 失败要有明确策略：等待、快速失败、重试、跳过。
- 被锁保护的业务操作仍要 idempotent。

典型问题：

- 业务执行超过 TTL，锁自动过期，另一个实例进来并发执行。
- 持锁者 GC pause 或网络卡顿，恢复后以为自己仍然持锁。
- Redis master failover 时可能丢锁状态。
- 锁释放失败，直到 TTL 才恢复。
- 缺少 fencing token 时，旧 owner 可能覆盖新 owner 结果。

#### 方式 2：Redis Redlock

Redlock 是 Redis 多节点锁算法，尝试在多个独立 Redis 节点上拿锁。

它的目标是降低单 Redis 节点故障导致锁错误的概率。

但是 interview 里不要把 Redlock 说成万能强一致锁。

更稳妥的表达：

```text
Redlock 可以提高 Redis lock 的可用性和容错性，但它仍然依赖时间、TTL、网络假设。
如果业务是金融账务强一致更新，我不会只靠 Redlock 决定余额正确性。
```

如果生产系统使用 Redlock，仍然要考虑：

- clock drift。
- network partition。
- Redis failover。
- lock TTL 与业务执行时间。
- owner token。
- fencing token。
- 幂等和补偿。

#### 方式 3：ZooKeeper / etcd / Consul lease

ZooKeeper、etcd、Consul 更常用于 coordination。

典型机制：

- ephemeral node。
- session。
- lease。
- compare-and-swap。
- watch。
- revision。

它们比普通 Redis lock 更适合：

- leader election。
- service coordination。
- 配置一致性。
- 长生命周期 ownership。

但它们也不是免费午餐：

- 运维复杂度更高。
- 延迟通常高于 Redis。
- client session 语义要理解清楚。
- 仍要处理业务幂等。
- 仍要处理旧 owner 恢复后的写入问题。

生产里如果用 etcd，比较好的做法是配合 `fencing token`。

例如：

```text
worker A gets lock with fencing token 10
worker B later gets lock with fencing token 11
downstream DB only accepts writes with token >= current token
```

这样即使 A 之后恢复，也不能用旧 token 覆盖 B 的结果。

#### 方式 4：数据库锁

数据库本身也可以做 coordination。

常见形式：

- row lock：`SELECT ... FOR UPDATE`
- unique constraint：insert-first claim
- `FOR UPDATE SKIP LOCKED`：多 worker batch claim
- advisory lock：例如 MySQL `GET_LOCK` 或 PostgreSQL advisory lock
- lease column：`status`, `locked_until`, `owner`

本项目主要使用：

```text
unique constraint
SELECT ... FOR UPDATE
FOR UPDATE SKIP LOCKED
PROCESSING lease
```

这不是传统意义上的 Redis distributed lock，但它解决的是同一类“多实例协调”问题，而且和业务数据在同一个 MySQL 事务里。

这就是本项目选择它的核心原因。

## 3. 生产 distributed lock 必须回答的 10 个问题

如果 interview 官问你“生产里怎么做 distributed lock”，不要只说 `SETNX`。

你应该主动回答这些问题：

| 问题 | 为什么重要 |
| --- | --- |
| lock key 怎么设计？ | key 太粗会降低并发，key 太细保护不了共享资源 |
| lock value 是什么？ | 需要 unique owner token 防误删 |
| TTL 多长？ | 太短会并发执行，太长会故障恢复慢 |
| 业务超时怎么办？ | 执行时间超过 TTL 是经典事故来源 |
| 要不要续期？ | 续期要处理 owner 存活、网络、GC pause |
| unlock 是否校验 owner？ | 不校验会删掉别人的锁 |
| acquire 失败怎么办？ | 等待、快速失败、重试、跳过是不同业务语义 |
| 旧 owner 恢复怎么办？ | 需要 fencing token 或下游状态校验 |
| 锁服务不可用怎么办？ | 是 fail closed，还是降级，还是走 DB 兜底 |
| 被锁保护的操作是否幂等？ | distributed lock 不能替代 idempotency |

一个比较完整的生产回答：

> 如果我必须使用 Redis distributed lock，我会用 `SET key value NX PX ttl` 获取锁，
> value 是唯一 owner token，释放时用 Lua compare-and-delete。
> TTL 要小于业务可接受的故障恢复时间，并且业务执行要有 timeout。
> 对可能超过 TTL 的任务，要么做安全续期，要么把任务拆短。
> 对会写入下游持久化状态的场景，最好加 fencing token 或状态版本校验。
> 同时，锁保护的业务操作仍然要 idempotent，因为锁可能因为 TTL、网络、failover、GC pause 出现边界问题。

## 4. Distributed lock 的典型事故场景

#### 事故 1：TTL 过期导致双执行

```text
T1: worker A gets lock, TTL = 30s
T2: worker A pauses for 40s because GC/network/slow DB
T3: lock expires
T4: worker B gets lock and starts processing
T5: worker A resumes and continues writing
```

如果没有 fencing token 或状态校验，A 和 B 可能同时写业务状态。

这就是为什么 lock 不等于 transaction。

#### 事故 2：误删别人的锁

```text
A gets lock value=A-token
A pauses
TTL expires
B gets lock value=B-token
A resumes
A DEL lock key
```

如果 A 不校验 value，B 的锁被删掉。

所以 unlock 必须 compare owner token。

#### 事故 3：锁拿到了，DB transaction 失败

```text
get Redis lock success
write DB row 1 success
write DB row 2 fails
rollback DB
release lock
```

如果业务没有审计和幂等，外部观察可能很难知道这次到底执行到哪里。

所以金融系统仍然需要 DB transaction 和状态机。

#### 事故 4：DB transaction 还没提交，锁已经释放

```text
get Redis lock
start DB transaction
update account
release Redis lock too early
another worker gets lock
first transaction not committed yet
```

这会让第二个 worker 基于不稳定状态做决策。

所以锁生命周期必须覆盖真实 critical section。

如果 critical section 本质就是 DB row update，用 DB row lock 更自然。

#### 事故 5：没有 fencing token，旧 owner 覆盖新 owner

```text
A gets lock with old ownership
A pauses
B gets new lock and writes correct result
A resumes and writes stale result
```

如果 downstream 不检查 owner version，旧写入仍然可能成功。

这就是生产 distributed lock 里经常强调 fencing token 的原因。

## 5. 为什么本项目不使用 distributed lock

本项目的核心共享事实都在 MySQL。

| 场景 | 当前保护机制 | 为什么不需要 distributed lock |
| --- | --- | --- |
| Authorization 幂等 winner | `authorizations.idempotency_key` unique + `findByIdempotencyKeyForUpdate` | winner 选择和业务记录在同一个 DB |
| Credit account 额度 | `credit_accounts` row 的 `SELECT ... FOR UPDATE` | 余额 source of truth 就是这行 DB row |
| Presentment 幂等 | `card_transactions.network_transaction_id` unique | 外部交易身份由 DB 唯一约束保护 |
| Repayment 幂等 | `repayments.idempotency_key` unique + row lock | 防重复还款必须和 statement/account 更新同事务 |
| Statement cycle | `credit_account_id + period_start + period_end` unique + row lock | 防重复账单和账单快照同事务 |
| Outbox publish claim | `FOR UPDATE SKIP LOCKED` + `PROCESSING` lease | 多 worker claim 和 row 状态在一个 DB 表 |
| DelayJob claim | `FOR UPDATE SKIP LOCKED` + `PROCESSING` lease | future business action 的 ownership 由 DB lease 表达 |
| Consumer duplicate | `consumer_inbox` unique | consumer side effect 幂等在 DB 中记录 |
| Cache stampede | Caffeine per-key single-flight + 跨 pod Redis rebuild lock（best-effort，见第 7 节） | 只是性能优化，不是金融正确性；丢锁最坏后果是多一次 DB 回源 |

最关键的是这句话：

```text
如果被保护的数据在 MySQL，优先用 MySQL 的 transaction、row lock、unique constraint 和 lease。
如果另加 Redis lock，就变成 Redis 决定执行权、MySQL 保存业务事实，两套系统之间没有同一个 ACID transaction。
```

这会引入新的 failure mode：

- Redis lock 成功，但 MySQL transaction 失败。
- MySQL transaction 成功，但 Redis unlock 失败。
- Redis TTL 过期，但 MySQL transaction 还没结束。
- Redis failover 导致锁状态不确定。
- app GC pause 导致旧 owner 恢复后继续写。
- 没有 fencing token，旧 owner 可能覆盖新 owner。
- 业务以为有锁就不做 idempotency，结果重复请求仍然出错。

所以，本项目的核心链路不使用 distributed lock 的原因不是“项目小”，而是：

```text
当前并发冲突的 source of truth 在数据库，
数据库锁和约束已经是更准确、更可审计、更容易和业务状态同事务提交的工具。
```

## 6. 为什么 DB row lock 在这里比 Redis lock 更合适

以 authorization 为例。

需要同时保证：

- 只能有一个 idempotency winner。
- 同一个 account 的额度不能被并发超用。
- authorization 状态要可审计。
- credit account reserved amount 要更新。
- expiry DelayJob 要写入。
- Outbox event 要写入。
- 失败时全部 rollback。

如果用 DB：

```text
BEGIN
insert authorization with unique idempotency_key
select authorization for update
select credit_account for update
update credit_account
update authorization
insert delay_job
insert outbox_event
COMMIT
```

这些都在同一个 transaction boundary。

如果用 Redis lock：

```text
SET lock:account:{accountId} token NX PX 30000
BEGIN MySQL transaction
update several MySQL tables
COMMIT
DEL lock
```

现在你必须额外处理：

- Redis lock TTL 和 MySQL transaction 时间是否匹配。
- Redis lock 成功但 DB 失败怎么恢复。
- DB 成功但 unlock 失败怎么办。
- TTL 到期后第二个请求进来怎么办。
- 旧 owner 恢复后是否还能写。
- Redis 和 MySQL 之间无法 atomic commit。

所以在这个业务里，Redis lock 没有简化问题，而是把一个 DB transaction 问题变成了 Redis + MySQL 分布式一致性问题。

## 7. 本项目哪些地方“看起来像 distributed lock”，但其实是 DB lease

Outbox 和 DelayJob 的 `PROCESSING` 状态很像 distributed lock。

例如 Outbox：

```text
PENDING
-> PROCESSING lease
-> PUBLISHED / PENDING retry / DEAD
```

DelayJob：

```text
PENDING
-> PROCESSING lease
-> DONE / PENDING retry / DEAD
```

这确实是在做跨 worker ownership。

但它不是 Redis lock，而是 database-backed lease。

好处：

- claim row 和 status update 在 MySQL transaction。
- worker finalize 前重新 `FOR UPDATE` 锁 row。
- 可以检查当前 row 是否仍然是自己的 lease。
- recoverer 可以扫描 stuck `PROCESSING`。
- attempts、error、nextAttemptAt 都可审计。
- 多实例 worker 用 `SKIP LOCKED` 不互相等待。

这比“拿一个 Redis lock 然后处理 DB row”更适合本项目，因为 queue state 本身就在 DB。

例外：项目里确实存在一把真正的 Redis 锁——statement read cache 的跨 pod 重建锁
（`StatementReadService.rebuildWithSingleFlight`）。它值得单独讲，因为它示范了
“什么样的场景可以接受 best-effort lock”：

```text
L1/L2 都 miss（热点 key 过期瞬间）
1. SET rebuild-lock:{id} {token} NX PX 2s   抢锁，token 每次随机
2. winner：回源 DB、写回 L2，finally 里只释放 token 匹配的锁（防误删别人的锁）
3. loser：自旋最多 5 次 x 20ms，每次重读 L2；winner 填好后直接复用
4. 任何一步 Redis 失败 / 等待超时：fail-open，自己回源 DB
```

对照第 4 节的四类事故逐条检查：TTL 过期双执行？后果只是两个 pod 各回源一次 DB。
误删别人的锁？token 匹配已防住。锁拿到了但“事务”失败？这里没有事务，回源失败就抛错。
没有 fencing token 旧 owner 覆盖新 owner？写入的是同一份可重建的 read model，
迟到写由 cache 层的版本 CAS 挡住。也就是说：

```text
best-effort lock 可以接受的判定标准，不是“场景不重要”，
而是“锁失效的最坏后果退化为无锁时的原始行为，且正确性另有兜底”。
cache 重建满足这两条；额度、幂等、账务一条都不满足，所以 money path 不用它。
```

## 8. 什么时候本项目未来可能使用 distributed lock

虽然当前不需要，但不是永远不用。

可能合理的场景：

#### 场景 1：全局单例 maintenance job

如果未来有一个不适合拆成 DB row claim 的全局维护任务，例如：

- 每天生成全局运营报表。
- 清理外部临时文件。
- 触发某个第三方系统的全局同步。

可以用 etcd/ZooKeeper/Redis lock 做 leader election 或 execution guard。

但仍要记录执行结果和幂等标记。

#### 场景 2：外部资源没有 DB row 可锁

如果要处理一个外部对象：

```text
s3://network-clearing-file/2026-06-21.csv
```

并且本地 DB 还没有建立 file processing row，那么 distributed lock 可以临时保护“只有一个 worker 下载处理”。

更好的生产做法通常是先落 DB：

```text
clearing_files(file_name unique, status, owner, lease_until)
```

然后继续用 DB lease。

#### 场景 3：账户级限流或防抖

Redis 可以用来做 account/card/merchant 级 rate limit。

例如：

```text
rate:authorization:account:{accountId}
```

这不是 distributed lock，而是流量控制。

它可以保护系统，但不能替代额度 row lock。

## 9. 如果 interview 官坚持问“那生产中到底会不会用 distributed lock”

可以这样回答：

> 会用，但我会先问它保护的资源在哪里。
> 如果资源本身是数据库行，而且业务更新必须和这行数据同事务提交，我优先用 DB row lock、unique constraint 或 DB lease。
> 如果资源是跨进程的外部任务、leader election、cache rebuild 或没有天然 DB row 的操作，才考虑 Redis/ZooKeeper/etcd distributed lock。
> 即使用 distributed lock，也必须有 TTL、owner token、safe unlock、timeout、fencing token 或状态版本校验，并且业务操作仍要 idempotent。

如果对方继续问“为什么不用 Redis lock 保护 authorization accountId”：

> 因为 authorization 不只是保护 accountId 这个 key。
> 它要同时写 authorization、credit account、DelayJob、Outbox。
> 这些状态的 source of truth 都在 MySQL，所以用 MySQL transaction 和 row lock 能把锁和状态放在同一个一致性边界。
> Redis lock 会把 ownership 放到 Redis，把业务状态放到 MySQL，反而制造跨系统一致性问题。

## 10. 一句话总结

> [!IMPORTANT]
> 最适合背下来的版本：

```text
Distributed lock 是跨实例协调执行权的工具，但不是 transaction，也不是 idempotency。
生产使用时要处理 TTL、owner token、safe unlock、fencing token、超时、重试和幂等。
本项目不使用 Redis/ZooKeeper/etcd distributed lock，因为核心共享事实在 MySQL；
用 DB unique constraint、SELECT ... FOR UPDATE、SKIP LOCKED 和 PROCESSING lease，
可以把执行权、业务状态和审计记录放在同一个 transaction/source of truth 里。
```
