# 分布式缓存、限流、Lua 与缓存设计通用规则

> 关键词：distributed cache, Redis, rate limiting, sliding window, token bucket,
> Lua atomicity, cache-aside, 缓存穿透/击穿/雪崩, fail-open, Redisson,
> 分散キャッシュ(ぶんさんキャッシュ), レート制限(せいげん)。

本文是本项目「缓存技术点」的学习/复习材料,既讲**本项目里实际怎么实现的**,也补**项目之外的通用知识**,最后给一组**硬核 interview 题 + 追问 + 解答**。

相关代码:
- `risk/infrastructure/redis/RedisRiskVelocityCounter.java`(Redis 滑动窗口限流)
- `risk/infrastructure/jdbc/JdbcRiskVelocityCounter.java`(SQL COUNT 对照实现)
- `risk/application/RiskVelocityCounter.java`(port)

---

## 1. 背景:这个项目的缓存是怎么演进的

项目最早有两块读缓存:`statement` 读模型 和 `card` 快照,都用 Caffeine(L1)+ Redis(L2)的两级框架。
现在项目恢复了一个更简洁的 `statement` GET cache: `StatementReadService` 直接使用 Caffeine L1 +
Redis L2 + cache-aside + after-commit eviction。`card` snapshot cache 仍然删除,原因值得讲清楚——
**这本身就是 interview 加分点:会判断"该不该缓存"。**

### 1.1 为什么恢复 statement GET cache

statement GET 缓存的是 `StatementReadModel`,不是写模型 aggregate:

1. **展示型 read model**:`paid_amount/status` 之外的大部分字段来自已经生成的 statement/item 快照,可以从 MySQL 重建。
2. **有真实重复读取场景**:用户打开账单详情、刷新页面、客服查询同一张账单,同一个 statement id 在短 TTL 内可能重复读取。
3. **可以 after-commit eviction**:还款更新 statement 后,`RepaymentService` 注册 after-commit evict,避免事务提交前删除 cache 又被旧 DB 值回填。
4. **核心一致性仍在 DB**:额度、幂等 claim、还款入账和状态推进仍依赖 MySQL transaction / row lock,不靠 cache 判断。

当前配置还加入 `remote-ttl-jitter`,让 Redis key 过期时间错开,降低 cache avalanche 风险。

### 1.2 为什么删掉 card snapshot 缓存

读缓存有没有价值,只看一件事:**同一个 key 在 TTL 窗口内被重复读到的概率(temporal locality)。** card snapshot 在这件事上很差:

1. **单卡请求率低**:一个持卡人一天就几笔授权,分散在全天;短 TTL 内重复刷同一张卡几乎不发生 → 命中率低。
2. **安全 TTL 与命中率是死结**:card status 是授权的 gate(blocked 卡必须立刻拒),所以 TTL 必须短到能及时失效;而"必须短"恰好压死命中率。**对"stale 会出事"的数据,你能安全用的 TTL,正好是命中率最差的 TTL。**
3. **它不是瓶颈**:授权热路径真正的瓶颈是 `credit_accounts` 的 `SELECT ... FOR UPDATE`(同账户串行);读 card 只是一次非锁定主键查询,本来就便宜。缓存一个非瓶颈的廉价读,收益有限。
4. **幂等重试根本不读 card**:`AuthorizationService` 先 claim,`if (!claimed) return`,重试在读 card **之前**就返回了 → 享受不到缓存。

结论:card snapshot 缓存是个**弱缓存**,删掉它、把缓存这个技术点搬到真正合适的地方。

### 1.3 为什么 velocity 计数适合 Redis(而 card 不适合)

velocity 计数和读缓存**不是一回事**:
- 读缓存:缓存"某次读的结果",**依赖命中率**。
- velocity:把**整个计数 workload 从主库搬到 Redis**,**不依赖命中率**。

原本每笔授权都要 `SELECT COUNT(*) FROM authorizations WHERE card_id=? AND created_at>=?`,**每次都打主库**——而主库正是被 `FOR UPDATE` 占着的瓶颈。改成 Redis 后,**每一笔授权都少打一次主库**,对每个请求无条件成立。这才是支付系统里 Redis 最对口的用法:**high-traffic + 分布式 + 原子计数**。

> 一句话:**不要因为"数据读得多"就缓存,要因为"同一份数据被很多请求复用、且能卸掉真实成本"才缓存。**

---

## 2. 本项目的实现:Redis 滑动窗口限流

### 2.1 数据结构:ZSET(sorted set)做 sliding window log

每张卡一个 ZSET,key = `risk:velocity:{cardId}`,member = 每次尝试的唯一标识,score = 尝试发生的毫秒时间戳。

```lua
-- KEYS[1]=窗口key; ARGV[1]=now(ms); ARGV[2]=窗口下界(ms); ARGV[3]=唯一member; ARGV[4]=TTL秒
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3])                 -- 记入本次尝试
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[2]) -- 裁掉窗口外旧记录
local count = redis.call('ZCARD', KEYS[1])                    -- 窗口内尝试数(含本次)
redis.call('EXPIRE', KEYS[1], ARGV[4])                        -- 续期,清理空闲卡的key
return count
```

几个**反向思考(不写会怎样)**:
- **member 必须唯一**:同一毫秒的并发尝试 score 相同,ZSET 成员若不唯一会被去重 → 少计。所以用 `nowMillis + "-" + UUID`。
- **必须 EXPIRE**:不续期的话,海量历史卡的空 ZSET 会永久堆积 → Redis 内存泄漏。
- **必须 Lua 原子**:见下一节。

### 2.2 fail-open:Redis 挂了怎么办

实现里 catch `DataAccessException` 后返回 `VelocityCheckResult(count=0, degraded=true, source=REDIS)`。
**这是显式 fail-open policy**:Redis 抖动时降级放行,而不是让授权失败。
- counterfactual:如果 fail-closed(抛异常),一次 Redis 抖动会让**全系统所有授权被拒**——为一个非关键风控信号牺牲整体可用性,得不偿失。
- 前提:velocity 只是**多个风控规则之一**(限额、外部风控、credit limit 仍生效),所以可以放行。如果它是唯一的安全闸,就要重新权衡。
- observability:Redis adapter 记录 `risk.velocity.redis.unavailable`,service 记录
  `risk.velocity.fallback.allow{source=redis}`。短时间连续 fallback allow 要报警,因为这表示
  “velocity 正在失效放行”,不是正常 0 次。
- 不默认回查 DB:Redis 故障时全量 fallback 到 `COUNT(*)` 会把每笔授权重新压回主库,可能把
  Redis brownout 放大成 MySQL/Hikari brownout。真要做 DB fallback,也应加 bulkhead、timeout、
  rate limit,最好走 read replica,并且只对高风险或采样请求启用。

### 2.3 与 JDBC 实现的语义差异(interview 常问)

| | JdbcRiskVelocityCounter | RedisRiskVelocityCounter |
|---|---|---|
| 数据来源 | 读 `authorizations` 表(audit source of truth) | 自维护的**近似**滑动窗口 |
| 精度 | 精确 | 近似(够风控用) |
| 主库压力 | 每笔授权一次读往返 | **零读压** |
| 适用 | 低流量 / 强一致需求 | 高流量 |

两者都不会被幂等重试重复计数(重试在读 velocity 之前就 return 了)。port `RiskVelocityCounter`
让 `RiskAssessmentService` 不知道底层是哪种,用 `@ConditionalOnProperty(risk.velocity.store)`
切换；但 port 返回 `VelocityCheckResult`,所以 service 仍能知道这次结果是否 `degraded`。

注意：`card_risk_features` 是另一层 long-window profile。它由 Kafka event 异步 upsert,
现在也会被 `RiskAssessmentService` 读回决策；它解决的是历史画像,不是 60 秒短窗口 velocity。
这能补齐 CQRS projection 的读侧,但会让通过 cheap rules 的授权多一次 DB read,所以生产要监控
projection read latency / Hikari pending,必要时走 read replica 或 profile cache。

---

## 3. 限流(Rate Limiting)通用知识

### 3.1 五种算法

| 算法 | 思路 | 优点 | 缺点 |
|---|---|---|---|
| **Fixed Window** 固定窗口 | 按时间桶 `INCR` | 实现最简单、省内存 | **边界突刺**:窗口交界处可达 2x 速率 |
| **Sliding Window Log** 滑动窗口日志 | 存每次请求的时间戳(ZSET) | 精确、无突刺 | 内存随请求数增长 |
| **Sliding Window Counter** 滑动窗口计数 | 多个小桶加权 | 近似无突刺、省内存 | 略不精确 |
| **Token Bucket** 令牌桶 | 匀速发令牌,桶有容量 | **允许突发**(burst 到容量) | 不直接给"窗口内次数" |
| **Leaky Bucket** 漏桶 | 请求入桶,匀速流出 | 输出绝对平滑 | 不允许突发、可能排队 |

本项目选 **sliding window log**:因为风控要的是"过去 N 秒**精确发生了几次**"这个**信号**(喂给风控决策),不是一个在边缘 reject 的硬阀门;token bucket 给的是"准不准过",语义不匹配。

### 3.2 在哪一层限流

- **边缘/网关**(AWS API Gateway throttling、WAF rate-based rule、Envoy/Service Mesh、Nginx):粗粒度、按 IP/API key,在请求进应用前挡掉。高流量系统通常先在这层扛。
- **应用内分布式**:按业务身份(每卡/每用户),跨实例共享计数 → Redis。
- **应用内单机**(Resilience4j `RateLimiter`,项目已有):per-instance,**不跨实例**,多副本下不准。

### 3.3 分布式限流的核心难点:原子性

多实例并发对同一个 key 计数,"读-改-写"必须原子,否则计数偏小、限流被绕过。Redis 的解法是 **Lua 脚本**(下一节)或 `INCR` 这种单命令原子操作。

---

## 4. Lua in Redis

### 4.1 为什么 Redis 用 Lua 能保证原子

Redis 命令执行是**单线程串行**的;一段 Lua 脚本在执行期间**不会被其他命令打断**。所以"裁剪+ZADD+ZCARD+EXPIRE"四步放进一个脚本 = 一个**无需显式分布式锁**的原子操作。
- counterfactual:不用 Lua,分四次命令发过去,并发请求会在 ZADD 和 ZCARD 之间交错 → 计数错乱、限流失效。

### 4.2 EVAL vs EVALSHA

- `EVAL`:每次把脚本文本发过去。
- `EVALSHA`:先 `SCRIPT LOAD` 得到 SHA1,之后只发 SHA1,省带宽。Spring 的 `DefaultRedisScript` 默认走 EVALSHA,遇到 `NOSCRIPT` 再回退 EVAL。

### 4.3 坑

- **脚本会阻塞单线程**:Lua 里别写重循环/大范围操作,会卡住整个 Redis。
- **Redis Cluster 的多 key 必须同 slot**:一个脚本里的所有 KEYS 必须 hash 到同一个 slot,否则 `CROSSSLOT` 错误。需要时用 **hash tag** `{cardId}` 把相关 key 固定到同一 slot。本项目脚本只用单 key(一张卡),天然安全。
- **确定性/副本一致**:脚本里产生的随机/时间若用于写入,在主从复制下要小心(现代 Redis 用 effects replication 复制实际写命令,缓解了这点)。本项目把 `now` 和唯一 member 作为 **ARGV 从客户端传入**,而不是在脚本里取,正是为了确定性。

---

## 5. Redisson:什么时候 buy,什么时候 build

**所有现成限流库(Redisson `RRateLimiter`、Bucket4j-redis、Spring Cloud Gateway `RedisRateLimiter`)底层都是 Lua。** "用库"没绕开 Lua,只是把它藏起来——懂 Lua 才有资格选库、调库。

**Redisson 是什么**:一个完整的 Redis 客户端 + 分布式对象框架,提供 `RLock`(可重入分布式锁,带 watchdog 自动续租)、`RMap`、`RSemaphore`、`RRateLimiter`(令牌桶式)等。核心 Apache-2.0,部分高级特性/性能在 **Redisson PRO**(商业版)。

**何时该用 Redisson(buy)**:当你**同时需要一批分布式原语**,尤其是**分布式锁**——`RLock` 的边界 case(续租、误删别人的锁、可重入)手写极易出 bug,这是典型的"该 buy 不该 build"。

**何时该手写 Lua(build)**:像本项目的滑动窗口计数——语义特殊(风控信号、要和风控特征整合)、只有几行、且要完全掌控数据模型。为它引入 Redisson(换/加一个 Redis 客户端 + 重依赖)不划算。

**判断线(interview 可背)**:
> 通用、边缘、粗粒度限流 → 推到 API Gateway;应用内跨实例按业务身份限流 → Bucket4j / Redisson;但语义特殊且简单的(风控 velocity)→ 手写 Lua;需要分布式锁等复杂原语 → Redisson。

---

## 6. 缓存设计通用规则(interview 必备)

### 6.1 读写模式(caching patterns)

| 模式 | 说明 | 备注 |
|---|---|---|
| **Cache-Aside(旁路)** | 读:先查缓存,miss 再查 DB 回填;写:更新 DB 后**删缓存** | 最常用 |
| **Read-Through** | 应用只问缓存,缓存自己负责回源 | 本项目历史 `SnapshotCache` 框架更接近这种 |
| **Write-Through** | 写时同步写缓存+DB | 强一致、写慢 |
| **Write-Behind / Write-Back** | 先写缓存,异步刷 DB | 写快、有丢数据风险 |
| **Refresh-Ahead** | 热点 key 过期前主动刷新 | 防击穿 |

### 6.2 缓存一致性(cache-aside 的经典坑)

- **更新时该"删缓存"而不是"更新缓存"**:更新缓存在并发下容易写入旧值;删缓存让下次读回源,更简单可靠。
- **"先更 DB 再删缓存"** + 极端并发下的 read-old-write 竞争 → **延迟双删(delayed double delete)**:更新后删一次,过一会儿再删一次。
- 本项目早先在 repayment 后用 **after-commit eviction**(事务提交后再删 statement 缓存),就是为了避免"事务内提前删缓存、另一个读又把旧值写回 Redis"。

### 6.3 三大经典问题

| 问题 | 是什么 | 解法 |
|---|---|---|
| **缓存穿透 penetration** | 查**不存在**的 key,每次都打到 DB(常是攻击) | 缓存空值(短 TTL)/ **布隆过滤器** |
| **缓存击穿 breakdown** | **单个热 key 过期瞬间**,大量请求同时回源 | 互斥锁重建（**本项目已实现跨 pod 重建锁,见 §6.5**）/ 逻辑过期 / 热点 key 不过期 |
| **缓存雪崩 avalanche** | **大量 key 同时过期** 或 Redis 整体挂 | **TTL 加随机抖动 jitter**(本项目 remote-ttl jitter 已实现) / 多级缓存 / 熔断降级 |

### 6.4 其他高频点

- **热 key(hot key)**:某明星卡/商户被打爆 → 加本地缓存(L1)、key 拆分、读副本。
- **大 key(big key)**:单个 value/集合无限增长 → 拆分、设上限。本项目 ZSET 用 `ZREMRANGEBYSCORE` 裁剪 + `EXPIRE`,防止无限增长。
- **淘汰策略**:`maxmemory-policy`(LRU/LFU/TTL/noeviction);缓存是"可丢"的,DB 才是 source of truth。
- **L1+L2**:本地缓存(Caffeine,纳秒级、不跨实例)+ 分布式缓存(Redis,跨实例、有网络成本);L1 TTL 要短,因为别的实例的写不会 evict 本机 L1。

### 6.5 本项目的击穿防护:跨 pod 重建锁(已实现)

`StatementReadService` 在 L2 miss 后用一把**跨 pod 的 Redis 重建锁**做分布式 single-flight:

- **为什么需要**:Caffeine 的 `get(key, loader)` 只在**同一个 JVM** 内 single-flight;热点 statement 的 L2 过期时,10 个 pod 仍会有 10 个线程同时回源 MySQL。这就是教科书"缓存击穿",只是发生在多实例维度。
- **怎么做**:miss 后 `SET <lockKey> <token> NX PX <ttl>`。
  - 抢到锁的 **winner** 回源 DB + 写回 L2,`finally` 里释放锁。
  - 没抢到的 **loser** 短暂自旋(`wait-attempts × wait-interval`,默认 5×20ms=100ms 封顶)重读 L2,winner 填好后直接复用,不打库。
- **`SET NX` 为什么必须带 `PX`(TTL)**:持锁者崩溃/卡住时锁要能自动过期,否则热点 key 被永久锁死;TTL 也是"持锁者死后多久放下一个重建者进来"的上限。
- **释放锁为什么走 Lua**:必须"GET 比对 token==自己 → 再 DEL",且两步原子。直接 `DEL` 会误删"我超时后别人刚抢到的那把锁"(经典误删他人锁 bug)。
- **为什么不需要 fencing token**:fencing token 是为挡住"GC 暂停后醒来、以为自己还持锁"的旧持有者对**共享可变状态的非幂等写**。这里被锁保护的动作是"只读重建 + CAS 写回",**本身幂等**——锁偶尔不互斥最坏只是多一次回源,不破坏数据。所以这把锁是 best-effort **优化**,不是正确性闸。
- **全程 fail-open**:抢锁异常 / 等待超时 / Redis 故障一律退回"自己回源"(退化成没有这把锁时的原始行为),并打 `statement.read_cache.rebuild_lock{outcome=winner|contended_hit|fallback_db|error}` 指标,观测命中/竞争/降级比例。

> **缓存三大问题在本项目的落点**:雪崩→remote-ttl jitter;击穿→跨 pod 重建锁;穿透→**故意不设布隆过滤器**,因为 statement id 是系统内部生成的 UUID,不可猜测、不存在"海量查不存在 key"的攻击面。这点要会主动说明,而不是无脑加布隆过滤器。

---

## 7. interview 需要掌握的技术点清单

- 缓存的**本质判断**:命中率(temporal/spatial locality)+ 是否卸掉真实成本;**什么时候不该缓存**。
- 读写模式 + **一致性**(删 vs 更、延迟双删、after-commit evict)。
- **三大问题**(穿透/击穿/雪崩)+ 解法。
- **限流五算法** + 选型(尤其 sliding window vs token bucket)。
- **分布式原子性**:Redis 单线程 + Lua;EVAL/EVALSHA;Cluster 同 slot / hash tag。
- **降级**:fail-open vs fail-closed,缓存/Redis 不可用时的行为。
- **build vs buy**:Redisson/Bucket4j 的定位、底层都是 Lua、何时引入。
- **多级缓存**、热 key、大 key、淘汰策略、TTL jitter。

---

## 8. 硬核 interview 题 + 追问 + 解答

**Q1. 你给 velocity 用了 Redis,却把 card 缓存删了,为什么?**
A:读缓存只有"TTL 内重复读同一 key"才有收益,card 单卡请求率低、且 status 是授权 gate 必须短 TTL,命中率被压死,而且它不是瓶颈、幂等重试还不读它。velocity 不同:它把每笔授权对主库的 COUNT 往返整体搬到 Redis,**不依赖命中率、对每个请求都减负**,主库正是被 `FOR UPDATE` 占着的瓶颈。
- *追问:那 card 读会不会成新瓶颈?* 不会,它是非锁定主键查询;真正要优化的是账户那条锁路径,而那条因为强一致不能缓存。

**Q2. Redis 滑动窗口为什么必须用 Lua?不用会怎样?**
A:"裁剪旧记录→ZADD→ZCARD→EXPIRE"是多步;Redis 单线程串行执行 Lua,期间不被打断,等于原子。不用 Lua,并发请求会在 ZADD 和 ZCARD 之间交错,计数偏小、限流被绕过。
- *追问:Lua 会不会有性能问题?* 会——脚本阻塞单线程,所以脚本要短、避免大范围操作;我的脚本是 O(log N) 级别的 ZSET 操作,可接受。

**Q3. sliding window log 和 token bucket 你怎么选?**
A:velocity 是**风控信号**(要"过去 N 秒精确几次"喂给决策),不是边缘硬阀门,所以选 sliding window log(精确、无边界突刺)。如果是 API 网关限流、允许突发,token bucket 更合适(能 burst 到容量)。
- *追问:sliding window log 内存会不会爆?* 每次 `ZREMRANGEBYSCORE` 裁掉窗口外 + `EXPIRE` 清空闲 key,所以单卡 ZSET 大小被窗口内尝试数限制,不会无限增长。

**Q4. Redis 挂了怎么办?fail-open 还是 fail-closed?**
A:这里 fail-open,但不是静默返回裸 0。Redis adapter 返回 `VelocityCheckResult(count=0, degraded=true, source=REDIS)`,
并打 `risk.velocity.redis.unavailable`;`RiskAssessmentService` 再打 `risk.velocity.fallback.allow`。
因为 velocity 只是多条风控规则之一,限额/external risk/credit limit 还在;若 fail-closed,一次 Redis 抖动会拒掉全系统所有授权,可用性灾难。
但如果某个检查是唯一的安全闸(比如反欺诈硬规则),就要 fail-closed 或加备用路径。
- *追问:fail-open 期间被攻击怎么办?* 边缘还有粗粒度限流 + WAF;短时间连续 `fallback.allow`
  要报警。默认不全量回查 DB,避免把 Redis 故障扩大成主库故障。

**Q5. Redis 的计数和 DB 的 authorizations 表不一致怎么办?**
A:DB 是 source of truth(审计、对账),Redis 是**近似**的快速计数,用于实时风控决策。两者目的不同,允许短时不一致;Redis 重启丢窗口也无所谓(滚动限流,不是钱)。要严格精确时切到 jdbc 实现(读表)。

**Q6. 缓存穿透/击穿/雪崩分别是什么,怎么解?**
A:穿透=查不存在的 key 每次打 DB → 缓存空值/布隆过滤器;击穿=单个热 key 过期瞬间打爆 DB → 互斥重建/逻辑过期/热点不过期;雪崩=大量 key 同时过期或 Redis 挂 → TTL 加 jitter/多级缓存/熔断降级。
- *追问:布隆过滤器的缺点?* 有假阳性(说"可能存在"其实不存在)、不能删元素(需 counting bloom)。

**Q7. cache-aside 更新时为什么删缓存而不是更新缓存?并发不一致怎么处理?**
A:更新缓存在并发下可能把旧计算结果写进去;删缓存让下次读回源最新值,更简单。极端的"读到旧值并回填"竞争用**延迟双删**或给缓存设短 TTL 兜底;写后失效要放在**事务提交之后**(after-commit),否则可能在事务可见前就被另一个读把旧值写回缓存。

**Q8. 你这套 Lua 在 Redis Cluster 下有什么坑?**
A:一个脚本里所有 KEYS 必须在同一个 hash slot,否则 CROSSSLOT。我的脚本只用一张卡的单 key,天然同 slot;如果要在一个脚本里操作多张卡,得用 hash tag `{...}` 把它们固定到同 slot,或改成多次调用。

**Q9. 为什么不用 Redisson?什么时候会用?**
A:Redisson/Bucket4j 底层也是 Lua,而我的需求(风控滑动窗口计数)语义特殊、只有几行、要掌控数据模型,引入 Redisson(重依赖、换/加 Redis 客户端、部分特性是 PRO)不划算。真要用 Redisson 是在需要**分布式锁 RLock** 这种自己写容易出 bug 的复杂原语时。

**Q10. 这条授权热路径还有别的可优化点吗?**
A:真正的瓶颈是 `credit_accounts` 行锁(同账户串行)。可讨论:账户分桶/分片降低单点争用、把可缓存的只读参考数据(FX 汇率、BIN、商户类目)做成全 fleet 共享的 read-through 缓存(命中率高,因为一个 key 服务所有请求)、读写分离把只读查询打到副本。注意:强一致的额度写不能缓存。

**Q11. 你那把缓存重建锁,和"分布式锁保护转账"有什么本质区别?为什么不用 Redlock / fencing token?**
A:本质区别是**被保护动作是否幂等、锁是否正确性闸**。我的重建锁保护的是"回源 DB 重建只读快照 + CAS 写回 L2"——幂等,锁只是**性能优化**:偶尔两个 pod 同时重建,最坏多一次 DB 读,数据不会错,所以全程 fail-open、不需要 fencing token。而"扣款/额度"这类对共享可变状态的非幂等写,正确性**不能**依赖 Redis 锁(Redlock 在 GC 暂停 + 时钟漂移下并非绝对互斥),必须靠 DB 行锁 `FOR UPDATE` / 唯一约束 / 版本号这种**单一权威**保证。一句话:**钱用 DB 的强一致,缓存重建用 best-effort Redis 锁。**
- *追问:那为什么不用 Redisson 的 RLock?* 我的需求只有"miss 时 single-flight",几行 `SET NX` + Lua 释放就够,且要自己掌控 fail-open 行为;Redisson 的可重入、看门狗自动续租、RedLock 多节点对"幂等的缓存重建"是过度设计。需要可重入锁、看门狗续租、跨多 Redis 节点这类复杂锁语义时才值得 buy。
- *追问:loser 自旋会不会拖慢请求?* 有上限:`wait-attempts × wait-interval`(默认 100ms)封顶,超时就 fail-open 自己回源,绝不无限等;而且只有"L1+L2 双 miss 且非 winner"的少数请求才会进自旋,热路径(命中)完全不受影响。
