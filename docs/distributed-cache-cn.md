# 分布式缓存、限流、Lua 与缓存设计通用规则

> 关键词：distributed cache, Redis, rate limiting, sliding window, token bucket,
> Lua atomicity, cache-aside, 缓存穿透/击穿/雪崩, fail-open, Redisson,
> 分散キャッシュ(ぶんさんキャッシュ), レート制限(せいげん)。

本文是本项目「缓存技术点」的学习/复习材料,既讲**本项目里实际怎么实现的**,也补**项目之外的通用知识**,最后给一组**硬核面试题 + 追问 + 解答**。

相关代码:
- `risk/infrastructure/redis/RedisRiskVelocityCounter.java`(Redis 滑动窗口限流)
- `risk/infrastructure/jdbc/JdbcRiskVelocityCounter.java`(SQL COUNT 对照实现)
- `risk/application/RiskVelocityCounter.java`(port)

---

## 1. 背景:这个项目的缓存是怎么演进的

项目最早有两块**读缓存(read-through cache)**:`statement` 读模型 和 `card` 快照,都用 Caffeine(L1)+ Redis(L2)的两级框架。后来都删掉了,原因值得讲清楚——**这本身就是面试加分点:会判断"该不该缓存"。**

### 1.1 为什么删掉 card snapshot 缓存

读缓存有没有价值,只看一件事:**同一个 key 在 TTL 窗口内被重复读到的概率(temporal locality)。** card snapshot 在这件事上很差:

1. **单卡请求率低**:一个持卡人一天就几笔授权,分散在全天;短 TTL 内重复刷同一张卡几乎不发生 → 命中率低。
2. **安全 TTL 与命中率是死结**:card status 是授权的 gate(blocked 卡必须立刻拒),所以 TTL 必须短到能及时失效;而"必须短"恰好压死命中率。**对"stale 会出事"的数据,你能安全用的 TTL,正好是命中率最差的 TTL。**
3. **它不是瓶颈**:授权热路径真正的瓶颈是 `credit_accounts` 的 `SELECT ... FOR UPDATE`(同账户串行);读 card 只是一次非锁定主键查询,本来就便宜。缓存一个非瓶颈的廉价读,收益有限。
4. **幂等重试根本不读 card**:`AuthorizationService` 先 claim,`if (!claimed) return`,重试在读 card **之前**就返回了 → 享受不到缓存。

结论:card snapshot 缓存是个**弱缓存**,删掉它、把缓存这个技术点搬到真正合适的地方。

### 1.2 为什么 velocity 计数适合 Redis(而 card 不适合)

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

实现里 catch `DataAccessException` 后返回 0(放行)。**这是 fail-open**:Redis 抖动时降级放行,而不是让授权失败。
- counterfactual:如果 fail-closed(抛异常),一次 Redis 抖动会让**全系统所有授权被拒**——为一个非关键风控信号牺牲整体可用性,得不偿失。
- 前提:velocity 只是**多个风控规则之一**(限额、外部风控、credit limit 仍生效),所以可以放行。如果它是唯一的安全闸,就要重新权衡。

### 2.3 与 JDBC 实现的语义差异(面试常问)

| | JdbcRiskVelocityCounter | RedisRiskVelocityCounter |
|---|---|---|
| 数据来源 | 读 `authorizations` 表(audit source of truth) | 自维护的**近似**滑动窗口 |
| 精度 | 精确 | 近似(够风控用) |
| 主库压力 | 每笔授权一次读往返 | **零读压** |
| 适用 | 低流量 / 强一致需求 | 高流量 |

两者都不会被幂等重试重复计数(重试在读 velocity 之前就 return 了)。port `RiskVelocityCounter` 让 `RiskAssessmentService` 不知道底层是哪种,用 `@ConditionalOnProperty(risk.velocity.store)` 切换。

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

**判断线(面试可背)**:
> 通用、边缘、粗粒度限流 → 推到 API Gateway;应用内跨实例按业务身份限流 → Bucket4j / Redisson;但语义特殊且简单的(风控 velocity)→ 手写 Lua;需要分布式锁等复杂原语 → Redisson。

---

## 6. 缓存设计通用规则(面试必备)

### 6.1 读写模式(caching patterns)

| 模式 | 说明 | 备注 |
|---|---|---|
| **Cache-Aside(旁路)** | 读:先查缓存,miss 再查 DB 回填;写:更新 DB 后**删缓存** | 最常用 |
| **Read-Through** | 应用只问缓存,缓存自己负责回源 | 本项目被删的 SnapshotCache 框架就是这种 |
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
| **缓存击穿 breakdown** | **单个热 key 过期瞬间**,大量请求同时回源 | 互斥锁重建 / 逻辑过期 / 热点 key 不过期 |
| **缓存雪崩 avalanche** | **大量 key 同时过期** 或 Redis 整体挂 | **TTL 加随机抖动 jitter** / 多级缓存 / 熔断降级 |

### 6.4 其他高频点

- **热 key(hot key)**:某明星卡/商户被打爆 → 加本地缓存(L1)、key 拆分、读副本。
- **大 key(big key)**:单个 value/集合无限增长 → 拆分、设上限。本项目 ZSET 用 `ZREMRANGEBYSCORE` 裁剪 + `EXPIRE`,防止无限增长。
- **淘汰策略**:`maxmemory-policy`(LRU/LFU/TTL/noeviction);缓存是"可丢"的,DB 才是 source of truth。
- **L1+L2**:本地缓存(Caffeine,纳秒级、不跨实例)+ 分布式缓存(Redis,跨实例、有网络成本);L1 TTL 要短,因为别的实例的写不会 evict 本机 L1。

---

## 7. 面试需要掌握的技术点清单

- 缓存的**本质判断**:命中率(temporal/spatial locality)+ 是否卸掉真实成本;**什么时候不该缓存**。
- 读写模式 + **一致性**(删 vs 更、延迟双删、after-commit evict)。
- **三大问题**(穿透/击穿/雪崩)+ 解法。
- **限流五算法** + 选型(尤其 sliding window vs token bucket)。
- **分布式原子性**:Redis 单线程 + Lua;EVAL/EVALSHA;Cluster 同 slot / hash tag。
- **降级**:fail-open vs fail-closed,缓存/Redis 不可用时的行为。
- **build vs buy**:Redisson/Bucket4j 的定位、底层都是 Lua、何时引入。
- **多级缓存**、热 key、大 key、淘汰策略、TTL jitter。

---

## 8. 硬核面试题 + 追问 + 解答

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
A:这里 fail-open(返回 0 放行)。因为 velocity 只是多条风控规则之一,限额/外部风控/credit limit 还在;若 fail-closed,一次 Redis 抖动会拒掉全系统所有授权,可用性灾难。但如果某个检查是唯一的安全闸(比如反欺诈硬规则),就要 fail-closed 或加备用路径。
- *追问:fail-open 期间被攻击怎么办?* 边缘还有粗粒度限流 + WAF;且 Redis 不可用是短时事件,可配合告警 + 自动降级策略。

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
