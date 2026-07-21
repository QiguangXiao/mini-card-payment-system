# 硬核面试全类型问答（Hardcore All-Round Interview QA）

本文档是 PayPay Card 面试的**全类型all-round**问答种子库question bank：覆盖专业基础（CS 基础 + 领域核心）、
通用后端工程、全新系统设计题、行为面试、团队/心理/人际、国际化与动机、逆质问questions for the interviewer。
与现有文档的分工：

- [`interview-qa-bank-cn.md`](interview-qa-bank-cn.md)：**项目技术深挖**（Q1–Q80 + 追问速查），
  技术轮被问到本项目细节时用它。
- **本文档**：项目之外的一切——CS 基础与领域背景、通用后端工程、独立系统设计题、
  软性问题。每类给少量高质量题，之后按类各自拓展。
- [`interview-readiness-review-cn.md`](interview-readiness-review-cn.md) 文末有 15 条英文背诵材料，
  本文的英文关键句与之互补，不重复。

编号约定（为后续分类拓展extension预留）：

| 前缀 | 类别 | 本版数量 |
| --- | --- | --- |
| D | 专业基础深水区（领域核心一问 + CS 基础五问） | D1–D6 |
| E | 通用后端技术深水区（不限于信用卡） | E1–E10 |
| C | 实务编码（live coding 高频手写题） | C1–C5 |
| P | 生产实战（debug/事故/数据订正/测试/发布） | P1–P6 |
| W | 工程日常（选型technology selection/git/设计文档/估算estimation/效率/AI/技术债） | W1–W8 |
| S | 系统设计（独立新题，非本项目走读；S3 迁移题附深挖专题） | S1–S3 |
| B | 行为面试（STAR） | B1–B5 |
| T | 团队 / 心理 / 人际 | T1–T6 |
| G | 国际化 / 动机 / 日本职场 | G1–G5 |
| R | 逆质问questions for the interviewer | R1–R8 |

> [!NOTE]
> 文中涉及的用户数、料率等外部数字标注为「锚点数字」，是截至撰写时的公开资料量级，
> **面试前一周用最新 IR / 官网再刷新一遍**。机制性内容（钱怎么流、法律怎么分工）不会过时。

---

## 1. 面试形态推演interview process walkthrough：每一轮在考什么

典型流程（PayPay 系技术岗常见形态，具体以 recruiter 说明为准）：

1. **Recruiter screen（30 min，英语或日语）**：动机、签证/语言、期望薪资、时间线。
   考察点是「这个人值不值得进流程」——G1/G2/G5 必须此时已经流畅。
2. **Coding / 技术一面**：算法或实务编码 + 项目深挖。实务编码常考「手写一个
   能用的组件」——C 节；项目部分用
   [`interview-qa-bank-cn.md`](interview-qa-bank-cn.md)；信用卡领域背景用 D1，
   网络/Linux/Spring/分布式理论等基础用 D2–D6，数据库/JVM/API/安全等工程题用 E 节，
   「讲讲你排查过的问题」类生产经验题用 P 节。
3. **System design 轮**：可能给本域题（授权系统 → qa bank 第 11 节）也可能给周边题
   （积分、对账reconciliation、迁移migration → 本文 S 节）。周边题更常见，因为面试官想看你**离开舒适区comfort zone**的表现。
4. **Hiring manager / behavioral 轮**：B 节 + T 节；经验向追问（事故怎么处理、
   怎么测试、怎么发布）常落回 P 节的流程版，开发流程与判断力类问题
   （怎么选型technology selection、怎么估算estimate、怎么用 AI）用 W 节。日本面试官会额外看 G5（转职理由的叙事一致性narrative consistency）。
5. **Culture / final 轮**：G 节全部 + R 节。这一轮挂人通常不是能力问题，
   是动机讲不圆、或逆质问questions for the interviewer暴露出「其实没研究过我们」。

语言策略：PayPay 集团工程组织国际化程度高，技术轮大概率英语；
HR 轮可能日语。**每个 G 类和 B 类问题都准备一个 30 秒英文短版**，
先用短版回答，面试官追问再展开——比一上来背三分钟长文自然得多。

---

## 2. 专业基础深水区deep dive（D1–D6）

D1 是唯一保留的信用卡领域题——四方模型 + issuer 四个核心过程，
这是讲清本项目所需的最低领域背景；更深的行业知识面试低频，不再展开
（领域全流程与 dispute/chargeback 等分支的概览见
[`credit-card-domain-cn.md`](credit-card-domain-cn.md)）。
D2–D6 是网络、Linux、IO 模型、Spring、分布式理论五个 CS 基础方向——
这些比行业知识高频得多，因为面试官每天用的就是它们。
与 E 节的分工：D 节偏**机制与原理**（协议、内核、框架、理论），
E 节偏**工程决策**（数据库、事务、JVM 调优、API、安全），
两节共用同一套回答纪律：60 秒结论先行，每层深入带一个反向事实。

### D1：用一笔交易讲清四方模型、issuer 靠什么赚钱，以及 issuer 内部的四个核心过程

**答：**

四方模型four-party model 的参与者：持卡人cardholder、加盟店merchant、
加盟店收单方acquirer、发卡行issuer，国际品牌（Visa/Mastercard 等 network）居中做
清分结算和规则制定。用一笔 10,000 円购物模拟钱流：

1. **授权时刻**：不动钱。issuer 只是冻结持卡人额度（本项目里的 reserved amount），
   给 network 返回 approve。
2. **清分clearing**：加盟店经 acquirer 向 network 提交请款记录，network 生成清分文件
   分发给 issuer——这对应本项目的 presentment posting。
3. **结算settlement**：issuer 把约 10,000 円 −（interchange fee）付给 network，
   network 转给 acquirer；acquirer 扣掉自己的手数料后把余款打给加盟店。
   加盟店最终到手大约 10,000 円 ×（1 − 加盟店手数料率）。
4. **回款**：issuer 垫付了钱，下个账单周期向持卡人请求还款——这就是为什么
   issuer 天然承担**信用风险**（持卡人不还）和**资金成本**（垫付期利息）。

issuer 的收入结构，按重要度：

1. **リボ・分割手数料（循环/分期利息）**：利润核心——一括払い不产生手数料，
   发卡行的持续收入流主要来自循环余额上年率 15% 上下的手数料。
2. **Interchange fee**：每笔消费从加盟店手数料中分到的部分。日本 2022 年起
   Visa/Mastercard 公开了标准 interchange 料率，平均约 2.3%（锚点数字reference figure，面试前核对）。
3. **年会费**、**キャッシング利息**、**加盟店手数料**（如果集团同时做 acquiring）。

**issuer 内部的四个核心过程**（上面钱流在发卡行系统里的镜像，
也是我项目的四条主链路，每条给一句「为什么 + 不这样会怎样」）：

1. **Authorization（授权）**：冻结额度，不动钱。同步低延迟路径
   （network 要求秒级应答），幂等idempotency靠 `Idempotency-Key` + 唯一约束，
   并发安全靠账户行锁row lock。不这样做：客户端重试重复占额度、并发刷卡超限额。
2. **Posting（清分入账）**：请款到达，把 hold 转成 posted 交易。
   异步批处理路径，幂等靠 `network_transaction_id`——用外部业务身份做键，
   因为这里的重复来自文件重放和上游重发，不是客户端重试。
   不把授权和入账分离：取消订单要走真实退款，授权与请款金额不一致
   的场景无处安放（详见追问 1）。
3. **Statement（出账）**：按账单周期把 posted 交易固化成账单，
   `statement_lines` 保存快照snapshot，账单稳定、可审计。不固化快照：
   事后退款调整会让用户每次看到的「同一期账单」数字不一样。
4. **Repayment（还款）**：还款核销账单、释放额度，statement 和
   credit account 在同一事务里更新。不同一事务：出现「账单已还清
   但额度没恢复」的中间态直接暴露给用户。

四个过程的逐题深挖在 [`interview-qa-bank-cn.md`](interview-qa-bank-cn.md)
（速Q1–速Q6 与 Q8–Q15），这里的版本用于面试开场介绍项目时的领域铺垫context-setting。
边界要主动交代：项目没有实现 network/acquirer 侧，
清分文件被抽象成 presentment API 调用。

**追问 1：为什么授权和清分要分成两步？一步扣钱不行吗？**

**追答：**

因为「持卡人愿意付」和「加盟店真的交付了」是两个时刻。酒店预授权、加油站、
EC 订单拆分发货，都存在授权金额 ≠ 最终请款金额、甚至授权后永不请款的情况。
一步扣钱会导致：取消订单要走真实退款（资金已动，成本高、周期长），
而两步模型里只需要释放 hold（资金未动，瞬时完成）。
架构上的对应：授权是**同步低延迟路径**（网络规定 issuer 要在秒级内应答），
清分是**异步批处理路径**（文件、可重放、幂等靠 `network_transaction_id`）。
两条路径的 SLA、幂等键idempotency key、失败处理完全不同，这正是我项目里把
authorization 和 presentment 分成两条链路的原因。

**追问 2：issuer 系统宕机了，POS 上的卡是不是就刷不了了？**

**追答：**

不一定，network 有 **stand-in processing（STIP）**：issuer host 不可达或超时时，
network 按 issuer 预先注册的规则（限额、卡状态黑名单blacklist等）**代替 issuer 作授权决定**，
事后把 advice 报文补送给 issuer。这对 issuer 系统设计有两个含义：

1. issuer 恢复后要消化 STIP 期间的 advice，把「network 替我批的交易」补记到额度上——
   本质是一次小型对账reconciliation，额度可能出现短暂超卖，系统必须容忍「reserved 之和暂时超过 limit」。
2. 这解释了为什么授权链路 p99 延迟是生死指标：慢到超时 = 触发 STIP = 风控决策权
   旁落给 network 的粗规则，欺诈损失由 issuer 自己承担。
   我项目里把外部风控调用放在账户行锁之前、并给它设 timeout + 降级graceful degradation策略，就是同一逻辑的缩影。

### D2：从服务 A 调服务 B 的一次 HTTPS 请求，网络层面发生了什么？超时和连接池要怎么配？

**答：**

**链路走读**（按时间序）：

1. **DNS 解析**：本地缓存 → 系统 resolver → 上游解析；K8s/服务发现service discovery环境里
   这一步换成集群 DNS 或注册中心。
2. **TCP 三次握手**（SYN → SYN-ACK → ACK）：为什么是三次——「我能发、
   你能收」两个方向各需一次确认，两次无法确认发起方的接收能力；
   同时随机初始序列号的交换防止旧连接的延迟报文串进新连接。成本：1 个 RTT。
3. **TLS 握手**：TLS 1.3 首次 1-RTT（1.2 要 2-RTT），交换密钥、验证证书链；
   会话恢复可以再省。ALPN 在这一步顺带协商是否走 HTTP/2。
4. **HTTP 层**：HTTP/1.1 一条连接同一时刻只能跑一个请求（应用层队头阻塞），
   所以客户端要开连接池跑并发；HTTP/2 单连接多路复用，流之间互不阻塞——
   但 TCP 层一个丢包仍会卡住全部流，这是 HTTP/3 换 QUIC/UDP 的动机。

把成本加总：一次冷连接 = DNS + 1 RTT（TCP）+ 1 RTT（TLS）+ 请求本身。
**这就是服务间调用必须开 keep-alive/连接池的原因**——省的不是带宽，
是每次 2–3 个 RTT 的建连延迟。反向事实：连接复用关掉后，5ms 的内网调用
变成 15–20ms，高 QPS 下还会制造大量 TIME_WAIT 和端口消耗（见追问 1）。

**超时是三个不是一个**：connect timeout（建连，内网可以百 ms 级）、
read timeout（等响应，按对方 SLA 定）、连接池获取 timeout（池耗尽时排队多久）。
配置纪律：**我的总预算必须小于上游对我的超时**——若重试次数 ×（单次超时）
超过上游的耐心，上游早已放弃，我还在重试，纯浪费还放大流量。
我项目里外部风控调用的 timeout + 降级就是这套预算法的应用。
反向事实：不设 read timeout 的 HTTP 客户端，对端挂死时线程无限期堆积，
一个慢下游拖光整个线程池——雪崩cascading failure的标准起点。

**追问 1：服务器上看到几万个 TIME_WAIT，要紧吗？**

**追答：**

先机制后判断：**主动关闭方**在四次挥手后进入 TIME_WAIT，停留 2MSL
（Linux 约 60 秒）。目的有二：最后一个 ACK 丢了能重发；保证这个四元组的
旧报文在网络里死透，不会串进复用同一四元组的新连接。
判断：每个 TIME_WAIT 只占少量内存，几万个本身不致命——它的真正价值是
**症状**：说明大量短连接在被反复建立和关闭，keep-alive 没生效。
真正的硬风险在**发起方**：本地临时端口只有几万个，每个被 TIME_WAIT
占 60 秒，高频短连接会把端口池耗尽、新连接发不出去。
修法从根上来：连接复用（池 + keep-alive），而不是打开 `tcp_tw_reuse`
掩盖症状；再记一条「谁主动关闭谁背 TIME_WAIT」——客户端和反向代理的
idle 超时要错开，别让两边同时关连接。

**追问 2：客户端超时报错了，服务端却把业务执行成功了——谁的 bug？怎么设计？**

**追答：**

谁的 bug 都不是，这是分布式的本性：**超时取消的是等待，不是执行**。
客户端超时后的正确认知是「结果未知」而不是「失败」——把未知当失败
直接重新下单，就是重复扣款事故。设计上的应对就是写接口的幂等键
（`Idempotency-Key`）+ 重试携带同一个键：上次真成功了返回原结果，
真失败了重新执行（qa bank Q16/Q27 是项目版）。把因果讲出来最加分：
**幂等不是锦上添花的优化，是「网络必然超时」这个物理事实逼出来的必选项。**

### D3：线上一台机器 load 飙高，你登录后前五分钟敲什么？load 高和 CPU 高是一回事吗？

**答：**

**概念先纠偏concept correction**：load average 统计的是「可运行 + 不可中断睡眠（D 状态，
通常在等 IO）」的任务数，所以 **load 高 ≠ CPU 高**——CPU 大片空闲、
一堆线程卡在磁盘或 NFS 上，load 照样爆表。这句答对，排查才有方向；
反向事实：只盯 CPU 使用率的人会对 IO 型故障得出「机器没问题」的结论。

**五分钟工具链**（按顺序）：

1. `top`：一屏看三件事——load 三个值的趋势（1/5/15 分钟对比，在恶化
   还是在恢复）、CPU 分解（`%us` 用户 / `%sy` 内核 / `%wa` IO 等待 /
   `%si` 软中断）、头部进程是谁。
2. **按 CPU 分解分型**：
   - `%us` 高：应用自己在烧 CPU——Java 进程进第 3 步；
   - `%sy` 高：系统调用/上下文切换风暴——`vmstat 1` 看 `cs` 列，
     常见根因root cause是锁竞争、线程数过多、频繁小 IO；
   - `%wa` 高：IO 瓶颈——`iostat -x 1` 看设备 `util` 和 `await`，
     `pidstat -d` 定位到进程；
   - `%si` 高：网络软中断——流量突增或小包风暴。
3. **Java 进程内定位**：`top -H -p <pid>` 找出烧 CPU 的线程 →
   线程号转十六进制 → 在 `jstack` 输出里按 `nid` 对号——那根线程的栈
   就是答案（GC 线程？死循环？正则回溯？疯狂序列化？）。
   这条「OS 线程 → JVM 栈」的映射链是 Java 工程师的基本功。
4. **顺带做基础检查sanity checks**：`df -h` 与 `df -i`（磁盘满、inode 满）、`free -m`
   （看 `available`；page cache 大不是泄漏——Linux 拿空闲内存做文件缓存
   是特性）、`ss -s`（连接状态分布）、`dmesg | tail`（OOM killer、硬件报错）。

**追问 1：JVM 堆用量正常，进程却被 OOM killer 杀了，为什么？**

**追答：**

OOM killer 看的是 cgroup/系统层面的**进程总内存**（RSS），不是 JVM 堆。
堆之外还有：Metaspace、线程栈（每根约 1MB，千线程即 1GB）、
DirectByteBuffer、JNI、glibc 碎片。容器场景的标准错误是把 `-Xmx` 设到
几乎等于 memory limit，堆外一分钱没留。对策：堆占 limit 的 60–75%，
或用 `MaxRAMPercentage` 让 JVM 感知容器配额；被杀后去 `dmesg`/journal 找
oom_kill 记录，内核会写明当时的内存账单——**取证入口在内核日志，
不在应用日志**。堆外具体怎么追是 E5 追问 2 的 NMT 工具链，两题互补。

**追问 2：文件描述符耗尽是什么现象？怎么防？**

**追答：**

现象很有欺骗性：**进程活着、老连接还能用，但一切新连接/新文件失败**
（`Too many open files`），健康检查若复用旧连接甚至还是绿的——「半死」状态。
fd 的消费者：socket、文件、pipe；泄漏源头常见是 HTTP 响应体没关闭、
连接池配置错导致连接只借不还。排查：`ls /proc/<pid>/fd | wc -l` 对比
`ulimit -n`，再看 fd 类型分布（socket 占绝对多数 = 连接泄漏）。
防御两条：fd 使用率进常态监控continuous monitoring（70% 报警，等打满就是事故了）；
资源关闭全部走 try-with-resources 的编码纪律，review 时当硬规则查。

### D4：BIO、NIO、IO 多路复用是什么关系？为什么 Redis 单线程能扛十万 QPS，Tomcat 却要几百个线程？

**答：**

**概念阶梯**（每层解决上一层的问题）：

1. **阻塞 IO（BIO）**：一连接一线程，线程睡在 `read` 上等数据。
   万连接 = 万线程 = 内存 + 上下文切换双爆炸。
2. **非阻塞 IO**：`read` 没数据立刻返回——但要自己轮询所有连接，CPU 空转。
3. **IO 多路复用**：把「等哪个连接就绪」外包给内核——一个线程
   `epoll_wait` 看住几万个 fd，就绪了才处理。epoll 强于老 select/poll
   的两点：fd 集合注册一次由内核维护（不用每次调用全量拷贝），
   返回的是就绪列表（复杂度随就绪数而不是总连接数增长）。
   LT/ET 一句话：ET 通知次数少，但要求一次把数据读干净。

**Redis 单线程够用的真相**：命令是纯内存操作、微秒级——瓶颈不在 CPU
而在网络 IO，单线程 + epoll 事件循环就能喂饱；还白赚三样：免锁、
免上下文切换、命令天然原子（`INCR` 不需要锁）。Redis 6.0 的「多线程」
只把网络读写并行化，**命令执行仍是单线程**——无锁模型这个核心资产没让步。
反向事实（生产纪律的来源）：单线程意味着一个慢命令阻塞所有人——
`KEYS *`、大 key 的同步 `DEL`、大集合全量读，都是拿全实例延迟陪葬；
所以生产禁 `KEYS`、大 key 要拆、删除用 `UNLINK` 异步化。

**Tomcat 为什么要几百个线程**：接入层早就是 NIO（少量 acceptor/poller
线程管几千连接），但业务逻辑要做**阻塞的 DB/HTTP 调用**，毫秒到百毫秒级，
线程在这段时间只能干等——并发能力 = 线程数。这就是两个模型的分水岭watershed：
**请求处理是否阻塞**决定要不要靠线程数堆并发。出路两条：虚拟线程把
阻塞变便宜（E6），或响应式把阻塞消掉（代价是编程模型复杂化）。
Netty 补一句：EventLoop 组 + pipeline，铁律non-negotiable rule是慢操作必须丢出 EventLoop
线程池之外执行，否则同组所有连接一起卡——和 Redis 慢命令是同一个病。

**追问：Kafka 的高吞吐和这些 IO 模型有什么关系？**

**追答：**

Kafka 是「顺序 IO + 零拷贝 + 批量化」的教科书案例：① 分区日志只追加，
把随机写变成顺序写，磁盘顺序吞吐接近内存量级order of magnitude；② 消费路径用 `sendfile`
零拷贝——数据从 page cache 直接送网卡，省掉「内核 → 用户 → 内核」的
两次拷贝和两次态切换；③ 生产端攒批 + 压缩，把每次网络和磁盘操作摊到
几百条消息上。可以收束成一句：**高性能中间件翻来覆去就四件事——
少拷贝、少切换、顺序 IO、批量化**。能把具体组件归纳到这四件事，
比背它的配置参数更能证明理解。

### D5：Spring 的 @Transactional 在哪些情况下会失效？从 AOP 代理机制讲清楚为什么

**答：**

**原理先行，失效场景全是原理的推论**：`@Transactional` 不是魔法，是 AOP
代理——Spring 给 bean 生成代理对象（有接口时 JDK 动态代理，无接口时
CGLIB 子类），事务的 begin/commit/rollback 织在代理方法的外圈。
所以全部失效场景归结成一句话：**调用没有经过代理，或者异常没有到达代理**。

失效清单（每条挂上原因）：

1. **自调用（最高频）**：`this.methodB()` 走的是原始对象不是代理，
   B 上的 `@Transactional` 完全不生效。解法：把 B 拆到另一个 bean，
   或注入自身的代理。（qa bank Q22 追问的 self-invocation 是项目版。）
2. **方法不是 public，或是 final/static**：CGLIB 靠子类覆写织入，
   覆写不了就织不进去。
3. **异常类型不对**：默认只对 `RuntimeException` 和 `Error` 回滚rollback，
   **checked 异常不回滚**——需要 `rollbackFor = Exception.class`。
   review 别人代码时的高频 bug。
4. **异常被吞**：方法内 catch 住没抛出，代理看不到异常，照常提交。
5. **跨线程**：事务上下文绑在 ThreadLocal，`@Async` 或自开的线程里
   没有外层事务——异步逻辑要有自己的事务边界。
6. **传播行为误配**：内层 `REQUIRES_NEW` 失败、外层 catch 后继续提交，
   出现「一半提交一半回滚」——这是否符合业务预期必须被显式想过，
   而不是被默认行为决定。

**顺带一句 bean 生命周期**（解释代理从哪来）：实例化 → 属性注入 →
Aware 回调 → BeanPostProcessor 前置 → init → 后置处理器——AOP 代理就是
在最后一步把原始对象替换掉的；三级缓存解决的也是「循环依赖时如何提前
暴露一个将来会被代理的引用」这个问题。

**追问 1：REQUIRES_NEW 什么时候真的需要？有什么坑？**

**追答：**

正当场景一句话：**无论外层成败都必须留下的记录**——审计日志、失败流水、
人工干预队列：外层业务回滚了，这条记录也要活下来，所以必须在独立事务里
提交。两个坑都和「同一线程同时有两个事务」有关：① **自死锁deadlock**——外层
事务持有某行锁，内层新事务再去锁同一行，自己等自己直到锁超时；
② **连接占用翻倍**——外层挂起的连接 + 内层新开的连接，池小并发高时
出现「每个线程都拿着一个连接、都在等第二个」的池死锁。
纪律：REQUIRES_NEW 要短、要少、绝不碰外层已锁的行。

**追问 2：Spring 单例 bean 被并发调用，为什么通常不用担心线程安全？**

**追答：**

因为约定 bean **无状态**：字段里只有注入的依赖（它们同样是无状态单例），
请求级数据全部在方法参数和局部变量里——栈上数据天然线程隔离。
一旦有人往 bean 里放了可变实例字段就是并发 bug，经典事故是
`SimpleDateFormat` 成员变量（内部共享缓冲区，并发解析出乱值）。
需要状态的出路：局部变量、不可变对象、或 ThreadLocal——线程池环境
用完必须 remove，否则值串进下一个请求（衔接 E5 的泄漏清单）。

### D6：CAP 经常被讲错——给出你的版本，并解释为什么分布式锁distributed lock需要 fencing token

**答：**

**CAP 纠偏版**：P（分区容忍）不是「三选二」里的可选项——网络分区是
物理现实，必然发生。真正的选择只存在于**分区发生的时刻**：选 C
（拒绝部分请求，保证读到的都一致）还是选 A（继续服务，容忍新旧不一致）。
而平时没分区的日子里，取舍trade-off其实是**延迟 vs 一致**（同步复制慢而一致，
异步复制快但有落后窗口）——这是 PACELC 对 CAP 的补全。
顺带纠偏一句：「MySQL 是 CA」这类说法没有意义，CAP 讨论的对象是
带复制的分布式系统，单机谈不上 P。

**一致性是光谱不是开关**：线性一致（整个系统表现得像单机）→
因果/会话一致（至少读到自己写的）→ 最终一致eventual consistency。实现上对应复制策略：
quorum（W + R > N）可到强读写，纯异步复制只能最终一致。
工程要点：**一致性是按数据分级购买的**——我项目里额度与交易走
单点强一致strong consistency（主库 + 行锁），通知与读模型走最终一致（事件 + 重试收敛converge），
全系统买同一档既贵又慢。

**共识consensus为什么难**：异步网络里无法区分「节点死了」和「网络慢」，
这是一切难题的根。朴素的 leader election 会脑裂split-brain：两个自认 leader 各写各的。
Raft 的直觉intuition两句话：任期号单调递增 + **多数派**投票才能当选；
日志复制到多数派才算提交——任何两个多数派必有交集，所以旧 leader
凑不出多数派，新 leader 必然握有全部已提交日志。

**Fencing token（把理论落到事故）**：即使锁服务本身完美，客户端拿到锁后
也可能 GC 停顿或网络卡住，锁过期被别人接走，而它醒来**不知道自己已经
没锁了**，继续写——互斥被破坏，且锁服务无法阻止。解法：锁服务发放
**单调递增的 token**，资源侧（DB/存储）拒绝 token 小于已见最大值的写。
结论一句话：**互斥的最终防线必须在资源侧，不能只在锁侧**——
这正是我项目 money path 用 DB 行锁和 DB lease（防线就在数据所在处）、
不用 Redis 锁的理论根据（[`distributed-lock-cn.md`](distributed-lock-cn.md)
有完整展开）。

**追问：你的项目里哪些部分是 CP，哪些是 AP？**

**追答：**

额度与交易是 CP：授权的额度检查必须读到最新值，DB 主库不可用时
宁可授权失败，也不能凭旧数据放行——分区时选一致、弃可用。
通知、积分类衍生、读模型缓存是 AP：组件故障不阻塞主链路，
靠重试、outbox 重投和对账事后收敛converge——分区时选可用、容忍暂时不一致。
真正的设计动作不是「系统级站队 CP 或 AP」，而是**给每类数据标注
它购买的一致性档位、以及故障时牺牲什么**——把问题从二选一重构成
分级采购，通常就是这题的最佳答案。

---

## 3. 通用后端技术深水区deep dive（E1–E10）

这一节是**不依赖本项目也能答**的通用后端题——技术面里穿插在项目深挖之间出现，
往往一句「顺便问一下，索引为什么用 B+ 树」就开始了。选题标准：Java 后端面试
高频 + 能分层答出深度 + 和 qa bank 的项目化版本互补（qa bank 里同主题的题
是「在我项目里怎么做」，这里是「脱离项目的机制本身」，两边互为弹药ammunition）。

回答共同纪律：**60 秒结论先行**，面试官想深挖自然会追问；每层深入都带一个
「如果不这样会怎样」的反向事实，证明学的是因果不是名词。

### E1：MySQL 为什么用 B+ 树做索引？什么时候索引会失效？

**答：**

**为什么是 B+ 树**——从磁盘物理特性倒推：磁盘按页读写（InnoDB 页 16KB），
索引结构的目标是**最小化页读取次数**。B+ 树把树压得极矮：非叶节点只存键 + 指针
（bigint 键约 8B + 指针约 6B ≈ 14B），一页能放 1,100+ 个分支，三层就能索引
1,100 × 1,100 × 每叶百余行 ≈ 上亿行——**任何一行三四次页读可达**，
且非叶层基本常驻 buffer pool，实际磁盘 IO 常常只有一次。

对比其他结构（面试官爱听的部分）：

1. **哈希**：等值 O(1)，但不支持范围查询和有序遍历——`WHERE created_at > ?`
   和 `ORDER BY` 全废，所以只当内存补充（InnoDB 的 adaptive hash index）。
2. **二叉/红黑树**：树高 log₂(N)，亿行 ≈ 27 层 = 27 次页读，磁盘上不可用——
   树高问题的本质是每个节点扇出fan-out太小，浪费了「一次 IO 拿回 16KB」的带宽。
3. **LSM 树**（RocksDB/Cassandra）：写优化（顺序写 memtable + compaction），
   代价是读放大read amplification和 compaction 抖动latency jitter。InnoDB 选 B+ 树是读写平衡 + 事务集成
   （聚簇索引和 undo/redo 的配合）的综合结果，不是谁绝对更好。

**聚簇索引与回表**：InnoDB 主键索引的叶子**就是行数据**；二级索引叶子存主键，
查完二级索引还要拿主键去聚簇索引再查一次（回表）。covering index（索引列
覆盖查询所需全部列）省掉回表——`EXPLAIN` 的 `Using index` 就是它。

**失效场景**（给具体例子，不背名词）：

1. 对索引列施加函数或运算：`WHERE DATE(created_at) = '2026-07-21'` →
   改写成范围 `created_at >= ... AND < ...`。
2. 隐式类型转换：`phone` 是 varchar 而 `WHERE phone = 13800000000`——MySQL 会把
   **列**转成数字比较，索引全废还可能出错；反过来数字列配字符串条件则没事。
3. 前导通配 `LIKE '%abc'`、联合索引不满足最左前缀、低选择性列上优化器主动放弃。
4. 读 `EXPLAIN` 的顺序：`type`（`ref`/`range` 可接受，`ALL` 报警）→ `rows`
   估算量 → `Extra`（`Using filesort`/`Using temporary` 是排序/分组没吃到索引）。

**追问 1：为什么不建议用随机 UUID 做主键？**

**追答：**

两笔账：① **写入账**——聚簇索引按主键物理有序，随机主键让每次插入落在
随机页上：页分裂频繁、buffer pool 局部性被打碎、写放大write amplification明显；自增或时间有序 ID
让插入永远追加在最右叶，顺序写。② **空间账**——所有二级索引的叶子都存主键，
16 字节 UUID 对比 8 字节 bigint，每个二级索引都膨胀一倍。
需要全局唯一又要有序时用 snowflake、ULID 或 UUIDv7（时间有序版 UUID）。
我项目里的做法是两层分离：自增主键管物理存储，业务身份（idempotency key、
`network_transaction_id`）走独立唯一索引——物理效率和业务语义各管各的。

**追问 2：一条 SQL 突然变慢，EXPLAIN 发现优化器选了错误的索引，怎么处理？**

**追答：**

短期止血mitigation和长期根治permanent fix分开。止血：`FORCE INDEX` / optimizer hint 钉住正确索引，
先恢复服务。根因root cause通常两类：统计信息过期（大量写入后基数cardinality估算失真，
`ANALYZE TABLE` 重采样）或数据倾斜data skew（某个值占了半张表，均匀性假设assumption失效）。
长期：要么改索引设计让正确选择变得「明显」（提高区分度、建覆盖索引），
要么改写 SQL 缩小优化器的自由度。工程教训是：优化器是基于统计的猜测者，
关键路径的执行计划要**监控**（慢查询日志 + 发布前后计划对比），
而不是相信它永远猜对。

### E2：讲讲 MVCC——InnoDB 在 Repeatable Read 下怎么做到「读不加锁」？

**答：**

**机制三件套**：

1. 每行两个隐藏列：`DB_TRX_ID`（最后修改它的事务 ID）、`DB_ROLL_PTR`
（指向 undo log 里的旧版本，串成**版本链version chain**）。
2. 快照snapshot读（普通 `SELECT`）开始时生成 **Read View**：记录当时活跃（未提交）
   事务集合和高低水位high/low watermarks。读每行时沿版本链回溯，找到第一个「对我可见」的版本
   （提交于我的视图建立之前、且不在活跃集合里）。
3. RR 和 RC 的全部区别就一句话：**RR 整个事务用第一次读时的那一个 Read View，
   RC 每条语句新建一个**。所以 RR 里反复读结果稳定，RC 里能看到别人新提交的。

**反向事实**：没有 MVCC 的世界是「读写互斥」——写事务提交前所有读者排队
（早期数据库的悲观读锁模式），OLTP 吞吐直接崩。MVCC 用 undo 空间换并发，
是「读多写少」负载的巨大胜利。

**必须主动讲的坑——两种读的拼合**：RR「防幻读」是拼出来的：
快照读靠 MVCC（看不见新插入的行），**当前读**（`UPDATE`/`DELETE`/
`SELECT ... FOR UPDATE`）读的是最新版本并加 next-key lock（行锁row lock+ 间隙锁）
挡住新插入。混用两种读会出现诡异现象：快照 `SELECT` 数出 10 行，
紧接着 `UPDATE` 却影响了 11 行（当前读看见了快照看不见的新行）。
所以我项目里钱路径全部用显式 `FOR UPDATE` 当前读——隔离级别是默认行为，
关键路径要显式声明「我要读最新并锁住」的意图，不赌隔离级别的语义细节。

**追问 1：长事务为什么被 DBA 视为头号敌人？**

**追答：**

三宗罪：① 它的 Read View 钉住了 undo 版本链——purge 线程不能清理
它可能还要读的历史版本，undo 表空间膨胀；② 别的查询沿版本链回溯变长，
出现「一个不提交的事务让全库查询变慢」的经典事故；③ 锁持有时间拉长，
死锁deadlock与等待概率上升。防御：监控 `information_schema.innodb_trx` 里的事务时长
并报警；应用侧把事务边界收到最小——我项目把外部 HTTP 调用挡在事务外/
事务前，就是防「事务里做慢事」这个根源。

**追问 2：生产上 RC 和 RR 怎么选？**

**追答：**

不少互联网公司默认 RC：间隙锁大幅减少 → 死锁率降、写并发升，代价是
binlog 必须 ROW 格式（RC 下 statement 复制不安全）。RR 的价值场景是
「一个事务内多次读必须自洽」——对账reconciliation、批处理里先统计后处理的逻辑。
技术选型technology selection的正确表述不是「哪个级别好」，而是：**默认级别管普通路径，
关键路径用显式锁把语义钉死**——这样即使团队日后把默认从 RR 调到 RC，
钱路径的正确性也不随之漂移drift。这是我在项目里坚持显式 `FOR UPDATE` 的原因。

### E3：分布式事务怎么选型？2PC、TCC、Saga、Outbox 各自的位置在哪里？

**答：**

先亮原则：**第一方案永远是重新划边界，让它不需要分布式事务**——
把必须原子的数据放进同一个库的同一个事务（我项目把「额度扣减 + 授权记录 +
outbox 行」放同库同事务就是这个选择）。划不开时再进选型矩阵：

| 方案 | 一致性 | 代价 | 适用 |
| --- | --- | --- | --- |
| 2PC/XA | 强一致strong consistency| 同步阻塞、协调者故障时资源悬挂、吞吐差 | 极少：同厂商资源、低并发管理操作 |
| TCC | 业务层强一致 | 每个参与方写 Try/Confirm/Cancel 三接口，侵入大 | 资金类短事务，参与方可控 |
| Saga | 最终一致eventual consistency| 补偿compensation逻辑 + 隔离性丢失 | 跨服务长流程（开户、订单履约） |
| Outbox + 事件 | 最终一致 | 消费侧要幂等idempotency| 「下游必须发生但不必立即」 |

**TCC 三大坑**（说得出这三个词才算真了解）：**空回滚empty rollback**（Cancel 先于 Try 到达，
要能对「没 Try 过」的请求安全地 Cancel）、**悬挂hanging transaction**（Cancel 之后迟到late-arriving的 Try
又把资源冻住，要拿事务状态表挡住迟到的 Try）、**幂等**（三个接口都会被重试）。
——本质上 TCC 是把数据库的 prepare/commit/rollback 搬到业务层自己实现，
所以数据库要处理的异常时序它一个不少。

**Saga 的核心认知**：补偿不是回滚。中间状态已经对外可见过（隔离性丢失），
补偿是**新的业务动作**——取消支付的补偿是「退款」而不是「删除支付记录」；
账务上是反向冲正分录而不是抹掉原分录。编排（orchestration，中心状态机）
与协同（choreography，事件接力）之争：超过三步的流程用编排，
否则流程逻辑散落在各服务的事件处理器里，半年后没人能说清全貌。

**追问 1：Saga 走到第三步失败，补偿第二步时又失败了，怎么办？**

**追答：**

设计时就要保证补偿是「**最终必然成功**」的方向：补偿动作要选那种只会
暂时失败（网络、重启）不会业务性拒绝的操作——给账户加回额度可以无限重试，
从账户扣钱可能余额不足，所以正向做「扣」、补偿做「加」的方向设计不是巧合。
补偿接口幂等 + 持久化重试（DelayJob 式），重试穷尽进人工队列 + 报警。
如果某个补偿在业务上真的可能被拒绝，说明流程顺序设计错了——
把最可能失败的一步挪到最前面，是 Saga 排序的第一原则。

**追问 2：所以跨服务的强一致就是做不到了？**

**追答：**

工程答案：单资源强一致（本地事务）+ 跨资源最终一致（事件/Saga）+
**对账兜底fallback**，是资金系统的现实三层。真出现「跨资源必须强一致」的需求，
我的第一反应不是上 XA，而是质疑边界：这两份数据为什么在两个资源里？
多数时候答案是历史拆分错了，合并回同库比引入分布式事务协议便宜且可靠。
对账是必答的收尾：最终一致的「最终」需要被验证，差异检测 + 修复通道
是这套体系的一部分而不是可选项（衔接 S2）。

### E4：什么时候该分库分表？shard key 怎么选？扩容怎么办？

**答：**

**先排除伪需求**：单表行数本身不是理由——几千万行配上好索引和合理查询
毫无压力。先用完便宜手段：读写分离read-write splitting、冷数据归档、缓存、查询优化。
真信号只有三个：**写吞吐**逼近单机上限、**存储**超单机容量、
**运维操作**（DDL、备份恢复）时长不可接受。

**shard key 选择**（本题真正的考点）：按**最高频且要求事务性**的访问维度dimension切。
发卡系统就是 `account_id`——授权、入账、账单、还款全是账户维度，
切在这里保证「单账户的所有操作落单分片sharding」，**本地事务和行锁的语义完整保留**
（我项目所有正确性机制在分片后依然成立，只是作用域变成分片内）。
被牺牲的维度（比如按商户查交易）走异构副本：CDC 同步到 ES/读模型库去查，
接受秒级延迟——**分片的实质是选择哪些查询保持廉价、哪些查询变贵**。

**路由routing与扩容scale-out**：不要直接 `hash(key) % N`——N 一变全量重分布。
用**逻辑槽位**：先 hash 到固定的 1024 个 slot，slot → 物理库的映射表可调整，
扩容scale-out = 迁移部分 slot 的数据 + 改映射，影响面blast radius可控。而「迁移部分 slot」
本身就是一次小型 S3：双写dual write/CDC + 校验 + 切读 + 切写，同一套方法。

**配套问题**：分布式 ID（snowflake：机器位 + 时间戳 + 序列，注意时钟回拨clock rollback
的处理——等待或备用位；或号段模式批发 ID）；跨分片分页用 cursor 归并；
`COUNT`/聚合走离线或计数表。

**追问 1：热点hotspot账户（比如一个巨型法人账户）把单分片打爆怎么办？**

**追答：**

分片解决的是**均匀负载**的水平扩展horizontal scaling，热点是另一个问题，硬件加不动
（热点在一个分片里再怎么加机器也是那一片热）。分层处理：
① 识别——按 key 的流量 top-N 监控常态化；② 隔离——热点账户路由到
专属分片，别让它产生 noisy-neighbor impact；③ 拆解decompose——把热点账户在业务上拆成子账户
（分部门额度池），各子账户独立行锁，汇总视图异步聚合——这一步动的是
**业务模型**而不是基础设施，也是为什么热点问题最终要和业务一起解
（qa bank Q52 有本项目版本）。

**追问 2：分片之后全局唯一约束怎么保证？比如幂等键idempotency key的唯一性。**

**追答：**

分两种情况。幂等键如果**包含 shard key**（我项目的 idempotency 记录
天然挂在账户下），唯一约束在分片内就足够——同 key 的请求永远路由到
同一分片，分片内唯一 = 全局唯一，这是 shard key 选对的隐藏红利。
真正的全局唯一（比如跨账户的卡号唯一）走两条路：独立的全局唯一性服务
（一张不分片的小表专管发号/查重，它只存键不存业务数据，容量压力小），
或者在 ID 生成阶段保证唯一（发号器发的号天然不重）。要避免的反模式是
「先查全部分片再插入」——check-then-insert 在分片场景下连锁都没得加。

### E5：线上 JVM 服务 Full GC 频繁甚至 OOM，你的排查步骤是什么？

**答：**

**第一步永远是看曲线形状，先分型classify再抓证据evidence**——三种形状三种病：

1. **锯齿缓慢上移，最后 OOM**：泄漏。每次 GC 后的「谷底」在爬升，
   说明有对象活过了每一轮回收。
2. **瞬间打满**：大对象或流量突变。查这一刻的请求日志——十有八九是
   一条没分页的大结果集查询（`SELECT` 全表进内存）或重试风暴。
3. **Full GC 频繁但不 OOM**：老年代压力（晋升过快、堆分代比例不当）
   或元空间问题（动态类生成泄漏）。

**取证工具链**（按侵入性从低到高）：监控面板/GC 日志 → `jstat -gcutil` 看
各代占用与回收频率 → `jcmd GC.class_histogram` 轻量看对象分布 →
heap dump 上 MAT 看**支配树**（dominator tree）找持有者。
关键纪律：`-XX:+HeapDumpOnOutOfMemoryError` 必须**提前**配好——
OOM 现场的 dump 是最值钱的证据，事后 `jmap` 补拍会 STW 且现场已被重启破坏。

**高频根因清单**：static 集合当缓存且无界（用 Caffeine 带上限替换）、
ThreadLocal 用完不 remove（线程池线程复用导致值滞留）、
未分页的大查询、日志框架异步队列无界堆积、Metaspace 里反射/代理类膨胀。

**止血mitigation与根治permanent fix分开**：滚动重启是 mitigation 不是修复，重启前必须先留 dump，
否则唯一的证据跟着进程一起消失，下周同一时间再见。

**追问 1：G1 和 ZGC 怎么选？授权这种低延迟链路要不要上 ZGC？**

**追答：**

选型维度selection criteria是停顿目标：G1 是吞吐throughput与停顿的平衡者（百毫秒级停顿、默认选择、
调优资料多）；ZGC 停顿亚毫秒且与堆大小无关，JDK 17+ 生产可用，
代价是吞吐略降、内存占用略高。授权链路 p99 敏感，ZGC 值得评估——
但**顺序不能反**：先用 GC 日志证明 p99 尖刺latency spike确实来自 GC 停顿，
而不是锁等待、连接池排队或下游慢（我项目的排查经验里，后三者更常见）。
拿数据说话：对比 GC 停顿分布和请求延迟尖刺latency spike的时间相关性，对上了再换收集器，
否则换了也白换——「数据先于选型」这句话面试官爱听，因为反的人太多。

**追问 2：GC 完全正常，但容器 RSS 持续上涨最后被 OOMKilled，怎么查？**

**追答：**

这是「JVM 内存 ≠ 堆」的经典题。堆外嫌疑人：DirectByteBuffer（Netty/NIO）、
JNI/native 库、线程数增长（每线程默认 1MB 栈）、glibc malloc 的 arena 碎片
（容器里常见，换 jemalloc 或调 `MALLOC_ARENA_MAX` 缓解）、Metaspace。
工具：Native Memory Tracking（`-XX:NativeMemoryTracking` + `jcmd VM.native_memory`）
先看 JVM 自己账本上的堆外分类，账本对不上 RSS 的部分再用 pmap/jemalloc profile
追。容器场景还要检查 `-Xmx` 与 memory limit 的关系——堆只占 limit 的一部分，
给堆外留的余量headroom不足是被 OOMKilled 的第一大原因。

### E6：Java 内存模型memory model（JMM）里 volatile 到底解决什么？虚拟线程又改变了什么？

**答：**

**JMM 部分**——三个概念一条线：可见性问题来自 CPU 缓存与编译器/处理器
重排序；JMM 用 **happens-before** 规则定义「哪些写对哪些读可见」；
`volatile`、`synchronized`、`final` 是建立 happens-before 边的工具。

- `volatile` = 可见性 + 禁止重排序，**不含原子性**——`count++` 是
  读-改-写三步，volatile 救不了，要 `AtomicLong` 或锁。
- 经典案例（值得白板）：双检锁单例为什么必须 volatile——
  `instance = new Foo()` 会被重排成「分配内存 → 赋引用 → 构造」，
  另一个线程可能拿到**未构造完**的对象；volatile 禁止这个重排。
- `ConcurrentHashMap`（JDK 8+）：CAS 插入空桶 + 桶头节点 synchronized，
  锁粒度到桶级。坑：它保证单操作线程安全，**复合操作不安全**——
  `get` 判空再 `put` 依然是 check-then-act 竞态race condition，要用 `computeIfAbsent`。
  这和我项目里「check-then-insert 要靠唯一约束」是同一个竞态race condition在不同层的投影。

**虚拟线程部分**（JDK 21，JD 写着 Java 11/17/21，这题大概率被问）：

虚拟线程让阻塞变便宜——阻塞时让出 carrier 线程，百万级并发线程成为可能，
thread-per-request 模型复活，异步回调式代码可以回归直白的同步写法。
但要说清**它不改变什么**：

1. **下游容量downstream capacity不变**：DB 连接池 50 个还是 50 个，行锁串行化还在。
   虚拟线程解决的是「线程稀缺」，不是「下游稀缺」——瓶颈在下游时，
   它只是让排队的成本更低，吞吐throughput一分不涨。
2. **限流rate limiting语义变化**：以前线程池大小顺便当了并发上限，虚拟线程下这个
   隐式并发上限implicit concurrency cap消失，要用显式 Semaphore 限制对每个下游的并发。
3. **Pinning 坑**：JDK 21 里 `synchronized` 块内阻塞会把 carrier 钉住
   （后续版本已改善），高频路径的锁要换 `ReentrantLock`；
   ThreadLocal 在百万线程下的内存也要重新审视。

**追问：你的项目升到 JDK 21 开虚拟线程，收益benefit和风险risk各是什么？**

**追答：**

收益集中在阻塞 IO 密集的环节：授权链路里的外部风控 HTTP 调用、
notification 的渠道调用——这些等待不再占据平台线程，同等内存下
可承载的并发请求数上升，线程池 sizing 从「按等待时间放大」简化为
「按下游容量限流」。风险与不变项：① DB 连接池仍是硬闸门，授权吞吐
瓶颈在行锁和连接数，虚拟线程不改变这个结论（qa bank Q50 的公式依然成立）；
② 要先扫一遍代码里的 `synchronized` 热点路径排查 pinning，压测确认；
③ Kafka consumer、@Scheduled 这些本来就少量线程的组件没必要动。
一句话总结：虚拟线程是把「线程」从稀缺资源变成廉价资源的运行时改进，
系统的容量模型capacity model依然由最稀缺的下游资源决定。

### E7：API 怎么演进evolve才不破坏调用方？分页为什么推荐 cursor 而不是 offset？

**答：**

**兼容性铁律non-negotiable compatibility rule**先说：加可选字段=安全；删字段、改类型、改语义=破坏。
配套两个纪律：consumer 侧**容忍读**（tolerant reader，忽略不认识的字段，
反序列化配置里显式允许未知字段）；provider 侧破坏性变更走
「新旧并行 → 迁移期 → 观测旧版本流量归零 → 下线」，**靠流量数据下线,
不靠邮件通知下线**。版本载体（URL `/v1` vs header）是次要问题，
主要问题是组织纪律：契约先行（OpenAPI/proto 进仓库），CI 里跑
breaking-change 检测，把兼容性从「人的自觉」变成「机器的门禁」。

**分页**：offset 分页两宗罪——① 深分页 O(offset)：`LIMIT 100000, 20`
要扫过前十万行再丢弃；② 翻页期间有插入/删除时**漂移**：第 5 页和第 6 页
重复或跳过记录，对账单、交易流水这种钱相关列表是正确性问题correctness issue不是体验问题。
cursor（keyset）分页：`WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC,
id DESC LIMIT 20`，游标是上一页最后一行的排序键——每页 O(page size)、
结果稳定。代价：不能跳到第 N 页、排序键必须唯一且单调（所以要拼上 id 兜底）。
交易列表天然适合 cursor，这也是各支付类 App 流水页都是无限下拉的原因。

**错误与批量设计**：错误体结构化（机器可读的 `code` + 人读的 `message` +
`trace_id` 贯穿排障）；批量接口要定义**部分失败**语义——逐项返回结果,
而不是一损俱损或静默吞掉；写操作幂等键（qa bank Q16 的通用版）。

**追问：内部服务间通信，REST 和 gRPC 怎么选？**

**追答：**

gRPC 的强项：proto 强契约 + 代码生成（接口不一致在编译期爆炸而不是
运行期）、HTTP/2 多路复用、双向流、性能；REST 的强项：调试友好
（curl 就能打）、网关gateway/缓存生态、对外暴露自然。常见组合：对外 REST、
内部高频调用 gRPC。但要点破一件事：**换协议不豁免演进evolution纪律**——
proto 的兼容规则同样严格（field number 永不复用、不改已有字段类型、
删除字段要 reserved），gRPC 只是把契约检查工具化了，没把兼容性问题
消灭掉。JD 提到 gRPC，能讲 proto 演进evolution规则会是个小亮点。

### E8：怎么安全地存密码？服务间的密钥怎么管理？JWT 和 session 怎么选？

**答：**

**密码存储**：只存慢哈希——bcrypt/scrypt/argon2，自带 salt、成本因子可调
（硬件变快就调高）。`MD5(salt + password)` 不行的原因要说得出：不是因为
「MD5 被破解」，是因为它**太快**——GPU 每秒百亿次的尝试速度下，
快哈希 = 可爆破；慢哈希把单次验证抬到几十毫秒量级order of magnitude，爆破成本上升百万倍。
再加一层 pepper（应用层密钥，不入库）防「只拖走数据库」的场景；
登录接口限流 + 撞库检测（credential stuffing 的特征是低频多账户）收尾。

**密钥管理**：三条纪律——① 密钥永不进代码库和镜像（git 历史删不干净，
提交过一次就视为泄漏、必须轮换）；② 运行时从 Secrets Manager/KMS 拉取,
配 IAM 角色而不是长期凭证；③ **轮换是设计要求不是应急动作**：
双密钥并存期（新旧同时有效）让轮换可以灰度progressive rollout做，不停机。
发卡语境下这直接对接 PCI DSS：持卡人数据环境cardholder data environment（CDE）的密钥管理是审计的重头。

**JWT vs session**：本质是「状态放哪」的取舍trade-off——

| | session | JWT |
| --- | --- | --- |
| 状态 | 服务端（需共享存储） | 自包含（无状态） |
| 吊销 | 即时（删记录即可） | 困难（发出去收不回） |
| 水平扩展 | 依赖 session 存储 | 天然无状态 |

金融场景的常见落地implementation：**短命 access token（分钟级）+ refresh token 轮换 +
敏感操作 step-up 认证**（改限额、改地址时二次验证）——用短 TTL 补 JWT
吊销难的短板weakest link。JWT 高频错误清单：信任 `alg` 头（none 攻击）、
把敏感数据放 payload（那只是 base64，不是加密）、不校验 `aud`/`iss`
（A 服务的 token 拿到 B 服务也能用）。

**追问：access token 泄漏了，你的设计怎么把损失关在笼子里？**

**追答：**

设计前提就是「泄漏一定会发生」，要设计的是爆炸半径blast radius：① 短 TTL 把
被利用窗口压到分钟级；② refresh token **rotation + 重放检测**——每次
刷新旧 refresh token 作废，若一个已作废的 refresh token 再次被使用,
说明它被偷了，立刻吊销整个 token 家族并强制重新登录；③ 敏感操作
step-up：token 只够看，动钱要再验证——把「读泄漏」和「资金损失」隔开；
④ 服务端风控兜底：异地/异常设备的 token 使用触发挑战。
这套思路和发卡风控同构：不追求「绝不被盗」，追求「被盗后损失有上限
且能快速检测」——安全设计和发卡风控（3DS 的挑战率 vs 盗刷率运营）是同一套哲学。

### E9：给一张几亿行的生产大表加字段/加索引，怎么做到不影响业务？

**答：**

**先讲危险在哪**，三重风险：

1. **MDL（metadata lock）连环堵**：DDL 要拿表的元数据排他锁；只要有一个
   长事务/慢查询还持着这张表的 MDL 读锁，DDL 就排队等待——而排在 DDL
   **后面**的所有普通查询又都得等 DDL。经典事故：一个忘了提交的事务 +
   一条 ALTER = 全表流量瞬间冻结。所以 DDL 前必查
   `information_schema.innodb_trx` 里的长事务，并把会话的
   `lock_wait_timeout` 调小——拿不到锁宁可 DDL 自己失败，别堵住队列。
2. **拷贝型 DDL 的时长与空间**：重建表意味着几小时的执行 + 双倍磁盘占用。
3. **主从延迟**：DDL 在主库执行完才进 binlog，从库还要串行重放同样时长——
   从库延迟几小时，读写分离的读全变旧数据。

**工具箱分层**（从便宜到重）：

1. **MySQL 8.0 原生算法**：`INSTANT`（表尾加列等，秒级、只改元数据）、
   `INPLACE`（加二级索引等，不重建聚簇索引但仍耗时占 IO）、
   `COPY`（改列类型等，全量重建）。第一步永远是查官方支持矩阵，
   确认这条变更落在哪一档——`INSTANT` 能解决的直接低峰执行。
2. **非 INSTANT 的大变更用影子表shadow table工具**：gh-ost / pt-osc——建新结构的
   影子表 → 分批拷贝存量数据existing rows + 应用增量变更incremental changes（gh-ost 读 binlog，pt-osc 用触发器）→
   追平后原子 rename 切换。可限速、可暂停、可随时放弃。
   本质就是 S3 迁移migration在单表尺度的缩影：全量 + 增量 + 原子切换atomic cutover。
3. **应用层配合 expand-contract**：加新列（nullable/带默认）→ 代码双写 →
   回填 → 读切到新列 → 停写旧列 → 删旧列。每一步独立发布、独立可回滚。
   反例counterexample就是 B1 事故示例的根因类型：一次发布捆绑「改表 + 改代码」，
   回滚代码时表结构回不去。

**追问 1：加索引不是 INPLACE 吗，为什么也要小心？**

**追答：**

`INPLACE` ≠ 免费：构建索引要全表扫描，IO/CPU 会挤压业务流量；从库重放
同样耗时，延迟照样出现；更隐蔽的是**新索引会改变优化器的选择**——
别的 SQL 可能突然切换执行计划变慢（E1 追问 2 的反向版本：不是统计过期，
而是选项变多）。所以加索引也走低峰窗口 + 发布前后对关键 SQL 做执行计划
对比，索引上线是「变更」不是「无害优化」。

**追问 2：Liquibase/Flyway 这类 migration 工具和上面是什么关系？**

**追答：**

管的是两个不同的问题。migration 工具管**版本化与可重复**——哪个环境
跑过哪些 changeset、顺序和校验和是否一致（我项目 changelog `0001–0007`
就是这层）；它完全不管「这条 DDL 在生产大表上是否安全」——changeset 里
一条普通 `ALTER` 该锁表还是锁表。所以生产纪律是两层叠加：
文件层保证可追溯traceable，执行层在评审时给大表 changeset 标注执行策略
（`INSTANT` 直接跑 / gh-ost 跑完后补记录）。把这两层混为一谈，
就会出现「Liquibase 跑了所以没问题」的假安全感。

### E10：MyBatis 里 `#{}` 和 `${}` 有什么区别？为什么后者会导致 SQL 注入 injection？

这题和本项目技术栈直接对应——用 MyBatis 写过 mapper 的人被问到的概率很高，
而且是"简历上写了 MyBatis，面试官顺口一问"的典型送分题/送命题分界线。

**答：**

**机制差异先讲透**：`#{}` 走 **PreparedStatement 预编译 prepared statement**——
MyBatis 把 SQL 模板发给数据库驱动生成占位符 `?`，参数值单独通过
`setXxx(index, value)` 绑定传输，**参数永远只被当作"值"，不会被数据库
解析成 SQL 的一部分**。`${}` 是纯字符串拼接——MyBatis 在生成最终 SQL 文本
这一步就把参数值原样嵌进去，数据库拿到的已经是一整条拼好的 SQL 字符串，
分不清哪部分是"代码"哪部分是"数据"。

**注入怎么发生的**（给一个能在白板上写出来的例子）：

```java
// mapper.xml: ORDER BY ${sortColumn}
// 调用方传入 sortColumn = "amount; DROP TABLE credit_accounts;--"
```

拼出来的 SQL 变成 `ORDER BY amount; DROP TABLE credit_accounts;--`——
数据库把攻击者传入的内容当**语法**执行，而不是当**数据**比较。
这正是 SQL 注入的本质定义：**用户输入突破了"数据"边界、被解释成了"代码"**，
和 XSS（数据被解释成 HTML/JS）、命令注入（数据被解释成 shell 指令）
是同一类漏洞在不同解析层的变体。

**`${}` 并非一无是处，但用法必须收窄到不可控参数绝对进不去的地方**：
表名、列名、`ORDER BY` 方向（`ASC`/`DESC`）这类**SQL 语法位置**上
`#{}` 做不到（占位符只能替换值，不能替换标识符），这时才用 `${}`，
但输入必须来自**白名单whitelist枚举**而不是用户原始字符串——比如
`sortDirection` 只接受程序内部定义的 `ASC`/`DESC` 常量，
不透传前端传来的任意字符串。**默认用 `#{}`，`${}` 是需要额外理由的例外**，
review 里看到 `${}` 要先问一句"这个值有没有可能来自用户输入"。

**追问 1：本项目里哪些地方最容易踩到这个坑？**

**追答：**

动态排序/动态过滤条件的列表查询——账单明细、交易流水这类支持
"按用户选择的字段排序"的接口最危险，因为排序列名天然想用 `${}` 传。
正确做法是**前端传枚举值（如 `"AMOUNT_DESC"`），后端映射到白名单里
固定的 SQL 片段**，而不是直接把前端字符串拼进 `ORDER BY`。
MyBatis 的 `<foreach>` 生成 `IN (?, ?, ?)` 这类多值查询天然安全
（每个元素仍是独立的 `#{}` 占位符），这是容易被误解为"动态 SQL 就不安全"
的反例，要主动讲清楚二者不是一回事。

**追问 2：`#{}` 就绝对安全了吗？还有什么口子要防？**

**追答：**

`#{}` 只堵住了"值"这一层的注入，还有两类相邻风险要一并讲到：
① **二次注入**——数据第一次入库时是安全的（走了 `#{}`），但如果这条
数据后续被**原样读出、再拼进另一条动态 SQL**（比如用户昵称含
`'; --`，先被安全地存进去，之后某个报表功能把它当列名/条件拼接），
风险在读取后的第二次使用点，而不是第一次写入点——排查注入漏洞
要沿着数据流找全部"被当作 SQL 片段使用"的地方，不能只查一个入口；
② **ORM 之外的原生 SQL/JDBC 拼接**——项目里如果有绕过 MyBatis
直接手写 `Statement`（而不是 `PreparedStatement`）拼 SQL 的代码，
框架的保护完全不适用，这类代码要在 review 里单独当高危项标记。
一句话收尾：**参数化是机制，能不能被攻破要看每一条数据在系统里
流经的全部路径**，不是查一次输入就算完。

---

## 4. 实务编码practical coding题（C1–C5）

技术面的编码环节不总是 LeetCode——实务派面试官偏爱「30 分钟手写一个
能用的组件」：正确、线程安全、边界清晰。这一节每题给考点、答题骨架skeleton
（关键代码）、必踩的坑、追问。通用纪律：**动手前先花 30 秒口头确认
需求和 API 签名**（容量语义？并发吗？满了阻塞还是拒绝？）——
实务题一半的分数在「你先问了什么」。

### C1：手写一个 LRU Cache（get/put 都要 O(1)）

**开场第一句**：「标准库 `LinkedHashMap(capacity, 0.75f, true)` + 覆写
`removeEldestEntry` 三行就是 LRU——需要手写底层的话，我用
HashMap + 双向链表。」先亮标准库再手写，展示的是层次感。

**骨架**：

```java
class LruCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private final Node<K, V> head = new Node<>(), tail = new Node<>(); // 哨兵

    // get：map 命中 → 从链表摘下 → 插到 head 后；未命中返回 null
    // put：已存在 → 更新值并移到头部；
    //      不存在 → 若满，先摘 tail.prev 并同步删 map，再插入新节点
}
```

**必踩的坑**（面试官盯着的地方）：

1. `get` 忘了移动节点——那是 FIFO 不是 LRU，第一个淘汰项first disqualifier。
2. 淘汰时只删链表忘删 map（或反过来）——两个结构必须同步修改，
   写的时候嘴上说出来「这里两处要一起动」。
3. **哨兵节点**（dummy head/tail）省掉所有 null 分支——不用哨兵的版本
   if 满天飞，几乎必错一个边界。
4. `capacity <= 0`、重复 put 同 key 的语义，动手前开口确认。

**追问 1：怎么做成线程安全？**

**追答：**

分层答：① `synchronized` 全包——正确、简单，先给出这个再谈优化；
② 只把 map 换成 ConcurrentHashMap **不够**——链表操作和 map 操作的组合
不是原子的，这是 E6 里 check-then-act 竞态的又一个化身；
③ 生产答案是不自己造：Caffeine 用 W-TinyLFU 而不是纯 LRU——纯 LRU
没有扫描抵抗性，一次全表遍历就把热数据全冲出去；它还用分段环形缓冲
把「记录访问」变成近似无锁。能说出「生产为什么不用裸 LRU」是这题的天花板ceiling。

**追问 2：要加 TTL 呢？**

**追答：**

节点带 `expireAt`，`get` 时惰性判断，过期即删——但纯惰性会让不再被
访问的过期项永久占位，所以补一个低频后台清理（或学 Redis 的抽样过期）。
顺势自然带回bridge back to：这就是项目里 Redis TTL + jitter 策略的单机镜像。

### C2：手写一个令牌桶限流器rate limiter

**骨架**：

```java
class TokenBucket {
    private final double capacity;
    private final double refillPerNano;   // 每纳秒补充的令牌数
    private double tokens;
    private long lastNano;

    synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - lastNano) * refillPerNano);
        lastNano = now;
        if (tokens >= 1.0) { tokens -= 1.0; return true; }
        return false;
    }
}
```

**写的时候开口讲的三个设计点**：

1. **懒补充**而不是后台线程定时加币——无线程、无调度误差，
   取令牌时按流逝时间一次算清，这是这道题的核心巧思。
2. 时钟用 `System.nanoTime()`（单调）而不是 `currentTimeMillis()`——
   NTP 回拨会让令牌「倒流」。
3. 两个参数的语义：capacity 表达允许的**突发**，refill 速率表达允许的
   **持续吞吐sustained throughput**——对齐项目里 burst 20 / sustained 10 rps 的配置语义。

**坑**：忘了 `min(capacity)` 封顶——闲置一小时的桶会攒出一小时的令牌，
突发瞬间放穿下游；精度问题想清楚（double 或全部用纳秒整数算）。

**追问：多实例部署怎么共享限流？**

**追答：**

把同一段「读-算-写」搬进 Redis Lua 原子执行，桶状态存 HASH
`{tokens, ts}`——这正是项目里 `RedisTokenBucketRateLimiter` 的实现
（[`caching-and-rate-limiting-cn.md`](caching-and-rate-limiting-cn.md)
有完整版）。两个要点：Lua 里用 Redis 的 `TIME` 而不是各实例本地钟
（多实例钟不一致会互相偷令牌）；Redis 故障时 fail-open 还是 fail-closed
按场景定——系统保护类限流 fail-open + 本地兜底fallback，配额类 fail-closed。

### C3：手写一个有界阻塞队列（生产者-消费者）

这题几乎是并发理解的试金石litmus test。**骨架**：

```java
class BoundedQueue<T> {
    private final Queue<T> q = new ArrayDeque<>();
    private final int cap;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    void put(T t) throws InterruptedException {
        lock.lock();
        try {
            while (q.size() == cap) notFull.await();   // 必须 while
            q.add(t);
            notEmpty.signal();
        } finally { lock.unlock(); }
    }
    // take 对称：while (q.isEmpty()) notEmpty.await(); ... notFull.signal();
}
```

**三个必考坑**：

1. **`while` 不是 `if`**：被唤醒 ≠ 条件成立——虚假唤醒存在，且从被唤醒
   到重新抢到锁之间条件可能又被别人改掉。用 `if` 的版本并发下必错，
   这是本题第一个淘汰项first disqualifier。
2. **两个 Condition 而不是一个**：单条件 + `signalAll` 也对，但会唤醒
   一堆同类线程空转再睡回去；双条件精确唤醒对方阵营。如果被要求用
   `wait/notify` 写：synchronized 只有一个等待集，必须 `notifyAll`——
   `notify` 可能叫醒同类导致唤醒丢失，两个版本的差异要能讲。
3. **unlock 放 finally**：`await` 会抛 `InterruptedException`，
   异常路径上锁必须释放。

**追问：JDK 的 ArrayBlockingQueue 和 LinkedBlockingQueue 实现上有什么差异？**

**追答：**

ArrayBlockingQueue 单锁双条件——和上面手写的同构；LinkedBlockingQueue
**两把锁**（put/take 各一把）+ AtomicInteger 计数，生产端和消费端不互斥，
吞吐更高，代价是实现复杂。顺带把话题引到生产纪律：Executors 工厂方法里
无界 LinkedBlockingQueue 是运行风险operational risk（任务堆积到 OOM），生产线程池要
有界队列 + 显式拒绝策略（衔接 qa bank Q50 的池设计）。

### C4：用 CompletableFuture 并行聚合三个下游调用，200ms 预算内返回

**场景**：授权决策前并行查风控分、账户额度、卡状态；任一路超时或失败
按各自的降级策略fallback policy处理。**骨架**：

```java
ExecutorService pool = Executors.newFixedThreadPool(64); // 不用 commonPool 跑阻塞 IO

CompletableFuture<Risk> risk = CompletableFuture
        .supplyAsync(() -> riskClient.score(req), pool)
        .completeOnTimeout(Risk.fallback(), 150, TimeUnit.MILLISECONDS)
        .exceptionally(e -> Risk.fallback());
// limit、card 两路同构，先全部发射

CompletableFuture.allOf(risk, limit, card).join();
return decide(risk.join(), limit.join(), card.join());
```

**写的时候开口讲的四个点**：

1. **先全部发射，再统一 join**——在循环里逐个 `join()` 会把并行做成串行，
   本题最常见错误。
2. **自定义线程池**：默认 commonPool 是给 CPU 密集任务的（和并行流共用），
   拿它跑阻塞 IO 会饿死整个 JVM 的并行任务。
3. **每路自己的降级策略**：风控超时可以用保守分兜底，但**额度不能降级**
   ——哪些下游允许 fallback 是业务判断，写代码时把这句说出来。
4. **并行的预算算法**：总时长 = 最慢一路，而不是三路之和——
   这正是并行化的意义，200ms 预算里给每路 150ms 是成立的。

**追问：虚拟线程时代还需要这套编排吗？**

**追答：**

结构化并发structured concurrency（`StructuredTaskScope`）让这段代码回归同步写法：fork 三个
子任务、`joinUntil(deadline)`、作用域退出自动取消未完成任务——
可读性和取消传播都优于 CompletableFuture 链。但三个判断一个都没消失：
要不要并行、每路的降级策略、对下游的并发上限（线程池换成信号量，
E6 的结论）。API 会换代，思考不换代——面试里这句收尾比背 API 更值钱。

### C5：SQL 现场题——每个账户取最新一期账单，再按余额取全局 Top 10

表 `statements(id, account_id, cycle_end, closing_balance)`：

```sql
SELECT *
FROM (
  SELECT s.*,
         ROW_NUMBER() OVER (PARTITION BY account_id
                            ORDER BY cycle_end DESC) AS rn
  FROM statements s
) t
WHERE rn = 1
ORDER BY closing_balance DESC
LIMIT 10;
```

**考点与坑**：

1. 「每组取最新」= `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)`
   的肌肉记忆；被追问 MySQL 8.0 之前的写法：先按组聚出
   `(account_id, MAX(cycle_end))` 再 JOIN 回原表——要主动指出
   同一天两条记录时这个写法会出并列行。
2. **窗口别名不能直接放 WHERE**——SQL 执行顺序里 WHERE 在窗口计算之前，
   必须套一层子查询；忘了这条当场语法错。
3. 并列语义开口问：`ROW_NUMBER` 硬切一条、`RANK` 保留并列——
   选哪个由业务定，主动问就是加分项。

**追问：这条查询慢，怎么优化？**

**追答：**

先 `EXPLAIN`（E1 的工具链）。索引 `(account_id, cycle_end DESC)` 让窗口
按分区有序扫描、免排序。但如果表巨大且这是高频查询，根治不是继续
优化 SQL，而是**维护一张「最新账单」读模型**：每次出账时 upsert 一行
`latest_statement`，查询从 O(全表开窗) 变成 O(账户数) 的直查——
这就是项目里 read model 思路的 SQL 版。答题模式：先给查询级优化，
再给架构级方案architectural solution，并说清各自的适用边界。

---

## 5. 生产实战production operations：debug、事故、测试与发布（P1–P6）

这一节回答「你真的上过生产吗」类问题。面试官问它们不是要听背流程，
是听**肌肉记忆**：具体工具名、具体判断顺序、具体踩过的坑。
项目版的五个排障场景在 [`interview-qa-bank-cn.md`](interview-qa-bank-cn.md)
第 12 节（授权 p99、Outbox backlog、账单缓存、consumer lag、Redis timeout），
本节是通用方法论层——答题时先用本节框架开场，再用项目场景具体化ground the answer举例，
两层配合就是「既有体系又有实战」的效果。

### P1：接口偶发intermittent超时（一天几十次、无规律），你怎么系统化排查？

**答：**

偶发intermittent问题的第一原则：**不要试图现场复现，先把「偶发」变成「可观测」**——
复现靠运气，观测靠工程。我的排查流水线：

1. **先把「偶发」定义清楚**：拉延迟分布直方图——双峰分布（大部分 20ms +
   一小撮 2s）和尾延迟tail latency渐变是完全不同的病。双峰几乎总是「撞上了某个离散
   事件」：GC、锁、缓存重建、超时重试。
2. **时间相关性分析temporal correlation analysis**：把慢请求的时间戳导出来，和一切周期性事件对齐——
   GC 日志、定时任务（cron 表要真的翻一遍）、缓存 TTL 到期、连接池
   回收周期、部署时刻、整点流量。先问「延迟尖刺latency spike有没有周期性」：
   有周期 = 有定时源头，一半的偶发问题在这一步破案。
3. **维度dimension切分**：慢请求按实例/接口/账户/参数特征切——集中在单实例
   （宿主机或邻居问题）？单账户（热点hotspot行锁row lock）？特定参数（某分支慢查询）？
   全随机（基础设施层）？切分结果决定下一步去哪里挖。
4. **请求级证据**：trace 采样必须**保留慢样本**（尾部采样tail-based sampling，不是头部随机
   采样——1% 随机采样几乎永远抓不到 p99 样本）；日志 traceId 贯穿 +
   关键阶段耗时打点instrumentation，让任何一条慢请求都能被解剖成「时间花在哪一段」。
5. **常见嫌疑人清单**（按命中率）：GC 停顿（对时间线）、锁等待
   （行锁/JVM 锁，jstack 抓现场）、**连接池排队**（获取连接的等待时间要
   单独打点——它常被算进「DB 慢」里背锅）、下游超时 + 重试放大、
   建连开销（keep-alive 失效，D2）、宿主机邻居（steal time、IO 争抢）。

**追问：尖刺一天只出现几次，trace 也没抓到，怎么办？**

**追答：**

上「常驻低开销观测continuous low-overhead observability」，把系统改造成**出事时证据已经在手里**：
① async-profiler 做 continuous profiling（个位数百分比开销，生产可长开），
事后按时间窗对比正常时段和尖刺时段的火焰图；② JFR（Java Flight
Recorder）环形缓冲常开，出事后 dump 最近几分钟——它记录 GC、锁、IO、
异常的全事件流，就是为「事后取证post-incident forensics」设计的；③ 嫌疑在网络层时 tcpdump
条件抓包轮转。核心思想和飞机黑匣子同一个哲学：低频问题不靠守株待兔，
靠**让证据常态化存在**。

### P2：生产环境不能 attach debugger，你的线上 debug 工具箱是什么？

**答：**

分四层，从零成本到高侵入，用到哪层停在哪层：

1. **第一层：已有的可观测性**（零新增成本）——结构化日志按 traceId
   串链、metrics 面板、trace。九成问题应该到此为止；如果经常不够用，
   欠债的是可观测性建设，事后要补的是这里而不是工具箱。
2. **第二层：动态调整**——日志级别运行时下调（logback/log4j2 都支持，
   比重新发布快两个数量级order of magnitude）；feature flag 隔离可疑功能路径。
3. **第三层：JVM 快照snapshot与画像**——`jstack`（连拍 3 次、间隔 5 秒看变化，
   抓锁等待和死循环）、`jcmd GC.class_histogram`（轻量对象分布）、
   async-profiler 火焰图（CPU/alloc/lock 三种模式）、JFR。
   heap dump 是重武器：STW 秒级 + 文件巨大，对授权这种延迟敏感服务
   要摘掉流量再拍（E5 的取证纪律）。
4. **第四层：在线诊断live diagnostics**——Arthas：`watch` 看方法真实入参/返回值/异常，
   `trace` 看方法内部耗时分布，`tt` 记录调用现场事后回放，全部不改代码
   不重启。经典救场：生产行为和代码「看起来」不一致——`jad` 反编译
   当前 JVM 里实际加载的字节码直接对质，多少「灵异问题」其实是
   跑着旧类文件。

**金融系统必须补的纪律**：诊断工具自身有观察者效应（字节码增强、
高频 watch 都有开销）；生产使用要有审批流程和审计记录approval workflow and audit trail；`watch` 抓到的入参可能含
卡号金额，输出脱敏与 PCI 语境一致；一切诊断以「不放大事故」为上限——
宁可取证慢一点，不拿生产冒险。

**追问：怀疑某方法在特定参数下走错分支，但日志没打，怎么最快确认？**

**追答：**

Arthas 条件表达式：`watch RiskService score '{params, returnObj}'
'params[0].amount > 100000' -x 2`——只捕获大额请求的现场，两分钟出
结论，零发布。没有 Arthas 的环境退一级：动态调 DEBUG 级别（前提是
关键分支本来写了 debug 日志——**写代码时留 debug 日志是给未来的自己
留后门**）；再退一级才是加日志发布。这个「确认成本阶梯」本身就是
经验的证据：新手第一反应是加日志重发布，老手第一反应是盘点手里
有什么不用发布的手段。

### P3：半夜被叫起来，线上事故，你的前 15 分钟做什么？

**答：**

前 15 分钟的目标只有一个：**止血mitigation**，不是找根因root cause。我的顺序：

1. **2 分钟定级**：影响面blast radius——错误率多少、影响哪类请求、**钱对不对**。
   资金正确性问题和可用性问题的处置优先级完全不同：可用性可以降级graceful degradation
   慢慢修，资金算错要先停下相关功能（宁可不可用，不可算错）。
   按分级标准决定叫不叫人，不靠个人英雄主义硬扛。
2. **3 分钟找「变更」**：绝大多数事故由变更引起——刚才发布了什么？
   配置改了什么？流量有没有突变？上下游有没有动？变更清单是第一
   嫌疑名单（这也是 P6 里发布记录要集中可查的原因）。
3. **5 分钟止血决策**，按优先级：**回滚rollback**（最近有变更就先回滚，
   不需要先证明因果——回滚便宜，猜错了再回来）→ **降级**（关非核心
   功能、开兜底fallback）→ **限流rate limiting**（保住部分容量）→ 重启（重启前留 dump，
   E5 的纪律）。止血动作要选**可逆的**。
4. **5 分钟建立沟通节奏**：事故频道发第一条状态（影响、已做动作、
   下次更新时间），之后固定节奏更新。沟通不是挤占救火时间——
   它防止十个人排队问同一个问题，防止不知情的人做出第二个变更
   把事故变复杂。这个固定更新时间就是事故沟通节奏incident communication cadence。

根因是天亮后的事。证据（dump、日志时间窗、变更记录）留好；
「不明原因自己恢复了」要当潜伏风险latent risk跟进——重启好了就算了，
是下一次更大事故的预约单。

**追问 1：什么时候回滚，什么时候 fix forward（改代码往前修）？**

**追答：**

默认永远回滚：昨天的版本是唯一被验证过的状态，而深夜手写的 hotfix
是全新的未测试变更——事故中的人智商减半，fix forward 是拿更大的风险
赌更快的恢复。fix forward 只在两种情况成立：回滚已不可行（数据格式
已前进、依赖已切换——E9/S3 反复强调「每步可回滚」就是在防这个局面state of play），
或根因百分之百确定且修复是一行级的；即便如此也要第二双眼睛看过再上。
补一句面试官爱听的：我评估一个团队的发布成熟度，第一个问题就是
「回滚要几分钟、多久真演一次」。

**追问 2：事后复盘postmortem 怎么写才不流于形式？**

**追答：**

四个硬要求：① **时间线精确到分钟**——从注入到发现到止血；「发现慢」
往往比「修得慢」更值得改进：为什么是客服先知道而不是监控先叫？
② **影响量化**——多少请求、多少用户、多少钱，禁止「部分用户受影响」
这种糊话；③ 根因避免**单因谬误single-cause fallacy**——事故几乎都是多层防线同时失效，
要平行问三条线：为什么测试没拦住、为什么监控没先报、为什么止血花了
40 分钟；④ **action item 可验证verifiable**——「加强 review」是坏 action，
「schema 变更单增加回滚依赖检查项，owner 某某、deadline 某日」是好
action，且下次事故复盘postmortem先审计上次 action 的完成率。前提是 blameless：
追责文化下大家会隐瞒小异常，直到它长成大事故（和 T4 是同一体系的两面）。

### P4：发现线上有 1 万条数据算错了（比如利息多算），怎么修？

**答：**

数据订正是最容易引发二次事故的生产操作，我的流程：

1. **先堵源头再修存量数据existing data**：确认产生错误数据的 bug 已修复上线——
   否则边修边冒新错数据，永远修不完。
2. **圈定影响面并留证**：SQL 圈出受影响记录**落成影响清单表**
   （含修复前原值）——既是回滚依据也是审计证据。金额类错误还要出
   用户影响报告（多收了谁、多少钱）：这是合规事件，不只是技术事件。
3. **修复脚本当生产代码对待**：幂等idempotency、分批（每批几百条 + 间隔，避免
   大事务锁表和复制延迟）、可中断续跑；走 code review；先在预发用
   生产影子数据演练，生产先跑 10 条抽样人工核对再放量。
   **绝不手敲 UPDATE 直连生产**——手抖没有 review。
4. **修「果」还要修「派生」**：错误数据可能已流进下游——缓存失效、
   读模型重建、已发出的账单/通知要不要更正、事件要不要重推。
   画一张「污染传播图」逐个节点处理。
5. **闭环验证**：重跑圈定 SQL 应为零；再跑一轮业务不变量对账reconciliation
   （S2 的第三层校验）确认没修出新问题。

**追问：多收的钱涉及退款，工程上要注意什么？**

**追答：**

退款走**正向业务流程**而不是数据订正：给每个受影响账户 append 一条
adjustment 冲正交易（D1 的 append-only 原则），让退款出现在账单上、
有据可查——而不是悄悄改余额让钱「凭空对不上」。幂等键idempotency key用
「事故 ID + 账户 ID」，脚本重跑不会退两次。这也是账务系统坚持
不可变记录的深层原因：**修错误的能力是设计出来的**——
靠 UPDATE 覆盖的系统，连「错了多少、修了没有」都说不清。

### P5：你怎么决定什么该测、测到什么程度？说真实的策略，不是教科书金字塔

**答：**

我的分配原则是按**出错概率 × 出错代价**投资，落到三层：

1. **单元测试**：只重仓有逻辑密度的地方——金额计算、状态机迁移、
   规则判断这类纯函数穷举边界（0 元、负数、化整、闰月账单日）；
   getter/setter 和纯粘合代码不测——那种测试是负资产，
   改动成本大于拦截价值。
2. **集成测试**：**SQL 和事务语义必须真库测**——Testcontainers 起真
   MySQL 而不是 H2：`FOR UPDATE`、gap lock、`ON DUPLICATE KEY` 这些
   方言差异恰恰长在钱路径上，H2 通过 = 什么都没证明。幂等与并发是
   集成层重点（qa bank Q77/Q78 是项目里的具体写法）。
3. **端到端**：少而关键，只冒烟「授权 → 入账 → 出账 → 还款」这类
   跨模块主流程——E2E 又慢又脆，数量失控 CI 就半小时起步。

两条实践纪律：**测行为不测实现**——断言「余额变成 900」而不是
「调用了 deduct 方法」；后者每次重构全红，狼来了几次团队就不信测试了。
**flaky test 零容忍**——出现即隔离quarantine，限期修复或删除；
用 retry 掩盖的 flaky 会腐蚀团队对红灯的敏感度，
「红灯可能是误报」被默认的那天，测试体系就死了。

**追问：并发 bug 很难稳定复现，怎么测？**

**追答：**

分三层：① **确定性化**——把「两个请求同时到」写成两个线程用
CountDownLatch 卡在同一起跑线释放，循环几百轮，把概率问题变成
高频重复问题；② **断言不变量而不是具体交错**——无论线程怎么交错，
「额度扣减总和 = 成功授权总和」「同 key 只有一行」这类性质必须恒真；
③ **承认边界**——JVM 内测试模拟不了跨实例竞态，跨实例正确性靠
DB 约束和锁的设计本身（E2/E6），测试要证明的是「约束存在且生效」：
故意制造重复插入，断言撞上唯一键。收尾一句：并发正确性主要是
**设计出来的**，测试是验证网——指望测试兜住设计漏洞，在并发领域行不通。

### P6：怎么安全地把一个变更发到生产？说说你的发布纪律

**答：**

把发布当**可控实验**而不是上线仪式：

1. **发布前**：变更单写清三件事——改了什么、怎么验证成功、
   **怎么回滚**（含数据/配置的回滚依赖——E9 的 expand-contract 就是
   为了让「回滚代码」永远安全）；高风险变更避开业务高峰和周五晚上，
   不是迷信，是出事时在场人力的期望值问题。
2. **发布中**：金丝雀canary/滚动——先 1 台或 5% 流量，盯 15 分钟核心指标
   （错误率、p99、业务量），**前进/回滚阈值threshold事先写好**而不是现场凭感觉；
   feature flag 把「部署」和「放量」解耦——代码先上但功能关着，
   放量独立控制，出问题关 flag 秒级恢复，比回滚部署快一个量级。
3. **发布后**：主动盯 30 分钟指标再走人——「发完就下班」的变更
   最容易变成 P3 的半夜电话；发布记录进集中系统，
   它就是事故时「3 分钟找变更」的数据源。
4. **配套演练**：回滚定期真演。没演练过的回滚方案等于没有——
   S3-c 在迁移语境说过同一句话，它是通用真理。

**追问：feature flag 会不会越积越多变成技术债？**

**追答：**

会，而且是高发债。治理三条：① flag 分两类管——**发布型**（保护单次
发布，放量到 100% 后限期删除，创建时就开好删除 ticket）和**运营型**
（降级开关、A/B，长期存在但有 owner 有功能开关清单feature-flag inventory）；② 每个 flag 的默认值
要经得起「配置中心挂了」的拷问scrutiny——取不到配置时落到的那一侧必须是
安全侧；③ 组合爆炸控制——同一路径上活跃 flag 超过两三个，
测试矩阵就失控了，这本身就是删旧 flag 的强制理由。
一句话：flag 是有租期的脚手架，不是永久建筑。

---

## 6. 工程日常day-to-day engineering：选型、流程与效率（W1–W8）

区别于 P 节的「出事了怎么办」，这一节是「平常怎么干活」。面试官
（尤其 hiring manager）用这些问题判断你是不是**可持续交付sustainable delivery**的工程师：
有自己的工作系统，还是全凭惯性inertia。回答共同原则：具体做法 + 为什么 +
一个真实代价的反例counterexample——最忌答成教科书式的正确废话。

### W1：一个需求从到你手上到上线，你的完整流程是什么？

（hiring manager 的开场综合题。答案就是你工作系统的目录页——
每步一句关键动作，细节留给追问，追问落点在本文各节。）

**答：**

1. **需求澄清requirements clarification（半天内）**：用具体案例逼出精确需求（T5 的方法），
   产出一页行为描述让需求方确认——写下来的分歧最便宜。
2. **设计（深度按影响面定）**：小改动直接写；动数据模型/资金语义/
   跨模块的先写设计文档（W4），重点写失败场景和非目标non-goals。
3. **拆解decompose与估算estimation**：拆到 ≤2 天粒度granularity（W5），不确定的部分先做时间盒
   spike；此时就识别哪些步骤有回滚rollback依赖（E9 的 expand-contract 排期）。
4. **开发**：短分支小 PR（W3），测试与代码同 PR（P5 的分层），
   feature flag 让未完成功能可以持续合入主干。
5. **Review 与合入**：请人看之前自己先 self-review 一遍 diff——
   一半的低级问题自查就能拦掉，这是对 reviewer 时间的基本尊重。
6. **发布**：金丝雀发布canary rollout + 预设阈值predefined threshold（P6），发布后主动盯 30 分钟。
7. **收尾close-out**：观测验证需求效果——不只是「没报错」，是「业务指标
   动了没有」；清理发布型 flag；同步更新文档。

反例一句：这个流程最常被砍的是第 1 步和第 7 步——跳过澄清 =
用一周的代码返工代替一小时的对话；跳过效果观测 = 功能上线了,
没人知道它有没有用。

**追问：哪一步被跳过后你见过的代价最大？**

**追答：**

设计文档里的「**非目标non-goals**」。不写非目标的设计会在开发中途被 scope creep
吃掉——每个人都往里塞「顺便做了吧」，两周的活变六周。非目标是设计文档里
唯一防守性的段落，它给了工程师说「不做」的书面依据——口头拒绝是态度问题，
引用文档拒绝是执行纪律。我准备面试的这个项目，每份机制文档都有明确的
scope 边界，就是这个习惯的练习场。

### W2：技术选型technology selection你怎么做？给一个你的决策框架

**答：**

四个维度dimension打分，权重按团队现状调：

1. **问题匹配度**：它解决的问题真的是我们的问题吗？最常见的选型事故
   不是选错工具，是**为了用工具而发明问题**（简历驱动开发）。
2. **总拥有成本total cost of ownership（TCO）**：学习曲线、运维负担（谁半夜起来修它？）、升级路径。
   注意运行时组件（数据库、消息队列）的成本远大于库/框架——
   引入一个新存储系统的真实含义是「从此多养一个孩子」。
3. **生态与生命周期ecosystem and lifecycle**：社区活跃度、出现多久、大规模生产案例。
   我的默认立场stance是 boring technology：创新预算花在业务差异化上，
   基础设施用最无聊最成熟的——你的「创新代币」是有限的。
4. **退出成本exit cost**：单向门one-way door还是双向门two-way door？换一个 JSON 库是双向门，快速试错；
   选主数据库、定事件 schema 是单向门，值得花十倍时间——
   **决策的谨慎度应该和不可逆性irreversibility成正比，而不是和讨论的热情成正比。**

流程上：真实候选 ≤3 个；时间盒验证time-boxed PoC用**真实场景的数据和负载**测
（hello world 级 PoC 什么都证明不了）；结论写成 ADR——背景、选项、
决定、**被否决的选项rejected alternatives和否决理由**。半年后新人问「为什么用 X 不用 Y」，
答案在仓库里，而不是在离职同事的脑子里。

挂回项目：资金互斥选 MySQL 行锁row lock而不是 Redis 锁、事件发布选 outbox
而不是 Kafka 事务，每个决定都在文档里带着否决理由——
这是我理解的选型纪律的微缩演练。

**追问 1：同事提议用新潮框架重写某个模块，你怎么评估？**

**追答：**

把讨论从审美拉回成本四问：① 现状的痛是什么，**量化**（bug 率？改动
周期？）；② 新方案解决的是这些痛，还是别的什么；③ 迁移成本和风险
（S3 的方法论按比例缩小）；④ 有没有渐进迁移路径incremental migration path——能 strangler 就不推倒。
多数「重写冲动」在第①问就消解了：痛的根源常常是缺测试和文档，
换框架不治这个病。但也不当一刀切的守旧派——新技术命中真痛点时
（比如虚拟线程之于 IO 密集场景，E6），小范围试点pilot拿数据说话。

**追问 2：选型后来被证明错了，怎么办？**

**追答：**

首先，退出成本应该在设计时就被压低——关键依赖留抽象层（repository
接口后面藏 ORM、事件发布藏在 port 后面），**可替换性是设计出来的，
不是运气**。真要换：沉没成本sunk cost不参与决策，算的是「从今天起继续用 vs
迁移」的前瞻成本比较forward-looking cost comparison；迁移执行走 S3 的双跑parallel run与渐进放量progressive rollout。最重要的收尾是把教训写回
ADR：当初哪个假设assumption错了——选型能力的成长几乎全部来自这类复盘retrospective。

### W3：说说你的 git 工作流。为什么小 PR 这么重要？

**答：**

基线baseline：**主干开发trunk-based development + 短命分支short-lived branches + 小 PR**。

1. **分支活不过几天**：长命分支的冲突和集成风险随时间超线性增长；
   feature flag 让未完成的功能也能安全合入主干（P6 的机制在开发期的用法）。
2. **PR 控制在几百行内**：review 质量在几百行后断崖下跌——大 PR 得到的
   不是 review，是「LGTM 盖章」。小 PR 的复利循环compounding loop：review 快 → 合入快 →
   冲突少 → 回滚粒度rollback granularity细 → bisect 好用。
3. **原子提交atomic commit + 讲「为什么」的 message**：一个 commit 一个逻辑变更；
   message 写为什么而不是做了什么（做了什么 diff 已经说了）。这不是洁癖：
   `git blame` 是最常用的代码考古code archaeology工具——半年后面对一行怪代码，blame 出来的
   message 写着「兼容上游日期格式 bug」和写着「fix」，是两个世界。
   我准备这个项目时坚持每个提交带完整 message，就是给未来的考古留路。
4. **git bisect**：回归 bug 不知道哪个 commit 引入——二分历史 + 复现
   脚本，log₂(500) ≈ 9 步定位。前提恰恰是上面的纪律：commit 原子
   （bisect 出来的 commit 小到能看懂）且每个 commit 可构建——
   **工具的威力全靠日常纪律供养**。
5. rebase vs merge：私有分支 rebase 保持线性历史；共享分支绝不
   force push；撤销用 `git revert` 生成反向 commit（历史可追），
   不删历史。

**追问：一个大功能怎么拆成小 PR？**

**追答：**

两个刀法：① **准备性重构preparatory refactoring先行**——「先让改动变容易，再做容易的改动」：
前几个 PR 全是行为不变的重构（抽接口、挪代码，测试证明行为没变），
最后一个 PR 才是真功能，每个都独立可 review、可回滚；
② **纵向切片vertical slice + flag**——按「走通一条最窄的端到端路径」切，而不是按层切
（先 DB 后 service 后 API 的横切，每层都无法独立验证）；flag 关着，
逐 PR 放量。反例拆穿：「这个 PR 3000 行因为功能不可拆」几乎总是假的——
不是不可拆，是没花拆的功夫，而拆的功夫是 review 质量的成本价。

### W4：写代码之前你做什么？设计文档怎么写才不流于形式？

**答：**

原则一句话：**写文档是为了先在纸上失败fail on paper**——纸上换方案十分钟，
代码里换方案一周。什么规模需要文档，看两个轴：**不可逆性**
（动数据模型/事件 schema/对外 API 必写——E7/E9 的兼容性代价都源于此）
和**影响面**（跨模块/跨团队必写）；和代码行数无关——改一行隔离级别
可能比一千行 CRUD 更需要文档。

一页纸模板（超过一页就没人读了）：

1. **背景与目标**：一段话，用新人能懂的语言。
2. **非目标non-goals**：明确不做什么——防 scope creep 的关键段落（W1 追问）。
3. **方案**：数据模型 + 时序图 + 状态机，图优先于文字。
4. **失败模式推演failure-mode walkthrough**：每个环节「进程在这里挂了会怎样」——我项目所有
   机制文档都有这段，它逼出来的设计修正比评审会议还多。
5. **被否决的替代方案rejected alternatives**：一句话方案 + 一句话否决理由——评审会上
   80% 的「为什么不用 X」被提前消灭。
6. **上线与回滚rollout and rollback**：灰度方式、数据迁移、回滚路径（P6 变更单的前身）。

评审流程review workflow：文档提前 24 小时发出（会上现读是集体浪费时间）；
关键 reviewer 会前一对一过一遍（G4 根回し的工程版）；
会议只讨论分歧点points of disagreement，不从头念文档。

**追问：团队没有文档文化，你怎么推？**

**追答：**

不开口号会，用行为示范：自己的下一个中型改动写一页纸发出去。
第一次可能没人看；但当它在评审里提前挡掉一个设计错误，或三个月后
有人靠它十分钟看懂了模块，价值自己会说话。然后把门槛降到最低：
给模板、把「设计文档链接」做成 PR 模板的可选字段。自上而下的文化
靠强制，自下而上的文化靠「被看见的好处」——后者慢，但不会人走茶凉。

### W5：你怎么估算工期？为什么工程师总是低估？

**答：**

先回答为什么——低估是**结构性的**，不是态度问题：

1. 我们默认估的是**编码 happy path**的时间，而真实交付 = 编码 + 测试 +
   review 等待 + 集成调试 + 环境问题 + 被打断 + 需求微调——
   编码往往不到一半；
2. 计划谬误planning fallacy：人对「自己这一次」系统性乐观，对上一次的教训不敏感；
3. 不确定性不对称asymmetric uncertainty：顺利最多省 20%，踩一个坑能翻 3 倍——估算分布estimation distribution
   呈长尾分布long-tailed distribution，而我们直觉intuition报出的是众数不是期望。

对策四条：

1. **拆到 ≤2 天粒度再估**：粒度是估算准确度的第一决定因素——两周
   粒度的任务误差 ±100%，两天粒度的误差 ±半天；拆不动的地方就是
   不确定性藏身处，先花一天做时间盒 spike（产出是「知道怎么做了」）。
2. **用自己的历史数据校准calibrate**：我的个人系数（估 3 天实际 4.5 天 →
   ×1.5）比任何估算方法论都准，前提是记录过。
3. **对内计划紧、对外承诺松**：里程碑milestone对外给日期区间不给单点。
4. **进度滑移schedule slip纪律**：落后的第一时间暴露（完整故事在 B3）——
   估算错不是失职，隐瞒才是。

**追问：明知做不到的 deadline 被压下来，怎么办？**

**追答：**

B3 追问讲了当下怎么应对（把代价具体化，请决策者对风险签字）；
日常的**预防版**是两条：① 争取估算所有权estimation ownership——deadline 应该是
「业务需要 × 工程估算」的谈判结果；如果团队总是被单方面通知日期，
这个流程缺陷比任何一次 deadline 都更值得修。② 平时积累估算可信度estimation credibility——
你的估算历史越准，谈判时你的数字越有分量；信用是复利资产compounding asset，
每次「说到做到」都在充值，每次沉默的 slip 都在清零。

### W6：你觉得高效开发的核心是什么？说说具体做法

**答：**

一个公式：**效率 = 反馈回路feedback loop速度 × 专注保护focus protection**。所有做法都在优化这两项。

**反馈回路**（从「改了一行」到「知道对不对」的时间）：

1. 本地环境一键化：`docker compose up` 拉起全套依赖（我项目就这么配），
   新人 clone 到跑通 ≤30 分钟——这个数字是团队工程健康度的体温计。
2. 测试分层求快：单测秒级、集成测试可选择性跑——测试慢的直接后果
   不是浪费时间，是**大家不跑测试了**，防线名存实亡。
3. 构建超过几分钟就值得专项投资——它是每天被乘上百次的系数。
4. 自动化一切重复 ≥3 次的操作（脚本/Makefile/别名）：省时间是小头，
   消灭手工步骤的出错面是大头（P4 说过：手抖没有 review）。

**专注保护**：

5. 深度工作块：上下文重建一次约 20 分钟，一天被打断 6 次 = 半天蒸发；
   大块完整时间的产出非线性高于等量碎片。
6. 异步优先沟通（G3 同款）：非紧急问题走文字、攒批处理。
7. **限制 WIP**：同时只推进一件主要的事——三件事各完成 60% 的
   已交付价值delivered value是零，一件 100% 两件 0% 至少交付了一件。

**追问：新入职一个团队，怎么快速达到高效？**

**追答：**

头两周把时间投在「回路」而不是急着产出：跑通构建/测试/部署全链路、
读懂 CI、配好调试环境——这些投资之后每天付息。认知上用 B5 的方法
建地图（追一条主链路 + 写下来让老人挑错）。第一个任务主动选
**小而完整**的（一个 bug 从修到上线），目的是把交付全流程走一遍——
新人的首要任务不是证明聪明，是打通自己的交付管道delivery pipeline。

### W7：AI 辅助开发，它真正提效在哪？带来了什么新问题？

（T6 讲质量与责任红线red line；这题是效率与能力维度，两个角度都可能被问。）

**答：**

**真正提效的环节**（按我的实际体感gut feel排序）：

1. 样板与胶水代码：mapper、DTO、配置——机械劳动几乎归零；
2. 测试用例枚举：AI 穷举边界比人有耐心（然后由我裁剪断言——
   T6 说过它会写空洞测试）；
3. 陌生领域考古：读不熟的库、解释遗留代码、文档初稿——我这个
   准备项目的效率倍数主要来自这里；
4. 高质量 rubber duck：把设计讲给 AI 并要求它攻击，比对墙讲强得多；
5. 大 codebase 检索问答：「哪里处理了 X」类问题比 grep 快。

**带来的新问题**（这半边答得具体才显真实）：

1. **验证成本转移shift in verification cost**：生成变快了，但幻觉 API、过时用法、似是而非的
   并发代码把工作量转移到验证端——净收益net benefit取决于你的验证效率，
   这解释了为什么资深者从 AI 获益更多。
2. **代码膨胀**：AI 偏爱防御性样板和过度抽象，不约束的话代码量和
   review 负担同步上涨——对策是 prompt 强制「最小 diff」+
   把项目约定喂进上下文。
3. **审查疲劳review fatigue**：大量「看起来都对」的代码降低警惕性——这恰恰是
   微妙 bug 混入的完美环境，所以钱路径的 AI 产出我逐行重写而不是浏览。
4. **技能维护成本skill-retention cost**：调试直觉和手写能力长在「挣扎」里，全托管会退化——
   我刻意保持核心逻辑手写，定期不带 AI 保持编码熟练度coding fluency（C 节就是这么准备的）。
5. **团队差异放大amplified variance across the team**：会验证的人效率翻倍，不会验证的人更快地产出
   负价值代码——AI 放大判断力差距，而不是抹平它。

一句话收束：**AI 把瓶颈从「写」移到了「判断」**——受益最大的是
本来就会判断的人，所以它加强而不是取代基本功投资。

**追问：AI 会不会让 junior 的培养断层？**

**追答：**

风险是真的：junior 用 AI 跳过挣扎期，而调试直觉恰恰长在挣扎里。
我带人的调整：① 要求先写自己的思路再对比 AI 的版本——差异点就是
学习点；② review 多问「为什么这么写」，讲不清的按 T6 的规则处理；
③ 任务里保留「禁 AI 区」：手写一次并发原语、独立查一次线上问题。
类比计算器：它没有消灭数学教育，但迫使教育重新聚焦——AI 时代的
基本功培养同理，需要**有意识地设计**，放任自流就真的断层。

### W8：技术债你怎么管理？

**答：**

先分类再谈管理——不分类的技术债讨论都是各说各话：

1. **有意的战术债**（「先硬编码，下月抽象」）：合法工具，但必须
   **打欠条**——TODO 关联 ticket 或进技术债台账technical-debt register；不记录的债不是债，
   是未来的惊喜。
2. **无意的债**（当时不知道有更好做法）：靠 review 和后见发现，
   发现即记录。
3. **腐化债bit rot**（当年正确，世界变了）：依赖过期、模式失配——要定期
   健康检查routine health check，「升级依赖」是最容易被无限推迟的一类。

管理机制四条：

1. **台账带「利息carrying cost」标注**：这笔债每月吃掉多少（摩擦friction工时、bug 占比）。
   没有利息的「债」可能根本不用还——丑但稳定且不再改的代码，
   还它是伪工作，先还利息最高的。
2. **还债两条腿**：小债顺路修opportunistically（boy scout rule——但顺路修必须小，
   PR 里混大重构会毁掉 review，W3 的纪律）；大债打包进需求
   （「做这个功能正好要动那个模块，+20% 工时顺路重构」——
   比单独立项容易批十倍）。
3. **翻译成业务语言**（T2 同款）：谈判用摩擦数据不用道德词汇——
   「烂代码」说服不了任何人，「这个模块改动要 3 天、别处半天，
   而它承载主业务」可以。
4. **控制新增比还旧账便宜一个量级order of magnitude**：definition of done 包含测试
   与文档；review 挡住「临时方案不打欠条」。

**追问：业务方永远不给还债时间，怎么办？**

**追答：**

换掉前提：不要「申请专门的还债时间」——那是把选择题出成
「功能 vs 卫生」，永远输。有效路径：① 把还债嵌进需求实现路径
（上面的打包法），让它是交付的一部分而不是竞争者；② 用一次真实事故
或量化摩擦做杠杆leverage——P3 复盘的 action item 是技术债最好的优先级入口prioritization channel
（「这个季度 40% 的线上问题出自这个模块」没人能反驳）；
③ 守住工程所有权边界engineering ownership boundary：**新代码不欠新债不需要任何人审批**，这是工程师
自己说了算的范围。三条做完债还在雪崩，那是组织级问题organizational issue——
值得在逆质问（R5）里提前探明。

---

## 7. 系统设计（S1–S3）

原则：这三题都**不是**本项目走读（那是 qa bank 第 11 节），而是把项目里练出的
机制迁移到新问题上。每题按真实 40 分钟节奏组织：
需求澄清requirements clarification（5 min）→ 规模估算capacity estimation（5 min）→ 核心设计（20 min）→ 失败模式failure modes与追问（10 min）。

### S1：设计「超还元祭」大促积分发放系统——每笔支付实时发积分，全场预算 10 亿 pt，发完即止

**答：**

**阶段 1：需求澄清（先问再答，面试官等你问）**

1. 触发源：支付成功事件（不是请求同步返回积分？——确认：允许秒级延迟，
   但用户在 App 里要「很快」看到积分入账）。
2. 预算语义：10 亿 pt 是**硬上限**（超发=事故）还是**软目标**（超发几万 pt 可接受）？
   ——这是整题的分水岭watershed，先按「近似硬上限：不允许显著超发，瞬时少量超发可事后核销」谈。
3. 规则：按金额比例（如 5%）、有单笔上限、每人活动期间总上限。
4. 规模：大促峰值支付 10,000 TPS（锚点假设reference assumption），活动 24 小时。

**阶段 2：规模估算**

- 峰值 10,000 TPS 支付事件 → 积分计算 10,000 TPS，写入积分账本同量级order of magnitude。
- 10 亿 pt ÷（平均每笔 50 pt）≈ 2,000 万笔就烧完预算——**大促开始后几小时内**，
  所以「预算还剩多少」是一个每秒被扣减上万次的热点hotspot计数器，这是本题的心脏。

**阶段 3：核心设计**

整体：支付系统在支付事务内写 outbox → Kafka `payment.completed`（partition key =
user_id，保证同一用户事件有序）→ 积分服务消费。积分服务内部分四步：

1. **幂等idempotency入口**：inbox 表按 `event_id` 唯一约束去重——支付系统重发、Kafka 重复投递
   都不会重复发积分。和我项目 Inbox 完全同构。
2. **规则计算**：纯函数算出应发 pt（比例、单笔 cap、查该用户已发总量做个人 cap）。
3. **预算扣减（热点核心，展开讲）**：
   - 反面方案 A：DB 单行 `UPDATE budget SET remaining = remaining - ?
     WHERE remaining >= ?`——正确但单行锁row lock串行化，几千 TPS 就到顶。
   - 反面方案 B：Redis `DECRBY`——扛得住量，但 Redis 是缓存层，
     宕机恢复后计数从哪来？钱相关的 source of truth 放 Redis 是我一定会被追问的红线red line。
   - **推荐：预算分片sharding+ 分层扣减**。把 10 亿 pt 切成 N=1000 个 shard（每片 100 万 pt）
     存 DB；每个积分服务实例向 DB **批发**一个 shard 到本地内存做原子扣减，
     烧完再领下一片。DB 锁频率从每笔一次降到每 100 万 pt 一次；
     实例崩溃最多丢一片未用完的余量（少发不超发，方向安全）。
     全局「还剩多少」用各 shard 状态聚合，允许秒级延迟。
4. **积分入账**：append-only 的 `point_ledger`（user_id, event_id 唯一, amount, reason），
   用户余额是 ledger 的派生视图 + 缓存。**先记账后更新余额缓存**，
   缓存丢了永远可从 ledger 重建。

**阶段 4：失败模式自查（不等面试官问，主动讲）**

1. 预算烧完瞬间：正在各实例内存里的余量继续发完（最多 N×批发粒度的尾差），
   新批发请求拿不到 shard → 规则引擎转「预算尽」分支。对外话术是
   「先到先得，以系统判定为准」，产品条款要配合留出尾差空间。
2. 消费积压：积分是异步的，积压不影响支付主链路——这是把发积分放 Kafka 后面的
   最大收益benefit；监控 consumer lag，App 端积分展示「计算中」兜底fallback。
3. 支付退款/取消：监听 `payment.refunded`，从 ledger 反向 append 一条负记录，
   余额可为负（用户已把积分花了），下次发放先冲抵——不回改原记录。

**追问 1：为什么不在支付事务里同步发积分，用户体验不是更好吗？**

**追答：**

三个理由：① 支付是钱路径，积分是营销路径，把营销逻辑放进钱的事务里，
积分服务的任何故障/慢查询都会拖垮支付可用性——用可用性换体验，方向反了；
② 大促积分规则天天改，改动频率高的代码必须离钱路径远；
③ 「秒级异步 + App 乐观展示（先显示预计积分，入账后转正式）」在体验上
和同步几乎无差别，成本却低一个量级。真正必须同步的只有「这笔支付成功与否」。

**追问 2：每人总上限（比如每人最多 5,000 pt）在分片方案下怎么保证？**

**追答：**

个人上限和全局预算是两个不同的热点：个人上限的竞争只在**同一用户并发支付**时出现，
量级小得多。用 Kafka partition key = user_id 让同一用户事件落到同一 consumer 串行处理，
个人累计值就是单线程更新的本地事实，再落 DB（`user_campaign_total` 表，
`UPDATE ... WHERE total + ? <= 5000` 条件更新兜底双保险）。
这里正好展示「按竞争域选并发策略」：全局预算靠分片分散竞争distribute contention，个人上限靠分区串行化，
不是所有热点都用同一把锤子。

**追问 3：怎么向老板证明没有超发也没有漏发？**

**追答：**

对账reconciliation作业：活动结束后（以及活动中每小时增量incrementally）扫 `payment.completed` 全量事件
对 `point_ledger` 做双向核对——有支付无积分（漏发）、有积分无支付（多发）、
金额不符（算错）三类差异分别出报表；预算侧核对
`SUM(ledger) ≤ 10 亿 + 允许尾差`。设计时就留好对账键（event_id 贯穿两边），
对账不是事后补救，是发布信心的一部分——这个习惯直接来自清分对账的行业纪律（S2）。

### S2：设计发卡行的清分对账系统——每天从卡组织收清分文件，和内部授权/入账记录核对

**答：**

**阶段 1：问题定义与澄清**

对账在核对什么：network 说「你的持卡人今天产生了这些请款，钱已按此结算」，
内部系统说「我记录的授权和入账是这些」——两边必须能逐笔对上，
对不上的每一笔都可能是**钱的错误**。澄清点：文件每天 1–2 次、百万行量级、
格式是卡组织定义的定长/TLV 记录；对账窗口：次日营业前出结果。

**阶段 2：数据模型——三方匹配**

每笔清分记录要在内部找到两层对应：

1. 清分记录 ↔ 内部 authorization（靠 network 交易标识 + 卡 token + 金额容差）：
   验证「这笔请款有对应的授权吗」。
2. 清分记录 ↔ 内部 posted transaction：验证「这笔请款我记账了吗，金额一致吗」。
3. 汇总层：文件尾部tail的合计金额/笔数 ↔ 内部当日 posting 合计 ↔ 实际结算资金
   （银行侧入出金）。明细全对但汇总不对 = 解析漏了记录，汇总对但明细有差 = 有互相抵消的错误，
   **两层都要**。

**阶段 3：处理管道**

1. **文件接收与不可变归档immutable archival**：原始文件先原样存对象存储（审计要求：任何结论都能
   回溯到原始字节），记录文件级元数据（序号、日期、行数、合计）。
   文件序号连续性检查——缺一个文件比错一行严重得多。
2. **解析与标准化**：解析成行级记录写入 `clearing_records` 表，
   文件 ID + 行号唯一约束 → **文件重放天然幂等**（运营最常见操作就是「文件重传一遍」）。
3. **匹配引擎**：按上面三方匹配规则跑批。设计要点：匹配是**多轮的**——
   第一轮严格匹配（标识+金额精确），第二轮容差匹配（金额差在小数化整规则内、
   多币种换算差），剩下的进差异池。分账户并行跑（我项目 claimable job 的
   分片模式直接复用：按 account 段 claim，多 worker 并行，lease 防重）。
4. **差异池与工作流**：每笔差异分类入库——

| 差异类型 | 典型原因 | 处理 |
| --- | --- | --- |
| 清分有、授权无 | STIP 期间代授权 / offline 交易 | 自动补记force post，额度事后扣减 |
| 授权有、清分迟迟不来 | 商户没请款 | 到期自动释放 hold（授权过期任务） |
| 两边有、金额不符 | 小费/汇率/部分请款 | 容差内自动过，超容差人工队列 |
| 内部有、清分无 | 内部重复入账（事故） | 最高优先级告警，冻结相关账户操作 |

   每类差异都是「自动处理 + 超阈值threshold转人工」的工作流，人工队列有 SLA 和 deadline 任务。

**阶段 4：主动讲的边界情况**

1. **时间窗切割**：授权在 23:59、清分在次日文件里——匹配必须带滑动窗口
   （拿最近 N 天未匹配授权池去配今天的清分），「当天对当天」是新手错误。
2. **对账系统自身的正确性**：对账是「裁判」，裁判错了比球员错了更糟。
   所以匹配引擎必须是**纯函数式、可重放**的：同样的输入文件 + 内部快照snapshot，
   任何时候重跑得到同样的差异清单；修 bug 后对历史重跑验证。
3. **结果的消费方**：差异不是报表就完了——「清分有授权无」要真实补扣额度，
   对账系统需要有回写主系统的通道，这个通道本身要幂等（差异 ID 作幂等键idempotency key）。

**追问：对账发现内部少记了 1,000 笔入账，你半夜被叫起来，处理顺序是什么？**

**追答：**

1. **先定性再动手**：这 1,000 笔是「解析漏了」（对账系统的错）还是「posting 链路
   真的丢了」（主系统的错）？拿 3–5 笔样本手工核对原始文件字节和内部记录，
   十分钟内分清是哪边——方向错了后面全白干。
2. 若是主系统丢数据：**先止血mitigation**——确认丢失是否仍在发生（看 posting 消费链路的
   lag/错误率），在扩大前停住；然后评估用户影响——少记入账意味着用户额度
   比真实多、账单会少——**宁可晚修不可错修**，补记走正常幂等入口重放这 1,000 笔，
   绝不手写 UPDATE。
3. 修完后：对补记结果再跑一轮对账闭环验证closed-loop verification；事后复盘postmortem为什么监控没有在对账之前
   发现（posting 成功率、outbox/inbox 差值这些先行指标应该更早报警）——
   对账抓到的问题都意味着上游监控有洞。

### S3：PayPay Card 要把一个 15 年历史的遗留发卡核心系统迁移到新平台，不能停服，你怎么设计迁移？

这题对 PayPay Card 极其现实（前身系统年代久远，JD 里也提 legacy 现代化），
而且是区分「做过题」和「懂工程」的题——答案的重心在**风险排序risk sequencing**，不在新系统多漂亮。

**答：**

**总原则先亮出来**：发卡系统迁移的第一目标不是「新系统上线」，
是**任何一天出问题都能在分钟级回到旧系统**。方案围绕三个词：strangler（绞杀者模式）、
dual-run（双跑比对）、reconciliation（每步都有对账闸门gate）。

**阶段 1：切分单位——按业务能力，不按表**

把系统切成可独立迁移的能力：授权决策、清分入账、账单、还款、督促、
卡片生命周期……选**第一个迁移对象**的标准：业务价值高之外，更重要的是
「读多写少 / 错了可重放」。账单查询类读路径先走（错了没资损），
授权决策这类实时资金路径**最后**走。

**阶段 2：数据层——先建同步管道，再谈切流**

1. 旧核心的 DB 通过 **CDC**（binlog/日志捕获）实时复制到新平台的数据模型，
   写一个转换层做新旧模型映射（旧系统的字段语义考古legacy archaeology是迁移里最耗时的部分，
   要配一个「旧系统语义字典」文档工程）。
2. **全量回填 + 增量追平**：先跑历史全量（按账户分片、可断点续跑——
   claimable job 模式又一次适用），再让 CDC 增量追平，差值收敛converge到秒级。
3. **每日对账闸门**：新旧两边按账户跑余额/状态核对，差异率不降到阈值以下，
   禁止进入下一阶段。对账脚本在迁移第一天就写，不是切流前一周补。

**阶段 3：读路径切流（strangler 开始咬）**

入口加路由routing层（API gateway / 前置 facade），账单查询等读流量按
**账户维度dimension渐进放量progressive rollout**（1% → 10% → 50% → 100%）切到新平台；新平台读自己的复制数据。
出错的影响半径blast radius = 展示错误，可即时回切。这一阶段真正的产出是：
路由/灰度/回切机制被读流量**演习**熟了，写路径切流时用的是同一套已验证的开关。

**阶段 4：写路径——shadow 双跑，再切主**

以授权为例（最险的路径）：

1. **Shadow 模式**：真实授权仍由旧系统决策返回；同一笔请求**异步**复制给新系统
   「假装决策」，结果只落日志不生效。跑 4–8 周，逐日比对新旧决策差异——
   每一个 diff 要么是新系统 bug（修），要么是旧系统 bug（终于被看见了），
   要么是有意的规则改进（记录并获得业务确认）。**差异率收敛到接近零才有资格切主。**
2. **切主**：按账户分组灰度，新系统决策生效、旧系统转为 shadow（反向比对继续跑）。
   保持**双写dual write**让旧系统数据持续新鲜——这是回切能力的物理基础。
   回切开关是账户组粒度、分钟级生效、演习过的。
3. **退役**：全量切完后旧系统只读保留一个合规要求的期限（审计/监管查询），
   然后才谈关机。宣布「迁移完成」的标准是回切窗口正式关闭，不是切流到 100%。

**主动讲风险**：

1. **双写的一致性陷阱**：双写期间两边都可能各自失败——必须明确
   「单一 source of truth 是谁」（切主前是旧，切主后是新），另一边的写失败
   只报警、由对账修复，**绝不**搞跨系统分布式事务去追求双写强一致strong consistency。
2. **长尾tail-end业务**：分期中的账户、争议中的交易、督促中的债权——这些跨月的
   长事务状态最难迁，宁可设计「存量账户existing accounts在旧系统跑完、新增账户new accounts进新系统」的双轨期，
   也不硬迁到一半状态。
3. **组织风险**：迁移是 1–2 年的马拉松，中途一定遭遇「先做新功能还是先迁移」的
   优先级冲突——所以切分要保证**每个阶段独立交付价值**（比如读路径切流顺带
   把查询延迟降了），迁移才不会成为永远排不上的技术债。

**追问 1：为什么不干脆停机一个周末，一次性割接？很多银行不都这么干过？**

**追答：**

停机割接把所有风险压缩到一个不可逆的时点：割接后发现新系统授权决策有 bug，
「回退」意味着把周末产生的新数据反向迁回旧模型——现场没人敢保证这条路是通的，
实际上等于没有回退。发卡业务 24 小时有交易（EC、海外时区），「停机窗口」本身
就意味着 STIP 代授权期，损失敞口exposure打开。灰度 + 双跑把同样的风险摊到几个月里，
每一步都可回退、可观测——总工期更长，但**最大单日损失**小两个数量级。
面试语言：我优化的不是期望工期，是风险的尾部。

**追问 2：shadow 双跑期间，新旧系统对同一笔授权给出不同结论，你怎么定位是谁错了？**

**追答：**

先建「diff 分诊」流水线而不是逐笔人肉看：按差异模式聚类（新批旧拒/新拒旧批 ×
风控分支 × 金额段），每类抽样深挖。定位手法是**输入固定、逐层比对**：
拿同一笔请求的完整输入快照，在两边的决策链路上逐检查点（卡状态→风控分→额度）
输出中间值，第一个分叉点就是根因root cause所在层。常见根因排名：① 数据复制延迟导致
新系统看到旧额度（管道问题）；② 旧系统里没人知道的隐藏规则（考古补规则）；
③ 浮点/化整差异（金额计算纪律问题）。关键纪律：每类 diff 关闭时要留一条
回归用例进比对套件，同类差异复发直接报警——diff 清单只能单调收敛。

#### S3 深挖：40 分钟主答之外的逐环节弹药ammunition

以上是限时作答版。迁移题的特点是**每个环节都能被追问两层**，
下面六个专题就是每个环节被按住深挖时的展开——也是真实迁移项目里
各自值一份设计文档的主题。

##### S3-a：数据同步管道——全量回填 + CDC 增量的工程细节

**一致性起点问题**（最容易被追问、最多人答不上）：全量快照和增量流必须
无缝衔接，否则快照之后、CDC 开始之前的写入永久丢失。标准做法：

1. 在一致性快照的**同一时刻**记录 binlog 位点（GTID/文件+offset）——
   `--single-transaction` 的逻辑导出或存储快照都能拿到对应位点。
2. 全量数据灌入新库后，CDC 从记录的位点开始回放增量。
3. 做不到严格同刻时用「先开流后快照」：CDC 先开始缓冲，再跑全量，
   增量从缓冲头回放——中间必然有重叠区，所以**增量应用必须是幂等 upsert**
   （按主键覆盖 + 版本号防旧盖新），重叠区自然被吸收。Debezium 的
   snapshot 模式就是这个思路的产品化。

**回填作业本身**：按主键区间切 chunk（每块几千行），进度落表、可断点续跑、
可并行——这就是 claimable job 模式的又一次复用；跑在旧库的从库上并限速，
「迁移把生产旧库打挂」是最丢人的事故类型。

**转换层的纪律**：旧模型 → 新模型的映射必须是**纯函数**（输入行、输出行，
无 IO），每个字段的映射有单测；配套「语义字典」工程：逐字段记录
来源系统、取值枚举、谁在写它、NULL 的业务含义。考古手段很土但有效：
对生产数据跑 `SELECT DISTINCT` 找出文档上不存在的枚举值——15 年的系统里
**一定**有文档外的值，每个值都对应一段没人记得的历史。

**CDC 的顺序性边界**：binlog 行级有序，但一个多表事务被拆成多行陆续到达，
新库会短暂看到「事务的一半」。不要试图逐事务原子重放（复杂度爆炸），
而是接受行级最终一致eventual consistency+ 用**账户级校验**兜底——校验的单位和业务不变量的
单位对齐（账户），而不是和复制的单位（行）对齐。

**校验的三层金字塔**：

| 层 | 手段 | 抓什么 | 成本 |
| --- | --- | --- | --- |
| 1 | 行数 / 主键集合对比 | 丢行、多行 | 低，可高频跑 |
| 2 | 分块 checksum（每千行一个 CRC） | 字段值错 | 中，先定位到块再下钻 |
| 3 | 业务不变量（余额 = 交易累计、账单头 = 明细合计） | 语义错 | 高，但最值钱 |

第 3 层的价值：前两层只能证明「新库 = 旧库」，第 3 层能抓住
「两边错得一样」之外的问题，还能反过来发现旧系统自己积累的脏数据——
迁移项目顺带产出一份旧系统数据质量报告，是常见的意外收获。
校验要**增量化**：只重验变更过的账户（CDC 流本身就是变更清单），
否则全量校验跑一轮的时间比数据漂移的速度还慢。

##### S3-b：Shadow 双跑——流量怎么复制，diff 引擎怎么建

**流量复制的三种方式**（按真实度与安全性权衡trade-off）：

| 方式 | 真实度 | 延迟 | 风险 |
| --- | --- | --- | --- |
| 网关gateway镜像（请求复制一份异步发新系统） | 高（原始请求） | 实时 | 要防新系统产生副作用 |
| 事件订阅（旧系统落库后发事件，新系统重算） | 中（已是加工品） | 秒级 | 最安全 |
| 日志回放（离线重放历史请求） | 高 | 离线 | 零风险，时效差 |

授权路径用网关镜像 + **副作用全面隔离**：shadow 侧的外部调用一律打桩——
尤其风控这种**非确定性依赖**要用「录制回放」：把旧系统当时真实拿到的
风控响应录下来喂给新系统，否则两边各调一次风控、拿到不同分数，
diff 里全是假警报，真 bug 反而被淹没。同理，时间要注入（用请求时刻而非
`now()`）、随机数要固定种子。**shadow 的第一工程原则：消灭非确定性输入。**

**diff 引擎的流水线**：归一化（掩掉时间戳、生成 ID、顺序无关字段）→
结构化对比（字段级，不是字符串 diff）→ 按（决策结果 × 分支路径 × 差异字段）
**聚类**→ 每类计数、留样本、开工单。产出不是「今天有 3,412 个 diff」，
而是「新增 2 类、收敛 5 类、存量 3 类各自的负责人和结论」。

**假 diff 的三大来源**（提前说出来很加分）：

1. **复制延迟**：新系统读到落后的数据做出不同决策——diff 时带上
   数据版本号对齐，或对可疑 diff 延迟几秒重查确认。
2. **规则版本不同步**：双跑期间业务改了旧系统的规则没同步新系统——
   规则变更必须「双投」，纳入变更管理。
3. **金额化整/浮点**：两边小数处理路径不同——这类 diff 不是噪音，
   是真 bug 的前兆，单独立类必须归零。

**出口标准要量化**：连续 N 天（比如 14 天，覆盖两个完整周末 + 出账日）
关键类 diff 为零、全部存量类有书面结论（bug 已修 / 旧系统 bug 确认 /
有意的行为变更获业务签字）。没有量化出口标准的 shadow 会跑成永远
「再观察一周」的僵尸项目。

**性能也要 shadow**：新系统不光答案要一样，p99 也要达标——shadow 期
就记录延迟分布和资源消耗，切主后才发现新系统慢 3 倍就晚了。

##### S3-c：切流、sticky 路由与回切

**路由维度必须是账户而不是请求**：同一账户的读写必须落同一侧，
否则「写进新系统、读到旧系统」的不一致直接暴露给用户。灰度组的推进顺序：
员工卡 → 低活跃账户 → 普通账户 → 大客户与特殊状态账户（分期中、争议中）最后。

**路由表的工程要求**：`account_id → OLD/NEW` 的映射本身要高可用
（本地缓存 + 变更推送，路由查询不能成为新的单点和延迟来源）；
账户**迁移瞬间**的处理是细节题：短暂 quiesce（该账户新请求挂起或拒绝、
等在途请求排空 → 切路由 → 放行），窗口毫秒到秒级；或者把两侧操作
都设计成幂等可重试，允许切换瞬间的请求失败重试后落到新侧。

**回切是流程不是开关**：

1. **前提**：旧系统数据仍然新鲜——反向同步（新 → 旧）没断、差异队列
   没积压。所以「差异队列长度」是回切健康度指标，积压超阈值要报警：
   此刻发生故障将无法干净回切。
2. **动作**：路由表批量切回 → 验证旧系统恢复处理该账户组 → 新系统转回
   shadow 继续比对（回切不是放弃，是退回上一阶段）。
3. **演习**：没演习过的回切方案等于没有。每个大阶段开始前做一次
   小范围（如员工组）真实回切演练，测出的往往不是开关问题，
   而是「反向转换层漏了新字段」这类只有真跑才暴露的问题。

**观测配套**：所有指标按路由侧打标（`side=old/new`），灰度每一步的
前进/暂停/回退条件go/pause/rollback criteria**事先写进 runbook**（如「新侧错误率高于旧侧 0.1pp
即暂停」），推进决策照单执行，不靠会议室现场拍板final say。

##### S3-d：双写期的失败矩阵

「双写」是迁移里最容易被说错的词。先钉死前提：**任何时刻只有一个
source of truth**（切主前是旧，切主后是新），另一侧是影子副本。
以切主后为例（新系统 = SoT），逐格分析：

| 写新（SoT） | 写旧（影子） | 后果与处理 |
| --- | --- | --- |
| 成功 | 成功 | 正常 |
| 成功 | 失败 | 用户无感；差异进补偿compensation队列异步重写旧侧（幂等）；旧侧落后期间**回切能力受损degraded rollback capability**——这就是 S3-c 说的健康度指标 |
| 失败 | 未执行 | 请求整体失败，用户重试；两侧一致，无害 |
| 失败 | 成功 | **最危险的一格**——通过顺序设计让它不存在：先写 SoT，SoT 成功后才异步写影子；影子写不在请求同步路径上，这格就是不可达状态 |

结论：正确的「双写」= **同步写 SoT + 异步复制影子 + 差异对账**，
本质是自建的主从复制，风险模型清晰。反模式是「同步双写、两边都成功
才返回」——那是自己手搓了一个没有协调者的 2PC，可用性乘积下降、
不一致窗口反而更多（衔接 E3 的技术选型technology selection原则）。

**反向转换层的隐藏成本**：新模型能表达旧模型表达不了的状态（新功能）。
所以双写期需要一份**功能冻结清单**：「只有新系统才能表达」的功能一律
推迟到回切窗口关闭后上线——否则等于亲手炸掉自己的回滚rollback路。
这份清单是和产品谈判的硬依据：不是工程师保守，是回切能力的物理前提。

##### S3-e：长尾状态与业务日历

**状态盘点先行**：分期中（跨 N 个月）、争议/chargeback 处理中、
延滞督促中、解约清算中、休眠卡。每类三选一并书面化：

1. **硬迁**：状态机可完整映射，随账户正常迁移（多数普通状态）。
2. **双轨**：存量在旧系统跑到自然终结，新发生的进新系统（适合争议这类
   有外部时限、迁移中断风险大的流程）。
3. **等待窗口**：状态临近终结的账户推迟到终结后再迁（例如还剩一期的分期）。

**迁移时点对齐业务日历**：全局上避开出账高峰日、扣款日、大促；
账户级更精细——每个账户在**自己账单周期的安静段**迁移
（还款确认后、下次出账前的窗口），此时该账户没有在途的批处理状态。
我项目里 billing cycle 是账户级属性，迁移分组直接按 cycle day 错峰，
迁移负载天然摊平——这是「领域模型自然支撑naturally enables基础设施设计」的好例子。

##### S3-f：组织与节奏——迁移作为多年期项目怎么不死

1. **里程碑必须各自独立交付价值**：M1 数据平台化（新库先成为分析与
   查询的数据源——立刻有用）→ M2 读切流（查询延迟改善）→ M3 shadow
   （产出旧系统 bug 清单，本身就是价值）→ M4 写切流 → M5 退役（成本释放）。
   这样任何时点项目被砍，已完成部分的价值都保得住。
2. **人力结构**：平台小队（管道/diff/路由等共用基建）+ 每个能力域一对
   「懂旧的 + 建新的」结对。旧系统专家是全项目的瓶颈资源，
   他们的时间要像稀缺算力一样排程，而不是随叫随到地救火。
3. **新需求三分法**：只进新系统（默认答案）/ 双实现（设预算上限，
   超了就是砍需求的谈判证据）/ 只进旧系统（原则禁止，需例外审批）。
   没有这个机制，迁移会被日常需求的双实现成本活活拖死。
4. **对「90% 完成」保持警惕**：迁移项目的最后 10% 是长尾状态和特殊客户，
   往往占 40% 的日历时间。计划阶段就把长尾当一等公民排期，
   而不是「剩下的杂项」——否则项目在最后一段永远差一个月。

**追问 3：CDC 复制延迟导致新系统读到旧数据，用户投诉「还款了额度没恢复」，怎么办？**

**追答：**

读切流阶段的经典问题，三层防御：

1. **度量先行**：端到端复制延迟（旧库提交时刻 → 新库可见时刻）做成 SLI
   持续测量，超阈值自动把读流量退回旧侧——降级开关挂在延迟指标上，
   而不是等客诉进来手动切。
2. **读己之写保护**：账户在旧侧发生写入后的短窗口内（比如 60 秒），
   该账户的读强制路由回旧侧——用写事件驱动一个临时的路由 pin，
   窗口过后自动失效。成本极低，精准消灭「刚操作完看到旧数据」这个
   用户最敏感的场景。
3. **产品兜底**：余额展示「更新中」状态优于展示一个错误的确定数字——
   工程和产品各挡一层。

原则一句话：复制延迟不能靠「希望它足够快」来管理，要可观测、有自动降级。

**追问 4：迁移做到一半，公司预算砍半、时限翻倍，你怎么调整？**

**追答：**

这是检验切分质量的时刻，而不是灾难。因为每个里程碑独立有价值（S3-f 第 1 条），
已完成阶段的收益benefit不会蒸发。调整动作：① 重排剩余能力域，只继续迁
「留在旧系统成本最高」的域（维护人力、故障率、合规风险最大的），
其余域**停在双轨稳定态stable coexistence state**；② 把「停留成本cost of staying」量化给决策层——双系统并存的
运维费、双实现税、专家占用，让「不继续迁」也是一个被定价的选择而不是
默认免费；③ 守住一条设计不变量design invariant：迁移的每个中间态都必须是**可长期运行的
稳定态**。最失败的迁移设计是「不做完就全无价值、停在中间就是危房」——
那种设计从第一天就把项目绑架给了预算的稳定性，而预算从来不稳定。

---

## 8. 行为面试（B1–B5）

### B0：先搭素材矩阵story matrix，再背答案

行为面的正确准备方式不是一题一答，是**5 个真实故事覆盖 20 种问法**。
先把你职业经历里这五类故事各选定一个（写成 STAR 四段、中英各一版）：

| 素材 | 覆盖的问法 |
| --- | --- |
| 一次生产事故（你在场且动了手） | 压力处理 / 排障能力 / 事后改进 / ownership |
| 一次技术分歧（你说服了人，或被说服） | 冲突处理 / 沟通 / 谦逊 / disagree & commit |
| 一次交付取舍（deadline 砍 scope） | 优先级 / 和 PM 协作 / 商业意识 |
| 一次失败（真失败，有你的责任） | 自我认知 / 成长型心态 / 诚实 |
| 一次快速学习（新领域从零到交付） | 学习能力 / 主动性——**本 repo 就是现成素材** |

下面每题给出**回答骨架skeleton + 一个可参考的完整示例**。示例是通用后端场景的样板，
【替换】标记处必须换成你自己的真实经历——行为面试里被追问三层后，
借来的故事一定露馅，骨架可以借，事实必须是你的。

### B1：讲一次你处理过的最严重的生产事故。（Tell me about the worst production incident you've handled.）

**回答骨架**：背景一句话（系统+你的角色）→ 事故现象与影响量化 → 你的处置时间线
（按分钟叙述，突出「先止血mitigation后修复」）→ 根因root cause→ 你主导的事后改进 → 一句反思。
全程用「我做了什么」，不用「我们」模糊自己的个人贡献individual contribution；影响必须有数字。
这题还有「流程版」问法（不讲你的故事、考你的方法论）——见 P3，两版互为素材。

**示例（【替换】为你的真实事故）：**

> 我当时负责一个交易类服务【替换：你的系统】。某天发布后 20 分钟，下游开始报
> 超时，核心接口 p99 从 200ms 涨到 4s，错误率 8%【替换：你的数字】。
> 我的处置：第一步不是找根因，是**止血mitigation**——先回滚rollback刚发的版本，5 分钟内完成，
> 但指标只恢复了一半，说明发布不是唯一原因。第二步看资源：DB 连接池打满，
> 活跃连接全部卡在同一条新加的查询上——回滚了应用但没回滚配套索引变更【替换：你的根因】。
> 补上索引后 15 分钟内恢复。全程我每 10 分钟在事故频道同步一次状态，
> 让支援的人不用来问我。
> 事后我主导了两个改进：① 发布 checklist 增加「schema 变更与代码变更的回滚
> 依赖关系」检查项；② 给该服务加了慢查询 top-N 的发布前后对比面板。
> 反思：这次暴露了我把「回滚代码」当成了「回滚变更」，之后我做任何变更
> 都先写好它的完整回滚步骤才执行。

**追问 1：如果重来一次，你会做什么不同的？**

**追答骨架：** 不要答「更小心」这类空话，答**机制**：
「我会在变更评审时就把 schema 和代码的部署顺序、回滚顺序写进变更单——
事故的根源不是手抖，是我们的发布流程允许两个耦合的变更分开回滚。
事实上事后我推动的 checklist 就是把这个教训固化成了流程。」
（面试官这一问是在测：你把事故归因于个人英雄主义，还是系统性改进。）

**追问 2：这次事故里你个人犯的错误是什么？**

**追答骨架：** 必须给出真实的、具体的个人责任，不能推给流程或他人：
「索引变更是我评审通过的，我当时只看了它对查询的收益benefit，没有问『回滚代码时
这个索引怎么办』——这是我的评审盲区blind spot，不是流程的错。流程改进只是让下一个人
不用靠记性。」（诚实承认 + 已修复 = 加分；「我没什么错」= 直接减分。）

### B2：讲一次你和同事/上级有技术分歧的经历，最后怎么解决的？

**回答骨架**：分歧点一句话讲清（两个方案各自的合理性都要说，不能把对方讲成傻子）
→ 你做了什么把分歧从「观点对观点」变成「证据对证据」→ 结果 → 关系维护。

**示例（【替换】为你的真实分歧）：**

> 一次评审里，资深同事主张用 Redis 分布式锁distributed lock保证一个资金操作的互斥，
> 我认为应该用数据库行锁row lock+ 唯一约束【替换：你的分歧点】。他的理由是性能和
> 既有习惯，也确实成立。我没有在会上争论到底，而是会后花了半天做了两件事：
> ① 写了一个时序推演文档：Redis 锁在「锁过期但持有者还在跑」时会出现双写dual write，
> 对这个资金场景意味着什么；② 做了行锁方案的压测，证明在我们的实际 QPS 下
> 行锁不是瓶颈。带着这两样东西再聊，他 15 分钟就同意了，还补了一个我没想到的
> 死锁deadlock场景让方案更完整。这件事我的收获是：技术分歧的解法不是说服技巧，
> 是把争论转化reframe成可以验证的事实；以及先承认对方方案的合理部分，
> 对话才不会变成防御战。

**追问：如果你拿出了证据，对方仍然坚持，而他是决策者，怎么办？**

**追答骨架：** 「我会确认自己的关切被完整听到且记录（比如在设计文档里留下
风险条目和我的立场stance），然后 **disagree and commit**——按他的方案全力执行，
不消极怠工，也不到处说『我早说过』。同时我会争取在方案里埋一个可观测点
（比如给锁冲突加监控），让未来的事实来裁决。如果后来证明我错了，
我会主动承认——上次类似情况里，事实证明对方的方案在我们的量级order of magnitude下完全够用，
我从中学到我有过度设计的倾向。」（能举一个「最后是我错了」的例子极加分。）

### B3：讲一次你在 deadline 压力下不得不做取舍trade-off的经历。

**回答骨架**：交付物和硬期限 → 发现来不及的时点（越早发现越加分）→
你怎么把「来不及」转化为**选项**给决策者（而不是自己闷头加班或单方面砍功能）→
砍了什么、为什么砍它、和谁确认的 → 结果 + 事后补回。
（估算estimation与提前暴露的日常方法论——这题的预防版——见 W5。）

**示例（【替换】）：**

> 一个对外承诺的功能要在月底上线，开发到第二周我评估进度时发现按当前范围
> 要晚一周【替换：你的场景】。我没有先加班硬扛，而是当天就把功能拆成
> 「资金正确性相关」和「体验增强」两层，做了一页纸的选项：A 全量延期一周；
> B 按时上线核心层，两个增强项（导出功能和一个管理后台页面）下个迭代补。
> 我带着建议选 B 去找 PM——理由是外部承诺的本质是核心能力而非全部细节。
> PM 同意并去和客户侧同步了预期。最后按时上线，增强项两周后补齐。
> 我的原则是：deadline 危机的第一责任是**尽早把真实进度暴露出来**，
> 让有权决策的人在还有选项的时候做选择；工程师最糟糕的做法是隐瞒进度
> 到最后一天，或者为了赶工静默牺牲质量——尤其在钱相关的系统里，
> 测试和正确性永远不在可砍清单上。

**追问：如果 PM 说「不行，全部都要，deadline 不动」呢？**

**追答骨架：** 「那我会把代价摊开说清：全部都要意味着砍测试覆盖或加班到质量
风险区，我会具体到『这两个场景将不被测试覆盖，出问题的后果是 X』，
请他和我的上级一起对这个风险签字。多数情况下，当风险被具体化到场景和金额，
『全都要』会自己松动。如果组织仍然选择接受风险，那是一个知情决策，
我执行并把风险敞口exposure记录在案、上线后优先补上。」

### B4：讲一次你失败的经历。（Tell me about a time you failed.）

**这题的评分点**：失败是不是真的（砍了水分的「假失败」如「我太追求完美」立即出局）、
归因里有没有你自己、改变是不是可验证。

**示例骨架（【替换】为你的真实失败）：**

> 我曾主导过一个内部工具/重构项目【替换】，三个月后被叫停，我投入的大部分
> 工作没有上线。复盘retrospective下来核心失败原因在我：我在没有充分验证「用户是否真的
> 需要」之前，就按自己认为优雅的方向做了很重的设计——我拿两周做了框架，
> 却没拿两天先做一个丑陋但可用的版本去验证需求。教训固化成了两个习惯：
> ① 之后做任何超过两周的工作，我先交付一个一周内能拿到反馈的最小切片；
> ② 我开始在设计文档最前面强制自己写「如果这个项目失败，最可能的原因」，
> 逼自己在投入前直视风险。后来的【替换：某项目】我就是用最小切片先行，
> 两周就发现方向要调，避免了同样的浪费。

**追问：这次失败对你现在做设计的方式有什么具体影响？**

**追答骨架：** 给一个**最近的、具体的**行为证据，证明改变是真的：
「就在我准备面试的这个项目里也有痕迹：我每实现一个机制都先写失败场景推演
（宕机在哪一行、重试会怎样）再写代码，这个『先写失败可能』的习惯就是那次
项目失败留下来的。」（把行为改变连到手头可展示的证据，说服力最强。）

### B5：讲一次你快速学习一个陌生领域并交付的经历。

**这一题直接用本 repo 作素材（真实且可被深挖验证）：**

> 我为了系统性理解发卡业务，从零搭建了一个 issuer 后端项目：授权、清分入账、
> 账单、还款全链路。学习方法是三层递进：① 先啃领域——四方模型、
> 授权和清分为什么分离、账单为什么要快照snapshot，把行业概念翻译成状态机和时序图；
> ② 再啃工程——幂等idempotency、事务边界、行锁、outbox/inbox，每个机制我都写了
> 「如果去掉会发生什么」的反向推演，确保学的是因果而不是模式名；
> ③ 最后自我审查——模拟「进程在任意一行宕机」做穿越测试，找出并修掉了
> 若干活性缺陷（比如错过定时触发时刻后的补偿compensation）。两个月内从对发卡一无所知
> 到能对链路上每个设计决策说出为什么、以及不这么做会怎样。
> 我的学习模式总结起来是：用「构建一个能跑的东西」倒逼理解，
> 用「反向推演」检验理解不是错觉。

**追问：进了新团队，面对没有文档的遗留系统legacy system，这套方法怎么用？**

**追答骨架：** 「核心不变：构建 + 反推reverse deduction，只是对象从新项目换成存量系统。
具体动作concrete steps：① 先追一条最重要的调用链从入口读到落库，边读边画时序图——
读代码以『一条链路』为单位而不是『一个模块』；② 把我的理解写成文档拿去
让老成员挑错——写错的地方就是我认知的洞，这比问十个问题效率高；
③ 在测试环境里做小型故障演习（杀掉一个 worker 看会发生什么）验证我对
恢复机制的理解。顺带产出的链路文档和图，正好是团队缺的新人材料——
学习过程本身对团队产生交付物。」

---

## 9. 团队 / 心理 / 人际team dynamics（T1–T6）

这一节的题目常常以聊天口吻出现（「平时你们 code review 氛围怎么样？」），
但都是在探测同一件事：**这个人放进团队里，摩擦friction系数高不高**。
回答共同原则：先原则后例子；不贬低任何前同事；展示的是「可预期的成熟行为模式」。

### T1：你的代码被资深同事在 review 里严厉批评，观点还很尖锐，你怎么反应？

**答：**

我的处理顺序是：**先分离情绪和信息，再分类triage处理意见**。

1. 第一反应是不在情绪上回击——尖锐的措辞和意见的正确性是两回事，
   我先把每条意见按「正确性问题 / 风格偏好 / 信息差」分类。
2. 正确性问题：直接认，修，并回复具体的修复而不是「好的」——
   review 里最有价值的就是这类意见，措辞再冲也是在帮我拦 bug。
3. 风格偏好类：如果团队有约定按约定，没有约定且分歧反复出现，
   我会提议把它变成团队 lint 规则或风格指南——把「人对人」的争论
   转化为「规则对代码」，以后谁都不用再消耗感情。
4. 信息差类（对方不知道的上下文导致的误解）：补充上下文，同时反思
   为什么 PR 描述没让他拿到这个上下文——review 误解多数是提交方信息给少了。
5. 如果措辞的尖锐已经影响到协作（比如带人身色彩），我会私下一对一说
   「意见我都接受，但这个说法让我不太舒服」——当面、私下、就事论事。

反过来我给别人 review 时的纪律：评论针对代码不针对人、每条意见带理由或链接、
nit 和 blocker 分级标注、超过三轮来回就转为当面/通话——文字吵三轮不如说五分钟。

**追问：如果他批评的点你确定他错了呢？**

**追答：**

那就更要冷静，因为「确定」也可能是我的错觉。我会用可验证的形式回应：
写一个最小复现/测试用例或引用文档，用事实说明——而不是「我觉得不是这样」。
如果证据摆出后他仍不同意，升级方式是拉第三个熟悉这块的人给意见，
   或按团队的裁决tie-break规则（比如 code owner 决定）。我不会为了和谐直接照改
我认为错误的意见——那是对代码库不负责；但也不会阻塞在争论里，
小事（不影响正确性）我倾向让步，把坚持的额度留给真正重要的事。

### T2：入职后发现接手的模块代码质量很差、没有文档、写它的人已经离职，你怎么办？

**答：**

分三步：**先建理解，再建防护网，最后才谈改造**。

1. **建理解**：用 B5 追问里的方法——追主链路、画时序图、写「我理解的行为」
   文档给周边团队确认。同时从 git 历史和 issue 里做代码考古code archaeology：很多「烂代码」
   是当年某个事故的补丁，删之前必须知道它在防什么（Chesterton's Fence）。
2. **建防护网**：在重构任何一行之前，先给关键路径补 characterization test——
   不是测「它应该做什么」，是钉住「它现在实际做什么」。有了测试网，
   后续任何改动都有回归防线。
3. **渐进改造incremental improvement**：绝不提案「推倒重写」——按改动收益benefit排序，结合正常需求
   顺路重构boy scout rule，每次 PR 保持小步可回滚rollback。
   向上沟通时把技术债翻译成业务语言：「这个模块每次改动要 3 天、别的模块半天，
   而它承载了 X 业务」——用可量化的摩擦成本争取重构时间，而不是抱怨代码丑。

心态上：接手烂代码是后端工程师的常态steady state而不是不幸，我把它当成「快速建立
团队信任」的机会——把没人敢碰的模块变成有测试有文档的模块，
是新人建立话语权最快的路径。（技术债的日常管理体系见 W8。）

### T3：团队里有个同事持续 struggle，任务总是延期，还影响到了你的交付，你怎么处理？

**答：**

原则：**先私下帮，再调整协作方式，把上升当作最后手段且只对事**。

1. 先一对一了解：延期是能力缺口gap（不熟这块技术）、信息缺口（需求没理解对）、
   还是个人状况？三种原因的帮法完全不同，不问就贴「他不行」的标签是团队毒药。
2. 能力缺口：约 pair session、把我踩过的坑写成短文档给他、帮他把大任务拆小
   （struggle 的人往往是被任务粒度granularity压垮的，拆成两天一个的交付物，
   反馈循环feedback loop短了状态就会好转）。
3. 同时保护我自己的交付：把依赖关系显式化——在计划里标注「我的 X 依赖他的 Y」，
   如果 Y 有风险，尽早解耦（加 stub/契约先行，各自并行）——
   让帮助他和保护交付不冲突。
4. 如果持续帮助后仍无改善且影响面blast radius扩大，我会和 lead 沟通，
   但内容是**事实与影响**（「Y 已延期两周，导致 X 无法联调」）而不是评价
   （「他不行」）——人事判断是 lead 的职责，我提供事实。

**追问：你自己成为那个 struggle 的人的时候呢？**

**追答：**

反过来用同样的原则：**尽早暴露，具体求助**。我给自己的规则是：卡住超过
半天到一天（视任务而定）必须求助，并且求助时带上「我试了 A、B，卡在 C」——
让帮我的人五分钟就能接上上下文。延期风险在变成事实之前就要同步给依赖方，
给别人留调整空间。掩盖 struggle 才是真正伤团队的行为，暴露它只是伤自尊——
这两个代价我分得很清。

### T4：金融系统的 on-call 半夜报警、大促全员待命，你怎么和这种压力相处？

**答：**

分「事中」和「日常」两层。

**事中**：压力最大的时刻恰恰是最不能靠肾上腺素的时刻。我的做法是把
判断流程**外置**——好的 runbook 和事故分级标准就是为了让凌晨三点的我
不需要聪明：先看哪个面板、什么条件下回滚、什么级别叫醒谁，全部照单执行。
事中只做止血mitigation决策，根因root cause留给白天的脑子。我在自己项目里给每类 worker 写
排障笔记，就是在练这个习惯。（前 15 分钟怎么走的完整版见 P3。）

**日常**：三件事让 on-call 可持续——
① 每次报警都要有下文：要么修根因，要么调阈值threshold，「报了又不用管的警报」
是压力的最大来源，我会主动清理 alert 噪音；
② 演习game day：故障注入过的场景，真发生时就不是压力而是流程；
③ 诚实的复盘retrospective文化：无责复盘blameless postmortem 让事故变成组织资产，
如果事故会导致追责，人就会隐瞒小问题直到它变成大问题。
个人层面，值班周我会主动降低其他承诺密度，保证睡眠冗余redundancy——
压力管理的大头其实是精力管理。

### T5：需求方（PM/业务）需求模糊还频繁变更，你怎么应对？

**答：**

我把「需求模糊」当作**我的输入问题**来解，而不是抱怨对方的输出问题。

1. **用具体案例逼出精确**：拿到模糊需求，我不问「能不能说清楚点」，
   而是构造具体场景追问scenario-based question——「用户还款 5,000 円但账单是 4,800 円，多的 200 怎么处理？」
   具体案例会立刻暴露没想清楚的分支。我项目里每个功能都先写
   「模拟时序」再写代码，就是同一方法。
2. **把理解写下来确认**：一页纸的行为描述（输入→输出→边界情况）发给需求方
   确认后再动工——写下来的分歧在开工前浮现，成本是最低的。
3. **对变更分级响应**：影响数据模型/资金语义的变更要正式评审（这类改错代价大），
   展示层变更快速接受。频繁变更多数时候说明业务本身在探索期discovery phase，
   那么架构上我会把「稳定的核心」（账务、状态机）和「易变的边缘」（规则、展示）
   分层——核心慢改、边缘快改，变更就不再是痛苦而是常态steady state。
4. 心态：需求变更不是 PM 的失职，是市场信息到达的形式。工程师的价值
   不是抵抗变更，是让系统的变更成本结构和业务的变更频率结构对齐。

### T6：你平时怎么用 AI 编码工具？金融系统里怎么保证 AI 产出的质量？

（2026 年的面试几乎必问，答得好是差异化项——既不能表现成抵触新工具，
也不能听起来把判断外包给了 AI。）

**答：**

我把 AI 当「极快但需要监督的结对对象」，边界很明确：

1. **用在哪**：样板代码、测试用例的边界枚举（AI 特别擅长帮我穷举
   case）、陌生 API 的用法探索、文档初稿、以及「换个视角perspective review 这段代码」。
   我准备面试的这个项目就是这个工作流：设计与取舍全部我拍板final say，
   AI 加速验证和初稿，我逐行改写定稿。
2. **责任模型不变**：提交者对代码负全责——「这是 AI 写的」永远不是
   缺陷的借口。AI 产出我按「陌生人的 PR」标准 review；资金语义相关的
   代码（计息、幂等idempotency、事务边界）我必须能逐行解释为什么这么写。
3. **硬红线red line**：生产数据、密钥、客户信息不进 prompt——等价于发送给
   外部服务，PCI/个人信息保护的合规逻辑直接适用；AI 生成的 SQL/DDL
   进生产前和人写的走同一套评审闸门gate（E9 的执行策略标注一视同仁）。
4. **质量兜底fallback靠测试不靠信任**：行为用测试钉死（幂等、并发、边界），
   而测试的断言我要亲手确认有意义——AI 会写出「跑得过但什么都没验证」
   的测试，这是我见过它最隐蔽的坑。

一句话立场stance：AI 改变我的产出速度，不改变我对正确性的所有权。
金融后端恰恰是「验证成本高于生成成本」的领域——懂领域、会验证的
工程师，价值反而被 AI 放大。（效率与能力维度dimension的另一半——它真正提效在哪、
带来什么新问题——见 W7，两题拼成完整的 AI 观。）

**追问：团队里有人过度依赖 AI，提交自己讲不清楚的代码，你怎么办？**

**追答：**

和 T1 的 review 原则同构，对事不对人：review 时请作者走读关键路径，
讲不清楚的代码按「未完成」处理打回——这个标准与是否用了 AI 无关，
人肉抄来讲不清的代码同样打回。如果发现是团队级趋势，我会提议把
「作者能解释每一行」写进 review checklist，并主动分享我自己的工作流
（AI 起草 + 人负责），把讨论从「要不要禁工具」转向「建立用法纪律」——
工具没有错，弃权才有错。

---

## 10. 国际化 / 动机 / 日本职场（G1–G5）

### G1：为什么想加入 PayPay Card？（最重要的一题，必须打磨到脱口而出）

**答（中文完整版）：**

三层理由，从业务到技术到个人：

1. **业务位置独特**：日本正处在政府推动的 cashless 转型中段transition phase（支付比率从
   两成级别爬到四成、目标八成，锚点数字reference figure面试前更新），而 PayPay 集团站在
   这个转型的最前排——码支付覆盖场景，卡片承载授信，「PayPayクレジット」
   把两者缝合。我想做的不是又一个 Web 服务，是**社会级资金基础设施**，
   这里是日本离这件事最近的位置之一。
2. **技术问题是我想解的类型**：发卡系统是「正确性和规模同时苛刻」的领域——
   钱一分不能错，量又在集团流量入口的尺度上；还叠加一层遗留系统现代化的
   工程挑战（这是最能沉淀架构判断力build lasting architectural judgment的场景）。我为准备这个方向做了一个
   issuer 全链路项目，做得越深越确认这类问题是我愿意长期钻的。
3. **个人匹配**：我需要一个国际化工程团队（英语工作环境对我是加分项），
   也认同集团「先做大规模再优化盈利」的产品节奏。我评估过自己的技能栈
   （Java/Spring、并发与一致性、消息与异步）和 JD 的重合度，这不是海投，
   是定向准备了几个月的选择。

**英文短版（30 秒，先讲这个）：**

> I want to work on financial infrastructure at real scale, and PayPay is
> in a unique position: the largest cashless ecosystem in Japan, with the
> card business providing the credit backbone behind it. Card issuing is a
> domain where correctness and scale are both non-negotiable — that's
> exactly the kind of engineering I want to go deep on. I've actually spent
> the past months building an issuer-side payment system end-to-end to
> prepare for this domain, so this application is a deliberate choice,
> not a broad one.

**追问 1：为什么不去メガバンク系或者外资科技公司？**

**追答：**

不贬低任何一边，讲匹配度：传统金融机构的强项是稳定和体量，但技术决策
链路长、现代化包袱重，工程师对架构的影响半径小；外资大厂工程文化好，
但在日支付业务多数不是主战场，做的往往是全球产品的本地化边缘。
PayPay Card 的独特点是**两者的交集**：有金融牌照业务的严肃性，
又有互联网公司的工程文化和迭代速度，而且支付就是主业本身——
我的工作直接落在公司核心价值链上。我选的不是「最好的公司」，
是「我的技能能产生最大杠杆leverage的位置」。

**追问 2：你对我们的业务/技术了解到什么程度？**

**追答：**

分三层，每层带一个研究痕迹，最后交出诚实边界：

1. **集团结构**：PayPay 码支付（注册用户 6,000 万量级order of magnitude）与 PayPay カード
   （有效会员 1,000 万+ 量级）咬合——卡是生态的授信底座，
   「PayPayクレジット（旧あと払い）」把授信直接嵌进扫码体验，
   没有实体卡也在使用卡公司的授信。对后端的含义：同一套额度和账务
   要同时服务卡组织报文和自家 App 流量，两者的幂等键idempotency key（网络交易标识 vs
   客户端 idempotency key）、限流rate limiting策略、峰值形状（营销日历驱动的尖峰）
   完全不同。
2. **行业背景**：EC 加盟店已被要求在 2025 年 3 月底前全面导入 EMV 3-DS，
   信用卡不正利用被害额在年间 500 亿円量级（锚点数字）——安全与风控
   对发卡行是现在进行时，不是教科书章节。
3. **技术挑战推断**：从公开信息推断两大工程主题——遗留系统现代化，
   以及大促（超还元祭型）营销尖峰下的授信流量——正好对应我准备的
   S3 迁移题和 S1 积分题。

结尾交底：「以上来自公开资料和我自己的推演，内部实际架构一定有很多
我不知道的约束——这正是我逆质问想请教的部分」（顺势自然带回bridge to R 节，
把「被考」转成「对话」）。

### G2：为什么选择在日本工作/生活？（国际候选人几乎必问）

**答（骨架skeleton + 要点，按你的真实情况填充）：**

这题考的是**稳定性**——公司投资签证和入职成本，最怕两年后你说「还是回国吧」。
回答三要素：来的理由（拉力pull factors而非推力push factors）、留的证据、长期打算。

> 我选择日本首先是职业理由：〔你的领域〕在日本正处于结构性的转型期
> （对支付而言就是 cashless 化），这种「成熟社会 + 剧烈数字化」的组合
> 提供的工程问题在别处不可复制。其次是已验证的适应：我已经在日本
> 生活/学习/工作了〔时长〕，语言在持续投入（〔JLPT 等级或现状〕），
> 生活基础稳定〔按实际情况：家庭、社群等〕。长期上我把日本作为职业发展的
> 基地来规划，这也是我选择转职到贵社这种可以长期成长的平台、
> 而不是短期高薪岗位的原因。

红线red line：不要把回答建立在对母国的否定上（面试官听起来像推力驱动，推力会变），
全部用拉力表述；不要过度承诺（「一辈子留在日本」听起来反而假），
「以此为基地长期规划」就是恰当的强度。

### G3：我们的团队工作语言是英语，同事一半以上是非日语母语者，你怎么在多语言环境下保证沟通质量？

**答：**

我把多语言环境当工程问题处理——降低对语言能力的依赖，提高对**沟通结构communication structure**的依赖：

1. **文字优先，异步优先**：重要的设计讨论我都会落成文档/图，口头结论
   24 小时内我补一条书面 summary 让各方确认——非母语环境里，
   「我以为他同意了」是最贵的 bug，书面确认是最便宜的测试。
2. **图和代码是第三语言**：时序图、状态机、伪代码在任何母语的工程师之间
   无损传输。我准备面试的项目里所有设计都配图，就是长期这么工作的习惯。
3. **会议纪律**：听不清就当场确认（「Let me repeat back what I understood…」），
   绝不为了面子装懂——装懂的成本会在两周后的联调里加倍偿还。
   我说英语追求的是精确而不是漂亮，短句、术语准确、结论先行。
4. **对日语侧的尊重**：和日语母语的业务/运营同事协作时，我主动用日语
   〔按实际水平表述〕，关键术语我维护自己的中英日对照表（我的项目里
   真的有一份 trilingual glossary）——语言在哪一侧不便，桥就搭在哪一侧。

### G4：日本企业的决策文化（根回し、稟議、合意形成）和你的工作风格冲突吗？

**答：**

不冲突，而且我认为工程师群体对「根回し」有一个普遍的误读——
把它理解成低效的官僚流程bureaucratic process。我的理解：**根回し的本质是把冲突前置front-load到成本最低的
阶段解决**，这和好的工程实践完全同构：设计评审前先和关键 reviewer 一对一
对齐，就是根回し；RFC 文档先小范围传阅再大会讨论，也是根回し。
美式「会上激辩」和日式「会前对齐」只是冲突消解的时点不同，
后者对**跨团队、高风险**的决策反而更稳。

我的实际做法：大的技术提案，我会先和受影响最大的两三个人单独聊，
吸收反对意见改进方案，正式评审时提案已经带着「已知反对意见与回应」——
会议变成确认而不是遭遇战。同时我会守住一条工程原则engineering principle：合意形成不能变成
无人决策，安全/正确性问题上如果各方都在等空气，我会主动把问题书面化、
标明期限和默认方案，推动一个明确的决定出现。适应文化和放弃判断是两回事。

### G5：说说你的转职理由，以及 5 年后你想成为什么样的工程师？（日本面试官高度关注一贯性）

**答（骨架，按真实经历填充）：**

转职理由的答法：**用「向往」解释，用事实支撑，不贬低现职**。

> 现职给了我扎实的〔你的积累：如 Java 后端 / 高流量系统〕基础，我很感谢。
> 转职的原因是方向性的：我确认了自己想深耕的领域是〔支付/金融基础设施〕，
> 而现职的业务重心不在这里，这个 gap 靠内部转岗解决不了。过去几个月我
> 用业余时间构建了一个发卡全链路项目来验证这不是一时兴起——
> 学得越深越确认方向。所以这次转职是「向着明确方向的移动」，
> 不是「离开什么」。

5 年图景（答「深度路线」通常最稳，除非你真想做管理）：

> 5 年后我想成为发卡/支付领域的 domain expert 型工程师——不只是会写代码，
> 而是对「钱在系统里怎么流、哪里会出错、监管要求什么」有体系化判断，
> 能主导一个核心子系统（比如授权或账务）的架构演进architecture evolution，并且开始带人：
> 把领域知识变成团队资产（文档、评审标准、新人培养），这也是我准备
> 面试时大量写文档的原因——我相信能教会别人才算真的懂。
> 中间里程碑：前 1–2 年成为团队里对负责模块最可靠的人，
> 3 年左右扩展到跨模块的设计影响力。

一贯性consistency检查（面试官会做的事，你先自查）：转职理由（想深耕支付）↔ 志望动机
（PayPay Card 是支付核心）↔ 5 年规划（支付领域专家）——三点必须在一条直线上，
任何一点跑偏（比如 5 年规划突然说想做 AI）都会让全部回答失去可信度。

---

## 11. 逆质问（R1–R8）

逆质问是面试的一部分，评分点：你关心的东西暴露你的层次。
原则：问**你真的想知道且对方才能回答**的问题；每轮准备 3 个，按面试官角色选用；
问题里嵌入你的研究痕迹（但不炫技）。

**对工程师/tech lead 问：**

- **R1**：「授权链路的 p99 延迟预算在团队内部是怎么分配的？外部依赖（风控、
  信用情報機関）占掉多少，超预算时的降级graceful degradation决策由谁做？」
  ——暴露你懂延迟预算是分配问题、降级是权责问题。
- **R2**：「遗留系统现代化目前走到哪个阶段？新旧系统并存期的对账reconciliation和回切
  机制是怎么设计的？」——直击他们最痛的日常，也验证 S3 准备的方向。
- **R3**：「团队的事故复盘postmortem文化是什么样的？最近一次让大家学到最多的事故
  是什么类型？」——考察 blameless 文化是否真实存在，回答的坦诚度
  也反过来告诉你这个团队值不值得加入。

**对 engineering manager 问：**

- **R4**：「新人入职后的前三个月，团队怎么定义『上手成功』？有没有
  ramp-up 的结构化支持（mentor、文档、首个任务的选法）？」
- **R5**：「团队目前在『交付新功能』和『偿还技术债/迁移』之间的投入比
  大概是多少？这个比例是怎么被守住的？」——测组织是否真的给迁移留了空间
  （S3 里说的组织风险，反向验证）。

**对 HR/final 轮问：**

- **R6**：「工程师的评价体系里，『把系统做稳』这类不产生新功能的贡献
  是怎么被看见和奖励的？」——金融系统团队健康度的试金石litmus test。
- **R7**：「团队的语言构成实际是什么样的？工程讨论、文档、值班沟通
  各自用什么语言？」——比笼统问「需要日语吗」精确得多。
- **R8**：「从贵社的角度看，这个 position 在一年后交付了什么样的结果，
  会被认为是一次非常成功的招聘？」——把视角perspective翻转到对方的成功定义，
  几乎总能引出 JD 上没写的真实期待。

**不要问的**：加班多不多（换成 R3/R6 侧面看文化）、薪资细节（留给 offer 阶段）、
官网五分钟能查到的事实（暴露没做功课）、以及任何 yes/no 就能回答完的问题。

---

## 12. 考前清单

- [ ] D 节：D1 的钱流和四个核心过程能白板画出；D2–D6 每题的工具链、
      失效清单、概念阶梯能脱稿列全，每题至少讲得出一个反向事实。
- [ ] 锚点数字reference figures用最新公开资料刷新（PayPay 用户数/卡会员数/cashless 比率/
      不正利用被害额/interchange 公开料率——用在 G1 及其追问和 D1）。
- [ ] E 节：每题练出「60 秒结论先行」的短版——通用技术题先给结论，
      面试官想深挖自然会追问；确认每题至少能讲一个「不这样会怎样」的反例counterexample。
- [ ] C 节：五道手写题**真的动手写一遍**（看会 ≠ 写得出）；LRU 和
      阻塞队列要能 15 分钟内白板写对，`while` 条件等待这类淘汰点零失误。
- [ ] P 节：每题的「顺序」就是分数——止血mitigation先于根因root cause、堵源头先于修存量数据existing data、
      回滚rollback先于 fix forward；给 qa bank 第 12 节的每个项目排障场景
      配一个本节框架的开场白，形成「方法论 + 实例」的组合拳。
- [ ] W 节：W1 的七步流程当骨架skeleton背熟（它是 hiring manager 综合题的目录页）；
      W2 的四维技术选型technology selection、W5 的低估三成因、W7 的提效五项/问题五项能脱稿列举；
      每题准备一个自己经历里的真实例证（哪怕来自本准备项目）。
- [ ] S 节：三道设计题各自在白板/纸上**限时 40 分钟**完整演练一遍，
      录音回听——书面能写和口头能讲是两个技能；S3 的六个深挖专题（S3-a〜f）
      按弱项抽查，重点自测失败矩阵（S3-d）能不能脱稿画出来。
- [ ] B 节：素材矩阵五个故事全部换成真实经历，中英各一版，
      每个故事被追问「你个人的错误是什么」都有诚实答案。
- [ ] G1/G5：中英文版脱口而出，三点一线（转职理由↔志望动机↔5年规划）自查通过。
- [ ] R 节：按已知的面试官角色每轮选定 3 问。
- [ ] 交叉复习：本文 D/S 节多处挂回本项目机制——确认每处「挂回」
      在 [`interview-qa-bank-cn.md`](interview-qa-bank-cn.md) 里的对应深挖题也能答。
