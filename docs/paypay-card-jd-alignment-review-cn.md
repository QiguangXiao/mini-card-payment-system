# PayPay Card JD 对齐度复盘（独立评审）

这份文档不是 JD 逐条映射表。逐条映射已经在
[`paypay-card-jd-fit-cn.md`](paypay-card-jd-fit-cn.md) 里写得很全了。

> [!NOTE]
> 评审基准：本文基于 PR #1（大规模重构，223 文件 +9.3k/−2.9k）合并进 `main` **之后**的代码状态。
> 那次合并把通用两级快照缓存（`TwoLevelSnapshotCache` / `CachedCardRepository`）整体移除，
> 改成"Redis 滑窗限速 + statement GET 的 L1/L2 cache-aside（Lua CAS + Pub/Sub 失效）"，
> 新增了第三套 claimable job（StatementJob）和 Testcontainers 集成测试，并把 `Money` 提为 shared-kernel VO。
> 下面的事实与锚点都已对齐到该状态。

> [!CAUTION]
> **二次复盘（2026-06-30 更新）——我有了一个新判断，而且它不太舒服。**
>
> 自第一版 review（commit `95406e9`）写完之后，仓库又往前走了 17 个 commit。我把它们逐条看了：
> **15 个是文档**（一次 step 1–11 的大规模 docs 重组 + 新增 `notification-mechanism-review` 750 行等分析文档），
> **2 个是代码**（`6db9ea2` / `c18843d`：给三套 claimable job 加 explicit lease/claim token）。
> 关键事实是：**第一版 review 点名的 P0 缺口，一个都没补。**
> 至今仍然 **没有 Dockerfile、没有 CI、没有任何 IaC/CFN artifact、没有一行 k6 压测数据、没有 gRPC**。
>
> 更尖锐地说：这 17 个 commit 几乎完美地落在了 review 当时**叫你别再投入**的两个方向上——
> 一是继续写/重组文档（§3.1），二是继续打磨那套**已经过度**的三作业引擎（§3.2 明确写了"不建议现在去重构"）。
> 而 review 当时判定**唯一能翻盘**的方向（一块真实可运行的云/CI artifact + 一组压测数字）原地未动。
> **第一版 review 在 §3 预言的 failure mode——"把'写文档/重构'当成'取得进展'的代餐"——这一个月被原样验证了一遍。**
>
> 所以本次复盘的结论不是"换个判断"，而是**同一个判断被证据加强了，且变得更紧迫**：
> 优先级没变，只是 P0（Dockerfile + CI）从"建议做"升级为"现在不做就是这一个月最大的机会成本"。
> 唯一值得肯定的正向动作：docs 重组确实建立了 `docs/archive/`、把学习笔记沉到 archive（正是 §3.1 让你做的"分层"），
> 但**净文档量仍然涨了 ~2,400 行**，分层没有压住总量。详见下方已更新的 §0 硬数字与 §3.1。

这份文档是一次**带观点的外部 review**，专门回答你问的四个问题：

1. 这个项目和 PayPay Card JD 的对齐度到底怎么样？
2. 哪些部分已经很强、很 valid，哪些**有点过度了**？
3. 哪些**现在值得加强**，哪些**暂时不用碰**？
4. 为了面这个岗位，**项目之外**还需要做什么，具体怎么做？

> [!IMPORTANT]
> 一句话结论：项目本身已经**超额**完成了"证明我能写正确的金融后端"这件事。
> 现在最大的风险不是"做得不够"，而是"在错误的维度上继续加投入"。
> 接下来的边际收益，主要来自**项目之外**（云/IaC 落地一小块、算法、系统设计、行为面、日语），
> 而不是再写第 259 个 Java 类或第 27,000 行文档。

---

## 0. 先看几个硬数字

> 数字已刷新到 2026-06-30 的 `HEAD`（`d629a24`）。括号内是与第一版 review（PR #1 anchor）的对比。

| 维度 | 现状 | 解读 |
| --- | --- | --- |
| 业务主链路 | Authorization → Posting → Statement → Repayment 全通 | ✅ 核心叙事完整 |
| 生产代码 | 15,683 行 / 258 个 main 类（≈持平） | 体量足够，甚至偏多 |
| 测试代码 | 6,990 行 / 64 个测试类（+1 类），含 Testcontainers 集成测试 | 测试占比 ~45%，健康且有真实集成测试 |
| Bounded context | 多个业务与机制边界（Notification 保留独立 consumer） | 广度足够，不再增加投影型 context |
| 文档 | **29,228 行 / 36 个文件**（上次 26,835 / 28，**+2,393 行 / +8 文件**） | ⚠️ 是生产代码的 **~1.86 倍**（上次 ~1.7），**review 警告后仍在涨**，是最固执的过度 |
| 可靠性基建 | Outbox + Inbox + **三套** claimable job（DelayJob/Outbox/StatementJob，本月又给三套统一加了 explicit lease token）+ Resilience4j + DLT + statement L1/L2 缓存（Lua CAS + Pub/Sub 失效） | ✅ 顶配但**继续在已饱和处加投入**（见 §3.2）|
| 云 / 部署落地 | 只有 `docker-compose.yml`；**仍无 Dockerfile、无 CI、无 IaC、无压测数据**（一个月零变化） | ❌ 与公司栈（ECS/CFN/CodePipeline）最大的真实差距，且**纹丝未动** |

这张表基本框定了所有结论：**正确性维度过度饱和，部署/工程化维度有真实缺口（且一个月零进展），文档维度严重过度（且仍在恶化）。**

---

## 1. 总体对齐度：B+ / A-

按"面试官真正会验证什么"来打分，而不是按 JD 关键词覆盖率。

| JD 能力 | 我的打分 | 一句话理由 |
| --- | --- | --- |
| Java / Spring Boot / OOP | **A** | 分层干净，domain 不漏技术细节，构造注入、配置绑定到位 |
| 金融数据正确性（事务/锁/幂等/状态机） | **A** | 这是项目的灵魂，`Money`+`FOR UPDATE`+三层幂等讲得清 |
| 并发与分布式一致性 | **A-** | Outbox/Inbox/SKIP LOCKED/lease/recoverer 一应俱全，且有 Testcontainers 并发/Outbox-roundtrip 集成测试坐实 |
| RDBMS / database client | **A-** | MyBatis 显式 SQL + 唯一约束 + 锁 + Liquibase 迁移 |
| Pub/Sub / Kafka / event-driven | **A-** | Notification 单 consumer group + 按失败消费组路由的 DLT + 版本化 event，理解到位 |
| Distributed cache | **A-** | statement GET 的 Caffeine L1 + Redis L2 cache-aside（Lua CAS + tombstone + Pub/Sub 跨 pod 失效），且**边界判断正确**——还主动**移除**了低价值的 card 快照缓存 |
| RESTful API | **A-** | Controller 只做 adapter，验证/映射清晰 |
| High traffic 设计 | **B** | 有 2,149 行设计文档，但**零压测数据**，缺"真打过"的证据 |
| NoSQL | **C+** | 无实操，只有 trade-off 论述（对学习项目够了，不强求） |
| AWS（ECS/CFN/CodePipeline/CloudWatch） | **C** | 1,620 行设计文档，但**零可运行 artifact**，与公司栈差距最大 |
| Microservices | **B** | modular monolith + 清晰拆分边界，能讲清"为什么现在不拆" |
| gRPC | **C** | 无实现（preferred，非必须） |
| 测试 / 协作 / 文档 | **A**（质量）/ **C**（克制度） | 内容质量高，但**体量失控**，反而成了减分项，见 §3 |

**对齐度结论**：作为一个"自学准备的后端面试项目"，它已经处在同类项目的 **top 5%**。
继续往上走的瓶颈不在项目正确性，而在**真实工程化落地**和**项目之外的能力面**。

---

## 2. 已经很强、很 valid —— 不要再动它

这些部分**已经到位，继续投入是浪费**。面试里直接当王牌打。

1. **money-changing path 的正确性叙事。** 授权占额 → 入账 → 出账 → 还款，
   每一步的 transaction boundary、`SELECT ... FOR UPDATE`、状态机都站得住。
   `Money`（`BigDecimal` + `DECIMAL(19,2)`，现已提为 `shared/domain` 的 shared-kernel VO）这种细节正是金融岗想看的。

2. **三层幂等的层次感。** API `Idempotency-Key`（客户端重试）、
   `network_transaction_id`（外部清算重放）、`repayments.idempotency_key`（还款重复）——
   能区分这三种"重复"的来源，比"我加了个唯一索引"高一个段位。

3. **Outbox / Inbox / DLT 的动机讲得对。** 不是"我会用 Kafka"，
   而是"我知道 DB 和 Kafka 没有联合事务，所以用 Outbox 解决 dual-write，
   Kafka 仍是 at-least-once，所以 consumer 侧还要 Inbox 幂等"。这是理解，不是堆砌。

4. **缓存边界的判断力（这次重构后更强）。** statement GET 用 Caffeine L1 + Redis L2 cache-aside，
   并用 versioned Lua CAS + tombstone + Redis Pub/Sub 跨 pod 失效来防 late-write race 和 cache breakdown；
   同时**明确不缓存** `availableCredit` / `reservedAmount` / 幂等 winner，还**主动移除了低价值的 card 快照缓存**。
   "知道什么不该缓存、甚至敢删缓存"比"到处加 Redis"更能体现成熟度——这正好也呼应 §3 的"克制"。

5. **并发的三个层次。** 单请求事务、跨请求 DB 锁（而非 JVM 锁，因为会横向扩容）、
   异步 worker 的 `SKIP LOCKED`+lease+recoverer。这套能直接回答"两个请求同时刷一张卡"，
   而且现在有 `AuthorizationConcurrencyIT` / `DelayJobSkipLockedClaimIT` 用真实 MySQL **跑过**，不只是嘴上说。

6. **测试占比与分层 + 真实集成测试。** controller / domain / service / listener / worker 全覆盖，
   ~44% 测试占比；更关键的是新增了 Testcontainers 集成测试（并发授权、`SKIP LOCKED` claim、
   Outbox→Kafka→Inbox roundtrip）——这种"用真实中间件验证 failure mode"的证据，面试官最买账。

> [!TIP]
> 这 6 点请整理成 6 张"卡片"，每张能在 2 分钟内讲完 + 对应一个 failure 场景。
> 这才是面试的真实弹药；再写代码不会让这 6 张卡片更强。

---

## 3. 有点过度了 —— 边际收益已经为负

诚实说，这是这次 review 最重要的一节。

### 3.1 文档体量（最严重的过度，且这一个月被加强）

> [!IMPORTANT]
> **二次复盘更新：** 第一版 review 把这一节列为"最严重的过度"，并明确写了"从今天起新增文档前先问这行字
> 是让面试更好还是只让我感觉良好"。结果这一个月做了一次诚意十足的 step 1–11 docs 重组——
> 建了 `docs/archive/`、把 `jvm-monitoring` / `thread-runtime` / `mybatis-sql-learning` / `kafka-learning`
> 等学习笔记沉到 archive（**这正是上次让你做的"分层"，值得肯定**），还合并了 caching / messaging / data-layer 几组重复文档。
> **但净结果仍是 +2,393 行 / +8 文件，总量从 1.7 倍涨到 1.86 倍。** 分层动作是对的，方向却仍是"在文档上花时间"：
> 重组本身又消耗了十几个 commit，期间还新增了 `notification-mechanism-review`（750 行）、`domain-state-flow`（1,547 行）等。
> 一句话：**你优化了文档的'目录结构'，但没有停止文档的'总产出'，更没有把这些精力挪到 §4 的 P0 artifact 上。**

**29,228 行文档 / 36 个文件，是生产代码的 ~1.86 倍，review 警告之后仍在涨**。问题不在质量，在体量与重复：

- `paypay-card-jd-fit-cn.md` 单文件 **5,579 行**——比绝大多数真实公司的设计文档都长。
- 它和 `paypay-card-backend-interview-guide-cn.md`（821 行）在内容上大量重叠。
- 一堆"学习笔记"型文档（`spring-java-technical-learning` 2,699 行、`jvm-monitoring` 1,247、
  `thread-runtime` 1,060、`mybatis-sql` 1,096、`kafka-learning` 725）本质是**你的私人复习笔记**，
  不是项目读者需要的东西。

**为什么这是减分项而不只是中性**：
- 没有面试官会读 27k 行文档。仓库第一印象会变成"文档比代码多"，反而像在凑工作量。
- 你自己复习时也会被淹没——真正要背的 6 张卡片散落在 2.7 万行里。
- 它泄露了一个信号：**把"写文档"当成了"取得进展"的代餐。**

**怎么处理**（不用删，分层即可）：
- 在 `README` 里明确分两类：**①面向读者的项目文档**（README、authorization-design、
  kafka-outbox-design、distributed-cache、这份 review）；**②个人复习笔记**（其余）。
- 个人笔记可以挪到 `docs/notes/` 子目录或单独分支，别放在仓库门面上。
- `paypay-card-jd-fit-cn.md` 砍到 800 行以内：保留映射表 + Q&A 模板，删掉重复铺陈。
- **从今天起，新增文档前先问：这行字会让面试表现更好，还是只让我感觉良好？**

### 3.2 三套作业引擎（轻度过度）

`Outbox`、`DelayJob`、以及这次重构新增的 `StatementJob`，是**三套高度相似的 claim-lease-recover job runner**
（claimer/poller/worker/recoverer/dispatcher）。展示一次模式就够了，做三遍属于重复。

- 这不致命，甚至能正面讲："我抽象出了 claim→process→recover 的通用模式，复用在三个场景。"
  PR #1 还专门写了 `claimable-job-families-comparison` 文档对比三者的共享模型与刻意差异——
  这等于已经把下面这道追问的答案准备好了，**面试时直接用即可**。
- 面试官读代码常问"为什么不抽一个公共骨架？"——答案是 trade-off：三者的幂等键、可见性条件、
  backoff 与失败语义不同，过早抽象会增加耦合。（注意别让"我写了三套"显得像炫技。）
- **不建议现在去重构**——重构的收益远低于把时间花在 §4/§5。把它当成一道"已知会被问到"的题即可。

> [!NOTE]
> **二次复盘更新：** 这一个月仅有的 2 个代码 commit（`6db9ea2` / `c18843d`）恰恰就是给这三套 job
> 统一加 explicit lease/claim token。这是一次干净的、正确的工程改进——**但它落在了本节明确写着"不建议现在投入"的方向上。**
> token 模型更严谨了，对面试说服力的边际提升却接近零（面试官不会因为你的 lease 列从隐式变 explicit 而高看一眼）。
> 同样的两天，如果花在 §4 的 Dockerfile + CI 上，能把一个**真实缺口**从"口头"变成"artifact"。这就是机会成本。

### 3.3 Bounded context 的广度 > 深度（轻度过度）

Notification consumer group + DLT 已经足够学习 consumer idempotency 与 dead-letter routing。
继续按 `remaining-domain-roadmap-cn.md` 的 P3/P4/P5（refund / dispute / settlement）加领域，
**只会增加广度，不增加面试说服力**——该路线图自己也是这么判断的，这点你已经做对了。

> [!WARNING]
> "过度"的共同根源是同一个：**用"增加产出"代替"提高单位产出的说服力"。**
> 项目已经过了"证明我能做"的阶段，现在要的是"打磨能讲清楚的少数几个点" + "补真实工程化短板"。

---

## 4. 现在值得加强 —— 高 ROI、小切口

排序原则：**贴 JD 公司栈 + 能产生"可运行/可量化证据" + 小工作量**。

### P0：补一块**真实可运行**的云/工程化 artifact（最高优先）

JD 里公司栈几乎全是 AWS（ECS 部署、CloudFormation 管基建、CodePipeline 做 CI/CD、CloudWatch 观测），
而项目**连 Dockerfile 和 CI 都没有**。这是设计文档再多也补不上的差距——因为差的是"我真的跑通过"。

> [!CAUTION]
> **二次复盘更新：这一节是过去一个月唯一原地踏步的地方，因此它现在是全文最高优先级。**
> `aws-ecs-deployment-cn.md` 已经写到 1,622 行、`high-traffic-system-design-cn.md` 写到 2,150 行，
> 但前者**没有一个能 `validate` 的模板文件**，后者**没有一行实测数字**（它自己第 64 行就写着"后续可以再补 k6 脚本"，
> 第 1493 行只有一段 k6 *轮廓*）。**文档已经把"设计能力"证明到溢出了，现在缺的纯粹是 artifact。**
> 别再给这两份文档加字了——加的每一行都在稀释而不是补强。把下面前两步做掉，比再写 2,000 行设计都值。

最小可信切片（按性价比排序，做前两个就够翻盘）：
1. **写一个生产级 `Dockerfile`**（多阶段构建、非 root 用户、JVM 容器参数）。半天。
2. **加一个 GitHub Actions workflow**（`./gradlew build test` + 构建镜像）。半天。
   ——这两步就把"我能容器化并 CI 这个服务"从口头变成 artifact。
3. **一份能 `validate` 的 CloudFormation/CDK 骨架**：ECS service + ALB +
   task definition 把 `/actuator/health/{liveness,readiness}` 接成健康检查；
   RDS/ElastiCache/MSK 用参数占位即可。**不需要真部署，能 `aws cloudformation validate-template` 通过即可。** 1–2 天。
4. （可选）`buildspec.yml` 雏形，对应 CodePipeline 叙事。

> 面试话术升级：从"如果上 AWS 我会……"变成"我把它容器化并写了 ECS 的 CFN 骨架，
> 健康检查接的是 Actuator readiness，CI 在 GitHub Actions 上跑 build+test"。后者可信度高一个数量级。

### P1：给 high-traffic 文档配**一组真实压测数字**

JD 明确写 "Experience designing high traffic systems"。你有 2,149 行设计文档，但**零数据**。
面试官一句"你压过吗、瓶颈在哪、p99 多少"就会让纯文档显得空。

- 用 **k6 或 Gatling** 写一个授权接口压测脚本（本地 docker-compose 起全套即可）。
- 跑出一组数字：TPS、p95/p99、在多少并发下 HikariCP 开始排队、热点账户行锁等待如何体现。
- 把"我把风控放在 account lock 之前缩短了临界区"这句话，用**压测前后对比**坐实。
- 产出一段 ~200 行的 `docs/load-test-results-cn.md`（**这是值得新增的文档，因为它带证据**）。

工作量 1–2 天，但把 high-traffic 从 B 拉到 A- 的杠杆最大。

### P2：一个 gRPC 小切片（可选，preferred qualification）

把 `ExternalRiskGateway` 背后的模拟 REST client 换成（或并存一个）gRPC adapter，
application 层只依赖 port 不变。这正好呼应 JD 的 "gRPC ... development experience"。
半天到一天，做小而干净即可。**仅在 P0/P1 做完且还有时间时做。**

---

## 5. 暂时不用碰 —— 投入产出比低

| 项 | 为什么可以先不做 |
| --- | --- |
| NoSQL 实操（引 DynamoDB 等） | 核心交易不该迁 NoSQL；现有 trade-off 论述对学习项目足够。**真要补也只补 read-model 投影，别动核心表。** |
| Refund / Dispute / Settlement | `remaining-domain-roadmap-cn.md` 已正确判定为 P3–P5；只增广度不增说服力，概念能讲即可 |
| 真正拆成微服务 | 会引入分布式事务/部署复杂度，稀释当前最强的"本地正确性"叙事；讲清"为什么不拆"反而更值钱 |
| User / Auth / PII | 引入登录与安全设计，偏离金融一致性主线 |
| Scala / C# | JD 写的是 "welcome / 望ましい"，不是必须；你的 Java 深度更值得打磨 |
| 重构三套 job 引擎 | 见 §3.2，当成"会被问到的题"准备答案，别花时间重构 |

---

## 6. 项目之外要做的事 —— 这才是当前的主战场

项目已经饱和。**面试通过与否，接下来更多取决于下面这些项目证明不了的能力。**
每项给：为什么 + 具体怎么做 + 大致投入。

### 6.1 算法 / 数据结构（JD 明确要求，项目无法体现）

JD 白纸黑字："Strong fundamentals in data structures, algorithms"。
个人项目**几乎无法证明**这一点——金融后端面试常有一轮独立的 coding。

- **怎么做**：LeetCode 按主题刷，不要盲刷。优先 array/hashmap、two pointers、binary search、
  BFS/DFS、heap、interval、基础 DP。目标**中等题能 25–35 分钟内讲思路 + 写干净代码 + 说复杂度**。
- **量**：~120–150 题（精刷 + 复盘 > 盲刷 300）。每天 2 题，6–8 周。
- **关键**：练**边写边讲**（出声讲思路），因为金融大厂 coding 轮看沟通不只看 AC。

### 6.2 系统设计面试（把项目变成可迁移的设计能力）

你的项目本身就是最好的系统设计素材，但**面试的系统设计是白板抽象题**，要单独练表达。

- **怎么做**：练 3–4 道经典题，且**每道都能引回你的项目**：
  - "设计一个支付/授权系统"（直接复用你的主链路，王牌）。
  - "设计一个保证 exactly-once 副作用的事件系统"（Outbox/Inbox）。
  - "设计一个分布式限流 / 热点账户保护"。
  - "设计一个读多写少的账单查询服务"（两级缓存 + 读写分离）。
- **框架**：需求→容量估算→API→数据模型→高层架构→瓶颈/扩展→失败模式→权衡。
- **资源**：DDIA（《Designing Data-Intensive Applications》）精读 1–7 章是性价比最高的单一投入。
- **投入**：4–6 周，每周 1–2 道，配 DDIA。

### 6.3 并发 / 分布式**理论深度**（撑起 JD 的 "in-depth understanding"）

JD 用了 "in-depth understanding of concurrency and distributed computing"。
项目展示了**实践**，但面试会追**原理**。

- **并发**：Java Memory Model（happens-before、volatile）、`synchronized` vs `ReentrantLock`、
  CAS/AQS、线程池饱和策略、死锁四条件。能解释"为什么我用 DB 锁不用 JVM 锁"的底层原因。
- **分布式**：一致性模型（线性一致 vs 最终一致）、CAP/PACELC、幂等与去重、
  2PC 为什么少用、共识（Raft 概念级）、为什么 Kafka 是 at-least-once 而非 exactly-once。
- **怎么做**：针对每个概念准备一个"项目里的落点"。投入 2–3 周，穿插进行。

### 6.4 行为面 / STAR（JD 直接点名的软技能）

JD 写明 "Experience in a multicultural environment" 和 "Stakeholder management is welcomed"。
这两条**只能靠故事**，项目给不了。

- **怎么做**：用 STAR（Situation-Task-Action-Result）准备 6–8 个故事，覆盖：
  跨文化/跨语言协作、推动技术决策、和非技术 stakeholder 对齐、处理线上故障/冲突、
  做过的权衡取舍、失败与复盘。**每个故事配一个量化结果。**
- 另准备："为什么 PayPay Card / 为什么日本 / 为什么这个岗位"——这几乎必问。
- **投入**：写下来 + 朗读演练，1 周。

### 6.5 日语（PayPay Card 在日本，JD 双语）

JD 是中日双语、公司在日本。**先确认这个岗位的语言要求**（英语可行 vs 需要商务日语）。

- 若需日语：评估 JLPT 水平，至少练能用日语做自我介绍 + 讲项目主链路的程度。
- 项目里已有 `trilingual-glossary-cn.md`，把核心术语的**日语说法**练到能开口。
- 即便英语面，能说几句日语 + 表达想融入日本团队，是明显加分。
- **投入**：取决于现状；至少花半天确认语言门槛，别到终面才发现。

### 6.6 领域知识（让你像"懂支付的人"而非"懂 Spring 的人"）

- **卡组织清结算**：issuer / acquirer / scheme（Visa/Mastercard/**JCB**）/ authorization vs clearing vs settlement 的真实流程。
- **日本支付生态**：PayPay 钱包与 PayPay Card 的关系、`PayPay あと払い`、日本信用卡/QR 支付习惯、收单与发卡格局。
- **怎么做**：读 PayPay 官方与**PayPay 技术博客 / 登壇资料**，了解他们的技术挑战与文化。
  面试时能提到"我看过你们关于 X 的分享"是强信号。
- **投入**：2–3 个晚上的阅读。

### 6.7 简历 / LinkedIn 与 JD 对齐

- 让简历里出现 JD 的关键词并**配证据**：event-driven、idempotency、distributed cache、
  high-traffic、Kafka、Spring Boot、transaction correctness。
- 用这个项目写 1–2 条 bullet：**动词 + 做了什么 + 量化/技术结果**，别堆名词。
- **投入**：半天。

### 6.8 模拟面试

- 找人做 1–2 次 mock（coding + 系统设计 + 行为各一次），或至少对镜头自录回放。
- **投入**：每次 1–1.5 小时，强烈推荐，性价比极高。

---

## 7. 建议的时间分配（若还有 ~4–6 周）

| 优先级 | 事项 | 大致投入 | 维度 |
| --- | --- | --- | --- |
| P0 | 算法/数据结构精刷（持续） | 每天 1–2 题 | 项目外，硬门槛 |
| P0 | Dockerfile + GitHub Actions CI | 1 天 | 项目内，补真实短板 |
| P0 | 6 张"王牌卡片" + 行为 STAR 故事 | 2–3 天 | 表达 |
| P1 | 一组真实压测数据 + 结果文档 | 1–2 天 | 项目内，补证据 |
| P1 | 系统设计 3–4 题 + DDIA 精读 | 持续 4–6 周 | 项目外 |
| P1 | 并发/分布式理论卡片 | 2–3 周穿插 | 项目外 |
| P1 | 确认日语门槛 + 核心术语开口 | 0.5–1 天起 | 项目外 |
| P2 | CFN/CDK 骨架（validate 通过） | 1–2 天 | 项目内 |
| P2 | PayPay 技术博客 + 领域知识 | 2–3 晚 | 项目外 |
| P2 | gRPC 小切片 | 0.5–1 天 | 项目内，可选 |
| —— | **暂停**：新增文档、新增领域、重构 job 引擎 | 0 | 见 §3/§5 |

---

## 8. 三次复盘补充（2026-07-12）：复习边界与数字清单

前七节回答的是"**别再生产什么**"。这一节回答一个新问题：**已经建成的东西，复习时边界收在哪里——
什么收窄、什么略过、什么学透、什么记数字。** 触发背景是 traffic/容量配置那轮打磨完成之后的全项目盘点。

先记一笔账：这一轮把 `high-traffic`（2,164 行）+ `production-runtime-sizing`（632 行）合并成
`traffic-rate-limiting-and-capacity-cn.md`（794 行），活跃文档**首次净减约 2,000 行**——§3.1 要的"压总量"
第一次真的发生了。配套的代码改动（503 语义、配置绑定测试、过载 trace）也是真工程而非文档代餐。
但 P0（Dockerfile / CI / 压测数字）仍然未动，§4 的判断继续有效。

### 8.1 有点过了的，收在哪里

| 过度点 | 现状 | 收在哪里 |
| --- | --- | --- |
| Statement 读缓存栈 | L1 + L2 + jitter + single-flight + loser wait + tombstone，共五层 | **收在 single-flight**。L1/L2 + jitter + single-flight 主动讲；tombstone 降级为被追问"缓存与写路径竞态"时的一句话（"还款后删缓存，慢的旧读可能迟到回填，tombstone 挡 10 秒"）。不再投入任何打磨 |
| 生产参考数字体系 | yml 注释和 traffic 文档里的"8 Pods × 25 连接、quota 1000/s ÷ 10 Pods"等 | **嘴上只留公式**（"每 Pod 连接 = RDS 总预算 ÷ Pod 数留余量"），具体数字一律说"起点要压测校准"。背诵 Pod 数在面试里是减分项——听起来像没压测过却在报数。文档里的数字保留，那是校准自己直觉用的 |
| Tomcat connector 四层语义 | 这轮修对了（threads / max-connections / accept-count / 业务背压） | 理解一次、留在文档里，**不进复习清单**。面试一般只考 threads vs connections 一层 |
| 文档总量 | 见 §3.1；本轮首次净减 | **冻结**。`traffic-rate-limiting-and-capacity-cn.md` 是最后一份大部头；复习入口收敛到本文 + `interview-readiness-review-cn.md`，其余文档只在被追问具体模块前翻对应章节 |

### 8.2 删除 / 合并候选（只动文档，代码零删除）

代码层面**没有需要删的**——每个模块都对着 JD 的一条线，删除反而破坏完整叙事。文档层面：

- `docs/archive/` 下两份原文档：已归档就从复习清单除名，不再翻。
- `caching-and-rate-limiting-cn.md`：限流部分与 traffic 文档大概率重叠，确认后把限流章节并入或删除，只留缓存部分。
- `spring-java-technical-learning-cn.md`：凡是"任何 Spring 教程都有"的通用内容删掉，只留和本项目代码绑定的部分。

### 8.3 保留但不学透（一句话定位即可）

| 内容 | 够用的深度 |
| --- | --- |
| MyBatis / Liquibase 细节 | "为什么选 MyBatis（SQL 可控、FOR UPDATE 显式）"+ 索引设计能讲；XML 语法不背 |
| JVM / GC | 线程清单（哪些池、各多少）能画；GC 一句话："G1 默认，大堆低停顿选 ZGC，先看分配速率再调" |
| AWS ECS 部署 | 架构图层面：Fargate/ALB/RDS/MSK 对应 compose 的哪块；CloudFormation 细节不碰 |
| Resilience4j 参数间约束 | slow-call 阈值 < read-timeout 这类关系理解过即可，现场能查 |
| Notification 三层组合细节 | 亮点句记住："throttle 不烧 attempts、不污染熔断统计"；内部实现不逐行讲 |
| 领域知识（lifecycle、日本还款习惯） | 加分项，读熟不背 |
| 503 handler、keep-alive/LB 边角 | 已并入"过载行为可解释"叙事，不单独复习 |

### 8.4 重点学透的五个主战场（被追问 3 层也不虚）

按 JD 权重排序，面试时主动往这五处引：

1. **Authorization 主链路的并发正确性**：idempotency key 语义（含 503 后复用同 key 重试）、
   `FOR UPDATE` 热点账户串行化、事务内外部调用为什么 bulkhead + fail-closed、over-approve 为什么不可能。必考。
2. **Outbox/Inbox + Kafka 全链**：at-least-once 每一环、双层幂等（inbox claim 与业务写同事务的反向事实）、
   retryable vs permanent 分类、DLT 运维闭环缺口**主动承认**——"知道 gap 在哪"比"没有 gap"更加分。
3. **Claim/lease/recover 统一模式**：三个家族一套话讲，落点是 liveness——"进程在任意时刻宕机，谁把任务捡回来"。
4. **限流三分类**：系统保护（token bucket/429）vs 业务风控（velocity/decline）vs provider quota（本地 RateLimiter）；
   为什么三套机制、故障时各自 fail-open/closed 的理由。
5. **过载行为叙事**：Hikari 耗尽 5 秒的 HTTP/Kafka 分叉 trace、30s vs 1s trade-off、"为什么不直接加线程加连接"。
   这是把"配置数字"升维成"系统行为"的部分，也是与其他候选人拉开差距的地方。

口座振替批处理维持既定边界：口头讲解，不写代码，归入第 1 点的问答准备。

### 8.5 数字清单：记什么、不记什么

原则：**每个机制记一个锚点数字，数字之间的比例关系比数字本身重要**；能从公式推出来的都不记。

**要记（约 12 个，成体系）：**

- Hikari **10** / Tomcat **80**，以及"百余个潜在执行单元共享 10 个连接"这个比例
- token bucket **20 burst / 10 rps**；velocity **3 次/60s**（区分：一个 429，一个业务 decline）
- external risk semaphore **4**（规则：必须 < Hikari 留余量）；正常延迟 100ms → **~40 calls/s**
- Kafka：**3** partitions、FixedBackOff **1s×2（总 3 次）**、**1 个** Notification DLT（按失败消费组路由）
- provider 每渠道 **20/s** → notification batch **40** 的对齐关系
- lease 的**相对关系**：outbox 30s < delayjob 60s < statement 300s，理由是单任务工作量递增（记理由，数字自然带出）
- 两道推导题：4 permits ÷ 0.7s ≈ **5.7/s**；1s 获取 ×3 + backoff ×2 ≈ **5s 进 DLT**

**不记（现场推，或明说"查配置"）：**

- accept-count 50 / max-connections 2048 / keep-alive 30s——记结论"connector 容量远大于 worker 数，真正的背压是 429/503"
- idle-timeout / max-lifetime 的毫秒值——记规则"max-lifetime 必须小于 DB/LB 强制断连"
- R4j retry 的 200/400ms、cache TTL 精确值——记量级（秒级 L1、分钟级 L2）
- 所有"生产大流量参考"数字——只记公式
- max-attempts 8 vs 10 这种差一档的值——统一说"个位数上限后转 DEAD/DLT 等人工"

> [!IMPORTANT]
> **三次复盘的一句话（2026-07-12）：**
> 模块广度不动、文档冻结、缓存栈和生产数字往回收半层；释放出来的复习时间，全部押在 §8.4 的五个主战场
> 和那张 5 秒过载 trace 上——它是整个项目"行为可解释"的浓缩样本。P0（Dockerfile / CI / 压测数字）依旧排在
> 一切复习动作之前，见 §4。

---

## 9. 一句话总结

> 项目层面：你已经**赢了"我能不能写正确的金融后端"这场仗**，现在要做的是**克制**——
> 停止扩产，补一块真实可运行的云/CI artifact 和一组压测数字，把核心叙事打磨成 6 张能抗压的卡片。
> 面试层面：胜负手已经转移到**项目之外**——算法、系统设计表达、并发/分布式原理、行为故事、日语和领域知识。
> 把原本要写第 27,000 行文档的时间，挪到这些维度上。

> [!CAUTION]
> **二次复盘的一句话（2026-06-30）：**
> 这一个月，文档从 27k 涨到 29k、三套 job 引擎又精修了一遍，而 Dockerfile / CI / 压测数字 / IaC **一个没动**。
> **第一版 review 的判断不需要修改，只需要被执行。** 现在最该删的不是某段文字，是"再开一份文档/再重构一处已饱和代码"这个动作本身。
> 下一个能改变面试结果的 commit，文件名应该叫 `Dockerfile` 或 `.github/workflows/ci.yml`，而不是又一个 `*-cn.md`。
