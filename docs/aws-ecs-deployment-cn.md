# AWS ECS Deployment Notes

> 目标：从零理解“这个 Spring Boot 信用卡后端如果上 AWS，大概会被拆成哪些云资源、请求怎么走、发布怎么做、哪里会出事故、interview 中怎么回答”。
>
> 这不是 AWS 百科。本文只覆盖把本项目从本地 `docker compose` 形态部署到 AWS ECS/Fargate 时最核心、最容易被问、最容易出错的部分。

## 1. 先用一句话建立心智模型

本地开发时，我们用一台电脑上的 `docker compose` 启动：

- Spring Boot 应用
- MySQL
- Kafka
- Redis

上 AWS 以后，通常不会把这些东西都塞进一台机器，而是变成：

- Spring Boot 应用：打成 Docker image，放进 ECR，再由 ECS/Fargate 运行多个副本。
- MySQL：换成 Amazon RDS MySQL。
- Kafka：换成 Amazon MSK，或者公司已有的托管 Kafka。
- Redis：换成 Amazon ElastiCache for Redis/Valkey。
- 日志、指标、告警：进入 CloudWatch。
- 密码、连接串：放进 Secrets Manager 或 SSM Parameter Store。
- 部署流水线：由 CodePipeline/CodeBuild 或 GitHub Actions 推动。
- 资源定义：用 CloudFormation、CDK、Terraform 等 IaC 管理。

可以先把 AWS 理解成“把本地 Docker Compose 里的每个容器和网络配置，替换成可扩缩、可监控、可权限控制的托管资源”。

## 2. 本项目的真实结构

本项目当前是一个单体 Spring Boot 后端，但内部已经有比较接近生产系统的可靠性结构：

```text
client
  -> Spring Boot API
      -> MySQL 8
          - authorization_requests
          - credit_accounts
          - card_transactions
          - outbox_events
          - inbox_events
          - delay_jobs
          - statements
          - ledger_entries
      -> Redis + Caffeine snapshot cache
      -> Kafka topics
          - authorization-events
          - transaction-events
          - statement-events
          - repayment-events
      -> simulated external risk service
```

关键运行时特征：

- Java 21 + Spring Boot 3.x。
- 主应用监听 `8080`。
- MyBatis 负责核心 SQL，能明确看到 `SELECT ... FOR UPDATE`、唯一约束和事务边界。
- Liquibase 负责数据库 schema migration。
- Kafka producer 配置了 `acks=all` 和 `enable.idempotence=true`。
- Kafka consumer 手动 ack，按 record 处理。
- Transactional Outbox 负责可靠发布领域事件。
- Consumer Inbox 负责消费端 idempotency。
- DelayJob 负责未来业务动作，例如授权过期、自动还款。
- Caffeine L1 + Redis L2 只缓存低风险 snapshot，不做资金真相来源。
- Actuator 暴露 `health`、`metrics`、`liveness`、`readiness`。

所以部署到 AWS 时，重点不是“怎么跑一个 Java 容器”这么简单，而是要保护这些真实工程边界：

- `transaction boundary` 仍然在服务层。
- MySQL 仍然是资金状态的 source of truth。
- Kafka 仍然是 at-least-once delivery，不保证 exactly-once business effect。
- Redis 仍然只是 cache，不是强一致业务存储。
- Outbox/Inbox/DelayJob 仍然是应用层可靠性机制，不会因为上了 AWS 自动消失。

## 3. AWS 基础概念：先把名词讲人话

### 3.1 Region 和 Availability Zone

`Region` 是 AWS 的地理区域，例如东京、新加坡、弗吉尼亚。

`Availability Zone`，简称 AZ，是一个 Region 里的独立机房区域。生产系统通常至少跨两个 AZ：

```text
ap-northeast-1
  ├─ ap-northeast-1a
  ├─ ap-northeast-1c
  └─ ap-northeast-1d
```

为什么要关心 AZ？

- 如果应用只在一个 AZ，那个 AZ 出问题，服务可能整体不可用。
- ALB、ECS Service、RDS Multi-AZ、ElastiCache replication 都会围绕 AZ 设计。

interview 高分点：

> 我不会只说“部署到 AWS”。我会明确说应用任务分布在至少两个 AZ 的 private subnets，ALB 在 public subnets，RDS 使用 Multi-AZ 或至少有明确的恢复策略。

### 3.2 VPC、Subnet、Security Group

`VPC` 可以理解成你在 AWS 里的私有网络。

`Subnet` 是 VPC 里的网段，通常分成：

- public subnet：放 ALB、NAT Gateway 这类需要和外部互通的资源。
- private subnet：放 ECS tasks、RDS、ElastiCache、MSK 这类不该直接暴露到互联网的资源。

`Security Group` 是资源级防火墙。它不是“机器内部防火墙”，而是 AWS 网络层的 allow list。

本项目推荐网络形态：

```text
Internet
  -> ALB public subnets
      -> ECS/Fargate tasks private subnets
          -> RDS MySQL private subnets
          -> ElastiCache private subnets
          -> MSK private subnets
```

一个非常实际的 Security Group 规则：

| 资源 | 允许入站 | 来源 |
| --- | --- | --- |
| ALB | `443`, 可选 `80` | Internet |
| ECS task | `8080` | ALB security group |
| RDS MySQL | `3306` | ECS task security group |
| ElastiCache | `6379` | ECS task security group |
| MSK | Kafka broker ports | ECS task security group |

核心思想：

> 不要让 RDS、Redis、Kafka 暴露公网。应用访问它们，不代表所有人都能访问它们。

### 3.3 IAM Role

`IAM Role` 是 AWS 权限身份。ECS task 会挂一个 task role，让容器里的应用能访问特定 AWS 资源。

本项目可能需要的权限：

- 从 Secrets Manager 读取数据库密码。
- 写 CloudWatch Logs。
- 可选：推送自定义 CloudWatch Metrics。
- 可选：访问 SSM Parameter Store。

高分回答：

> 我会给 ECS task 最小权限原则的 IAM role，不会把长期 access key 写进镜像或环境变量。

### 3.4 ECR、ECS、Fargate

`ECR` 是 AWS 的 Docker image registry。可以理解成 AWS 版私有 Docker Hub。

`ECS` 是容器编排服务，负责运行、替换、扩缩容容器。

`Fargate` 是 ECS 的一种运行方式：你不需要自己管理 EC2 机器，只需要声明 CPU、memory、image、端口、环境变量，AWS 负责底层机器。

三者关系：

```text
Docker image
  -> push to ECR
      -> ECS Service reads image
          -> Fargate runs tasks
```

### 3.5 Task Definition、Task、Service

这是 ECS 最容易混的三个概念。

`Task Definition` 是运行模板，像一份“容器说明书”：

- 用哪个 image？
- 分配多少 CPU/memory？
- 暴露哪个端口？
- 有哪些环境变量？
- secrets 从哪里读？
- 日志发到哪里？

`Task` 是这个模板运行出来的一个真实容器副本。

`Service` 是长期运行控制器：它负责保证始终有 N 个 task 活着，并在新版本发布时滚动替换。

类比本项目：

```text
Task Definition:
  mini-card-payment-system:42

Task:
  task-a running image tag 2026-06-24-abc123
  task-b running image tag 2026-06-24-abc123

Service:
  desiredCount = 2
  keep 2 healthy tasks behind ALB
```

interview 里可以这样答：

> Task Definition 是 immutable deployment blueprint；Task 是一次运行实例；Service 管理 desired count、rolling deployment、health check 和 service discovery。

### 3.6 ALB 和 Target Group

`ALB` 是 Application Load Balancer，负责接收 HTTP/HTTPS 请求并转发到后端 task。

`Target Group` 是 ALB 后面的一组目标。对 ECS/Fargate 来说，目标通常是 task 的 private IP + port。

请求路径：

```text
POST /api/authorizations
  -> ALB :443
      -> target group health check says task-1 is ready
          -> task-1 :8080
```

ALB 不理解你的业务事务，只理解 HTTP、健康检查、超时和负载分发。

## 4. 本地 Docker Compose 到 AWS 的映射

| 本地组件 | AWS 推荐映射 | 关键理解 |
| --- | --- | --- |
| Spring Boot container | ECR + ECS Service on Fargate | 多个 task 副本，滚动发布，ALB 转发 |
| MySQL container | Amazon RDS MySQL | 托管备份、监控、Multi-AZ，但 schema 和事务设计还是你的责任 |
| Kafka container | Amazon MSK | 托管 broker，不取消 Outbox/Inbox/idempotency |
| Redis container | ElastiCache for Redis/Valkey | cache，不是资金 source of truth |
| `.env` / local env vars | ECS environment + Secrets Manager | 非敏感配置可环境变量，密码放 secrets |
| Docker healthcheck | ECS health check + ALB health check + Actuator | 区分 liveness 和 readiness |
| local logs | CloudWatch Logs | 每个 task stdout/stderr 进入 log group |
| manual start | CodePipeline / GitHub Actions | build、test、push image、deploy |
| manual docs | CloudFormation/CDK/Terraform | 网络、ECS、RDS、MSK、ElastiCache 可复现 |

关键 interview 观点：

> 上 AWS 不是把 Docker Compose 原样搬上去，而是把“计算、网络、数据库、消息、缓存、观测、密钥、发布”拆给对应托管服务，同时保留应用层的事务和幂等设计。

## 5. ECS/Fargate 如何运行这个项目

### 5.1 Docker image

典型流程：

```bash
./gradlew test
./gradlew bootJar
docker build -t mini-card-payment-system:local .
docker tag mini-card-payment-system:local \
  123456789012.dkr.ecr.ap-northeast-1.amazonaws.com/mini-card-payment-system:2026-06-24-abc123
docker push 123456789012.dkr.ecr.ap-northeast-1.amazonaws.com/mini-card-payment-system:2026-06-24-abc123
```

实际公司里通常由 CI/CD 执行，不会手工在本机执行。

### 5.2 Task Definition 示例

下面是简化过的 task definition 片段，不是可直接复制的完整模板：

```json
{
  "family": "mini-card-payment-system",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "networkMode": "awsvpc",
  "containerDefinitions": [
    {
      "name": "app",
      "image": "123456789012.dkr.ecr.ap-northeast-1.amazonaws.com/mini-card-payment-system:2026-06-24-abc123",
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "KAFKA_BOOTSTRAP_SERVERS",
          "value": "b-1.msk:9092,b-2.msk:9092,b-3.msk:9092"
        },
        {
          "name": "REDIS_HOST",
          "value": "mini-card-cache.xxxxxx.apne1.cache.amazonaws.com"
        },
        {
          "name": "REDIS_PORT",
          "value": "6379"
        },
        {
          "name": "EXTERNAL_RISK_BASE_URL",
          "value": "https://risk-api.internal"
        }
      ],
      "secrets": [
        {
          "name": "DB_URL",
          "valueFrom": "arn:aws:secretsmanager:ap-northeast-1:123456789012:secret:mini-card/db-url"
        },
        {
          "name": "DB_USERNAME",
          "valueFrom": "arn:aws:secretsmanager:ap-northeast-1:123456789012:secret:mini-card/db-username"
        },
        {
          "name": "DB_PASSWORD",
          "valueFrom": "arn:aws:secretsmanager:ap-northeast-1:123456789012:secret:mini-card/db-password"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/mini-card-payment-system",
          "awslogs-region": "ap-northeast-1",
          "awslogs-stream-prefix": "app"
        }
      }
    }
  ]
}
```

对本项目来说，最重要的环境变量是：

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `KAFKA_BOOTSTRAP_SERVERS`
- `REDIS_HOST`
- `REDIS_PORT`
- `EXTERNAL_RISK_BASE_URL`

不要把数据库密码 bake 进 image，因为 image 可能被多人和多个环境复用。

### 5.3 Service 配置重点

ECS Service 需要关心：

- desired count：生产至少 2 个 task，分布在多个 AZ。
- deployment strategy：滚动发布或 blue/green。
- health check grace period：给 Spring Boot 启动、Liquibase、连接池初始化留时间。
- ALB target group：把健康 task 暴露给外部请求。
- autoscaling policy：根据 CPU、memory、ALB request count、业务自定义指标扩缩容。

本项目如果是生产服务，起步可以这样：

```text
desiredCount = 2
minimumHealthyPercent = 100
maximumPercent = 200
healthCheckGracePeriodSeconds = 120
```

含义：

- 发布时先启动新 task。
- 新 task 健康后，再逐步下线旧 task。
- 任何时刻尽量不要少于当前健康容量。

## 6. 一次真实请求在 AWS 上怎么走

以授权请求为例：

```http
POST /api/authorizations
Idempotency-Key: checkout-20260624-001
Content-Type: application/json

{
  "cardId": "card-123",
  "amount": 100,
  "currency": "JPY",
  "merchantId": "merchant-123",
  "merchantCountry": "JP",
  "cardholderCountry": "JP"
}
```

部署到 AWS 后，请求链路变成：

```text
client
  -> Route 53 / DNS
  -> ALB
  -> ECS task:8080
  -> AuthorizationController
  -> AuthorizationService transaction boundary
  -> MySQL RDS
      - insert/read idempotency record
      - SELECT credit account FOR UPDATE
      - update reserved amount
      - insert authorization
      - insert outbox event
  -> HTTP response
  -> Outbox worker in another scheduler tick
  -> MSK topic mini-card.authorization-events.v1
  -> Notification/Risk/Ledger consumers
```

注意一个细节：ALB 可以把请求转发给 `task-a`，而下一次重试可能转发给 `task-b`。所以应用不能依赖 JVM 内存锁保护资金状态。

正确保护点仍然是：

- idempotency unique key 在 RDS。
- `SELECT ... FOR UPDATE` 锁住 credit account row。
- 事务提交时一起写业务状态和 Outbox。
- 消费端用 Inbox 做 idempotency。

interview 高分回答：

> ECS 横向扩容后，同一张卡的两个授权请求可能落到不同 task，所以不能用 `synchronized`、本地 `Lock` 或本地 cache 判断余额。资金一致性必须靠数据库唯一约束、row lock、transaction boundary 和幂等表保护。

## 7. Health Check：不能只会说 `/health`

本项目已有几个健康相关入口：

```http
GET /api/health
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
```

它们应该承担不同职责。

### 7.1 Liveness

`liveness` 回答的问题是：

> 这个进程还活着吗？如果它卡死了，要不要重启？

适合用于 ECS container health check。

如果 liveness 过度依赖 MySQL/Kafka，可能导致连锁重启：

```text
RDS 短暂抖动
  -> 所有 task liveness 失败
  -> ECS 同时重启所有 task
  -> 连接风暴
  -> RDS 更慢
```

所以 liveness 应该轻量，重点检查 JVM/进程是否还能响应。

### 7.2 Readiness

`readiness` 回答的问题是：

> 这个 task 现在可以接真实流量吗？

适合用于 ALB target group health check，或者至少作为发布判断依据。

本项目 readiness 已经包含 DB，这是合理的：如果 RDS 不可用，授权接口无法安全完成资金状态变更，不应该继续接新授权流量。

但不是所有依赖都必须让 readiness 失败：

| 依赖 | 建议 readiness 策略 | 原因 |
| --- | --- | --- |
| MySQL/RDS | 失败 | 授权、还款、Outbox 都依赖它 |
| Kafka/MSK | 可讨论，不一定失败 | Outbox 可以先落库，短时 Kafka 故障可通过 backlog 恢复 |
| Redis/ElastiCache | 通常不失败 | Redis 是 cache，失败时应回源 DB 或降级 |
| external risk | 通常不直接失败 | 有 timeout、fallback、circuit breaker，需要按风控策略决定 |

### 7.3 具体事故例子

假设一次发布期间有两个 task：

```text
task-old: version 1, healthy
task-new: version 2, starting
```

请求 A：

```text
POST /api/authorizations
Idempotency-Key: checkout-A
cardId=card-123
amount=100 JPY
```

如果 ALB health check 只打 `/api/health`，而这个接口不检查 DB，那么可能出现：

```text
task-new Spring Boot 已经能返回 OK
  -> ALB 把请求 A 发给 task-new
  -> task-new 的 datasource 还没连上 RDS
  -> 请求 A 500
```

更好的策略：

- ECS liveness：轻量检查进程是否活着。
- ALB readiness：检查应用是否准备好接核心流量，至少包含 DB。
- 发布 grace period：给初始化留时间。

## 8. RDS：托管 MySQL 不是“自动正确”

RDS 帮你管理：

- MySQL 实例生命周期。
- 备份和恢复。
- Multi-AZ failover。
- 监控指标。
- 参数组。
- 存储扩容。

但 RDS 不会替你解决：

- 事务边界设计错误。
- 错误的隔离级别理解。
- 忘记唯一约束导致 idempotency 失效。
- 锁顺序不一致导致 deadlock。
- 连接池过大打爆数据库。
- 不兼容 schema migration。

### 8.1 连接池和 task 数量

假设：

```text
ECS desiredCount = 8
Hikari maximumPoolSize = 20
```

那么最坏情况下应用可能打到：

```text
8 tasks * 20 connections = 160 DB connections
```

如果 RDS 实例最大连接数只有 150，再加上 Liquibase、运维连接、只读工具，就可能出现连接耗尽。

interview 回答要具体：

> 我会按 task 数量乘以连接池上限计算 worst-case DB connections，再结合 RDS max_connections、慢查询、锁等待和峰值 QPS 调整。扩容 ECS 不是免费增加数据库吞吐，数据库经常是金融写路径的第一瓶颈。

### 8.2 Liquibase 在 ECS 上的风险

本项目用 Liquibase 管理 schema。开发环境中应用启动时跑 migration 很方便，但生产 ECS 多副本启动时要谨慎。

事故例子：

```text
task-A starts
  -> runs Liquibase changeset 20260624-add-column

task-B starts at almost the same time
  -> also tries to run Liquibase
```

Liquibase 有锁表机制，通常能避免同一个 changeset 被并发执行，但生产发布仍有几个风险：

- migration 时间太长，task readiness 一直失败。
- DDL 锁表，影响在线授权请求。
- 新旧版本代码同时运行时，schema 不兼容。
- 部署回滚了代码，但 schema 已经前进，无法轻易回滚。

更稳的生产做法：

```text
CodePipeline
  -> build image
  -> run tests
  -> run one migration job
  -> deploy ECS service
  -> smoke test
```

也可以让一个 ECS one-off task 专门跑 migration，然后再更新 service。

### 8.3 Expand and Contract 示例

假设要给授权表新增 `risk_decision_reason`，并最终要求非空。

不推荐一步到位：

```sql
ALTER TABLE authorization_requests
ADD COLUMN risk_decision_reason VARCHAR(128) NOT NULL;
```

问题：

- 老数据没有值，DDL 可能失败。
- 旧版本代码不会写这个字段。
- 发布期间新旧 task 并存，旧 task 插入会失败。

更稳的三步：

```text
1. expand:
   ADD COLUMN risk_decision_reason VARCHAR(128) NULL

2. deploy writer:
   新代码开始写 risk_decision_reason
   后台 backfill 老数据

3. contract:
   确认没有 NULL 后再加 NOT NULL 或 check constraint
```

这就是高难度 interview 喜欢听的点：你不仅知道怎么部署，还知道“发布中间态”会发生什么。

## 9. MSK：Kafka 托管后，Outbox 仍然需要

Amazon MSK 可以帮你托管 Kafka broker，但不会把消息语义变成“业务 exactly-once”。

本项目保留 Outbox 是因为状态变更和消息发布必须处理这个经典问题：

```text
AuthorizationService transaction:
  update credit account
  insert authorization
  insert outbox event
  commit

Outbox publisher:
  read pending events
  send to Kafka/MSK
  mark published
```

如果不用 Outbox，可能出现：

```text
线程 A:
  1. RDS 中授权成功并扣减可用额度
  2. 准备发送 Kafka 事件
  3. JVM crash

结果:
  DB 状态已经变更
  但没有事件通知 Notification/Risk/Ledger
```

Outbox 把“业务状态”和“待发布事件”放进同一个 MySQL transaction boundary，使故障后可以重扫补发。

### 9.1 Kafka 不可用时会怎样

具体例子：

```text
请求 A:
  cardId=card-123
  amount=100 JPY
  idempotencyKey=checkout-A

RDS 正常
MSK 暂时不可用
```

执行结果应该是：

```text
1. 授权事务成功提交
2. outbox_events 插入 PENDING
3. API 返回授权结果
4. Outbox publisher 发送失败，增加 attempt 或保留 PENDING
5. CloudWatch 告警 outbox backlog 增长
6. MSK 恢复后 publisher 补发
```

这比“授权请求直接依赖 Kafka 同步成功”更稳，因为资金写路径不应该被短暂消息系统抖动完全阻断。

但也不能无限制接受 backlog：

- 如果 `outbox_events` backlog 超过阈值，要报警。
- 如果 backlog 长时间不下降，要考虑降级、限流或暂停部分非核心流量。
- 如果事件消费者落后，Notification、Risk projection、Ledger projection 都会有 eventual consistency 延迟。

### 9.2 Topic 和 Consumer Group

本项目 topic 可以映射为：

```text
mini-card.authorization-events.v1
mini-card.transaction-events.v1
mini-card.statement-events.v1
mini-card.repayment-events.v1
```

消费者组：

```text
notification-service-group
risk-feature-group
ledger-projection-group
```

即使现在代码还是单体部署在一个 ECS service 里，Kafka 的 consumer group 语义仍然存在。将来拆服务时，这些 group 可以自然迁移到独立 ECS service。

## 10. Redis/ElastiCache：缓存加速，不承担资金真相

本项目缓存的是低风险 snapshot：

- card snapshot
- statement read model

真正资金状态仍然在 MySQL/RDS：

- credit limit
- reserved amount
- posted balance
- authorization state
- repayment state

部署到 AWS 后：

```text
task-a Caffeine L1
task-b Caffeine L1
        |
        v
ElastiCache Redis L2
        |
        v
RDS source of truth
```

### 10.1 多 task 下 L1 cache 的问题

本地只有一个 JVM 时，Caffeine L1 很容易理解。ECS 上有多个 task 后：

```text
task-a has card-123 snapshot in Caffeine
task-b has card-123 snapshot in Caffeine
```

请求 A 落到 `task-a`，更新了账户状态并 after-commit evict Redis。

请求 B 立刻落到 `task-b`，如果 `task-b` 的 L1 还没过期，就可能读到短暂旧 snapshot。

为什么本项目仍然可以接受？

- 缓存只用于低风险读取或辅助 snapshot，不作为最终资金扣减判断。
- 真正授权扣减会走 RDS row lock 和事务。
- L1 TTL 应该短。
- 更新后应尽量做 after-commit eviction。

高分回答：

> 横向扩容后，每个 task 都有自己的 L1 cache，所以我不会把强一致资金判断放进 Caffeine。本项目把 cache 限制在 reconstructable snapshot，并用短 TTL、Redis L2、事务提交后失效降低陈旧读影响。

### 10.2 Redis 故障时的策略

如果 ElastiCache 短暂不可用：

```text
GET /api/statements/{id}
  -> Redis timeout
  -> fallback to RDS
  -> optionally skip cache write
```

对于授权写路径：

```text
POST /api/authorizations
  -> 不应该因为 Redis down 就错误批准或错误拒绝
  -> 资金判断仍然以 RDS locked row 为准
```

所以 Redis 故障更多影响 latency 和 RDS read pressure，而不是资金正确性。

## 11. CloudWatch：要观察什么

CloudWatch 在这里有三类作用：

- logs：每个 ECS task 的应用日志。
- metrics：CPU、memory、RDS、ALB、Kafka、Redis、应用指标。
- alarms：超过阈值时通知人或触发自动动作。

### 11.1 ECS 和 ALB

| 指标 | 为什么重要 |
| --- | --- |
| ECS CPU utilization | 判断 CPU 是否是瓶颈 |
| ECS memory utilization | 防止 OOM 和频繁重启 |
| task restart count | 新版本启动失败或健康检查失败 |
| ALB target 5xx | 应用 task 返回错误 |
| ALB target response time | 请求整体延迟 |
| unhealthy target count | 发布或依赖故障信号 |

### 11.2 RDS

| 指标 | 为什么重要 |
| --- | --- |
| CPUUtilization | SQL 或连接压力 |
| DatabaseConnections | 连接池和 task 数量是否过高 |
| FreeableMemory | buffer/cache 压力 |
| Read/Write IOPS | 存储瓶颈 |
| lock wait / deadlock logs | 金融写路径核心风险 |
| slow query log | 索引和 SQL 退化 |

### 11.3 MSK/Kafka

| 指标 | 为什么重要 |
| --- | --- |
| consumer lag | Notification/Risk/Ledger 是否落后 |
| broker CPU/network | broker 是否过载 |
| produce error rate | Outbox 发布是否异常 |
| under replicated partitions | Kafka 高可用风险 |

### 11.4 ElastiCache

| 指标 | 为什么重要 |
| --- | --- |
| cache hit rate | 缓存是否有效 |
| evictions | memory 是否不足 |
| latency | Redis 是否拖慢读路径 |
| connection count | task 数量和连接池是否合理 |

### 11.5 应用业务指标

建议补充自定义指标：

| 指标 | 示例阈值 |
| --- | --- |
| `authorization.approved.count` | 突然归零报警 |
| `authorization.declined.count` | 突然暴涨报警 |
| `authorization.conflict.count` | idempotency 冲突异常上升 |
| `outbox.pending.count` | 超过 1000 或持续增长报警 |
| `outbox.publish.failure.count` | 连续失败报警 |
| `delayjob.overdue.count` | 过期任务堆积报警 |
| `kafka.consumer.duplicate.count` | duplicate delivery 变多报警 |

interview 里不要只说“我们上 CloudWatch”。要说清楚你看什么指标，以及指标异常意味着什么。

## 12. CloudFormation：把资源变成可复现的代码

CloudFormation 是 AWS 原生 IaC 服务。你写模板，AWS 创建一个 `stack`。

可以把 stack 理解成“一组一起管理的云资源”。

本项目不建议把所有东西塞进一个超大模板。更可解释的拆分：

```text
network-stack
  - VPC
  - public subnets
  - private subnets
  - route tables
  - NAT Gateway

data-stack
  - RDS MySQL
  - DB subnet group
  - DB security group
  - Secrets Manager secrets

messaging-cache-stack
  - MSK cluster or Kafka connection config
  - ElastiCache
  - security groups

app-stack
  - ECR repository
  - ECS cluster
  - task definition
  - ECS service
  - ALB
  - target group
  - CloudWatch log group

pipeline-stack
  - CodePipeline
  - CodeBuild
  - deployment role
```

为什么这样拆？

- 网络和数据库变化少，风险高。
- 应用发布变化多，应该独立。
- Pipeline 权限复杂，单独审计更清楚。
- RDS 删除保护、备份策略不应该被频繁应用发布影响。

高分观点：

> IaC 的价值不只是“一键创建”，而是让网络边界、权限、依赖、告警和发布策略可 review、可回滚、可审计。

## 13. CodePipeline：一次合理的发布流水线

一个最小但靠谱的流水线：

```text
Source
  -> Build
      ./gradlew test
      docker build
      docker push ECR
  -> Migration
      run Liquibase as one-off job
  -> Deploy
      update ECS service task definition
  -> Smoke Test
      GET /actuator/health/readiness
      POST a safe synthetic request if environment allows
  -> Manual Approval for production
```

### 13.1 为什么 migration 在 deploy 前

假设新版本代码需要读新字段 `risk_score_source`。

如果先部署代码再 migration：

```text
task-new receives request
  -> SELECT risk_score_source
  -> column does not exist
  -> 500
```

所以常见顺序是：

```text
backward-compatible schema first
  -> deploy code that can use it
  -> backfill/contract later
```

### 13.2 回滚要分代码和数据库

代码回滚通常比较快：

```text
ECS service -> previous task definition revision
```

数据库回滚很危险：

- DDL 可能不可逆。
- 新代码可能已经写入新格式数据。
- 回滚 schema 可能破坏线上数据。

所以生产发布应该偏向：

- backward-compatible schema。
- feature flag。
- canary 或 blue/green。
- 先扩展，后收缩。
- 避免“必须靠数据库回滚救场”的 migration。

## 14. 两个发布事故例子

### 14.1 事故一：健康检查过早通过

时间线：

```text
10:00:00 task-new starts
10:00:10 /api/health returns OK
10:00:11 ALB marks task-new healthy
10:00:12 request A routed to task-new
10:00:12 Hikari pool still cannot connect to RDS
10:00:12 request A gets 500
```

请求 A：

```text
Idempotency-Key=checkout-A
cardId=card-123
amount=100 JPY
```

问题根因：

- ALB health check 只验证进程，不验证 readiness。
- 新 task 还没准备好接核心写请求。

解决：

- ALB 使用 readiness 语义的 health check。
- 设置 health check grace period。
- readiness 至少覆盖 DB。
- 对 Kafka/Redis/external risk 做有意识的 readiness 策略，而不是全部塞进去。

### 14.2 事故二：不兼容 migration 遇上滚动发布

旧版本代码：

```text
insert authorization_requests(card_id, amount, currency)
```

新 migration：

```sql
ALTER TABLE authorization_requests
ADD COLUMN risk_decision_reason VARCHAR(128) NOT NULL;
```

滚动发布中：

```text
task-old still handles request B
task-new handles request C
```

请求 B 落到旧 task：

```text
Idempotency-Key=checkout-B
cardId=card-123
amount=200 JPY
```

旧 task 执行旧 insert，不写 `risk_decision_reason`，结果：

```text
MySQL rejects insert because NOT NULL column has no default
request B returns 500
```

解决：

```text
1. 先 ADD nullable column
2. 部署新旧版本都兼容的代码
3. backfill 老数据
4. 确认所有 task 已升级
5. 再加 NOT NULL
```

这就是为什么高并发金融系统的发布设计必须考虑“新旧版本同时存在”的窗口。

## 15. 成本和复杂度：为什么不一上来就全套

学习项目可以分阶段：

### 15.1 最小学习版

```text
ECS/Fargate + ALB + RDS + CloudWatch Logs
```

Kafka 和 Redis 仍然用本地或简化替代，先把容器部署、网络、安全组、健康检查跑通。

### 15.2 接近生产版

```text
ECS/Fargate
ALB
RDS Multi-AZ
ElastiCache
MSK
CloudWatch metrics/alarms
Secrets Manager
CodePipeline
CloudFormation/CDK/Terraform
```

### 15.3 生产强化版

```text
multi-AZ everywhere
blue/green deployment
custom business metrics
centralized tracing
WAF
private endpoints
least-privilege IAM
backup and restore drills
load testing
chaos/failure drills
```

interview 中不要虚报：

> 如果我还没亲手用过 AWS，我会说我理解部署拓扑、资源职责和故障模式，并能从最小版逐步演进到生产版，而不是一开始堆满所有服务。

## 16. interview 常见追问与高分回答

### Q1: ECS task definition、task、service 有什么区别？

高分回答：

> Task Definition 是不可变的运行模板，定义 image、CPU、memory、端口、env、secrets、log driver。Task 是这个模板的一次运行实例。Service 是长期控制器，它保证 desired count，挂到 ALB target group，处理 rolling deployment、health check 和自动替换失败 task。对本项目来说，一个 ECS service 会运行多个 Spring Boot task，每个 task 都可能处理授权请求，所以一致性不能依赖 JVM 本地状态。

### Q2: 为什么选择 Fargate，不直接用 EC2？

高分回答：

> Fargate 适合这个学习项目和中小型后端服务，因为它降低了管理 EC2、patch、capacity bin packing 的成本。我们只声明 task 的 CPU/memory 和网络，ECS 负责调度。缺点是单价可能更高，底层可控性更少。如果公司已有大规模容器平台、需要特殊 agent、daemon、GPU、极致成本优化，EC2 launch type 或 EKS 可能更合适。

### Q3: ALB health check 应该打哪个接口？

高分回答：

> 我会区分 liveness 和 readiness。liveness 用来判断进程是否需要重启，应轻量；readiness 用来判断是否可以接真实流量，ALB 更应该使用 readiness 语义。对本项目，RDS 不可用时授权和还款无法安全完成，所以 readiness 应该覆盖 DB。Kafka 可以通过 Outbox 短时缓冲，不一定直接让 readiness 失败；Redis 是 cache，也通常不让 readiness 失败。

### Q4: Kafka/MSK 挂了，授权接口要不要失败？

高分回答：

> 不一定。这个项目的授权事务会在 MySQL 中同时写业务状态和 Outbox event。如果 MSK 短时不可用，授权仍可提交，Outbox publisher 稍后重试补发。真正需要监控的是 outbox pending backlog、publish failure rate 和 consumer lag。如果 backlog 超阈值或持续增长，才考虑限流、降级或暂停部分非核心流量。

### Q5: Redis/ElastiCache 挂了，会不会影响资金正确性？

高分回答：

> 不应该。这个项目只缓存 reconstructable snapshot，例如 statement read model 和 card snapshot。资金扣减和额度判断仍然在 RDS transaction + row lock 中完成。Redis 故障会增加延迟和 RDS read pressure，但不应该造成错误批准或错误拒绝授权。多 task 下每个 JVM 都有自己的 Caffeine L1，因此更不能把强一致判断放进本地 cache。

### Q6: RDS 使用 Multi-AZ 后是不是就不用管数据库风险？

高分回答：

> 不是。Multi-AZ 主要提高可用性和 failover 能力，不会修复应用层错误。我们仍然要设计唯一约束、row lock、锁顺序、事务边界、连接池大小、慢查询、schema migration 和备份恢复演练。尤其是金融写路径，ECS 扩容可能让 DB 连接和锁竞争更严重。

### Q7: Liquibase 在 ECS 多副本中怎么处理？

高分回答：

> 开发环境可以让应用启动时跑 Liquibase，但生产多副本滚动发布更推荐把 migration 做成 pipeline 中的独立步骤或 one-off ECS task。schema change 必须 backward-compatible，尤其要考虑新旧 task 同时存在。比如新增 NOT NULL 字段，应先 nullable expand，再部署 writer，再 backfill，最后 contract。

### Q8: 为什么不把 MySQL、Kafka、Redis 也放进 ECS？

高分回答：

> 可以跑，但通常不推荐作为生产默认选择。数据库、Kafka、Redis 都是有状态系统，需要持久化、备份、复制、failover、监控和升级策略。RDS、MSK、ElastiCache 把大量运维复杂度交给托管服务。ECS 更适合运行 stateless 或 state-light 的应用容器。本项目的状态真相在 RDS，应用 task 可以无状态横向扩展。

### Q9: ECS 横向扩容后，idempotency 怎么保证？

高分回答：

> 请求 A 和重试 A 可能落到不同 task，所以不能靠本地 map 或本地 lock。这个项目用 `Idempotency-Key`、请求指纹、MySQL 唯一约束和事务来保证幂等。并发请求抢同一个 key 时，只有一个能成功创建记录；另一个要么读到已完成结果，要么发现 payload mismatch 返回 conflict。资金账户更新再通过 row lock 串行化。

### Q10: 怎么设计 CloudWatch alarm？

高分回答：

> 我会分基础设施和业务可靠性两层。基础设施看 ECS CPU/memory/restart、ALB 5xx/latency/unhealthy target、RDS connections/CPU/slow query、MSK lag、ElastiCache evictions/latency。业务层看 authorization decline/approve rate、idempotency conflict、outbox pending count、publish failure、delayjob overdue、DLT count。金融系统不能只看机器活着，还要看可靠性队列有没有堆积。

## 17. 最小落地清单

如果真的要把本项目部署到 AWS，建议按这个顺序学和做：

1. 先理解 VPC、subnet、security group，画出 ALB -> ECS -> RDS/Redis/MSK。
2. 把 Spring Boot 打成 Docker image，推到 ECR。
3. 用 ECS/Fargate 跑一个 task，能访问 `/api/health`。
4. 加 ALB target group，验证 readiness 和滚动发布。
5. 接 RDS MySQL，确认 Liquibase 策略。
6. 接 ElastiCache Redis，验证 cache down 时能回源。
7. 接 MSK，验证 Outbox backlog 和 consumer lag。
8. 接 CloudWatch Logs 和基础 alarms。
9. 用 CodePipeline/CodeBuild 自动 build、test、push、deploy。
10. 用 CloudFormation/CDK/Terraform 固化资源。

每一步都要问：

- 请求怎么走？
- 密码在哪里？
- 失败时会怎样？
- 能不能回滚？
- 指标在哪里看？
- 是否破坏 idempotency、row lock、transaction boundary？

## 18. 记忆版总结

最重要的不是记住 AWS 服务名，而是能把它们和后端系统边界对应起来：

```text
ECS/Fargate = 跑 Spring Boot 容器
ALB = 接 HTTP 流量并转发到健康 task
RDS = MySQL source of truth
MSK = Kafka broker，不替代 Outbox/Inbox
ElastiCache = Redis cache，不承担资金真相
CloudWatch = logs + metrics + alarms
Secrets Manager = 密码和敏感配置
CloudFormation = 资源可复现
CodePipeline = 发布流程自动化
```

真正硬核的回答要落到本项目：

> 授权请求进来后，即使 ECS 有多个 task、ALB 随机分发、Kafka 短时不可用、Redis 短时不可用，资金正确性仍然靠 RDS 中的 idempotency unique constraint、row lock、transaction boundary 和 Outbox/Inbox 保护。AWS 提供运行平台和托管资源，但业务一致性仍然是应用设计的责任。

## 19. 官方文档入口

- [Amazon ECS task definitions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)
- [Amazon ECS on AWS Fargate](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
- [Amazon ECS service definition parameters](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service_definition_parameters.html)
- [Amazon RDS User Guide](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Welcome.html)
- [Amazon ElastiCache User Guide](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/WhatIs.html)
- [Amazon MSK Developer Guide](https://docs.aws.amazon.com/msk/latest/developerguide/what-is-msk.html)
- [Amazon CloudWatch User Guide](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/WhatIsCloudWatch.html)
- [CloudWatch alarms](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Alarms.html)
- [AWS CloudFormation stacks](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/stacks.html)
- [AWS CodePipeline concepts](https://docs.aws.amazon.com/codepipeline/latest/userguide/concepts.html)
- [AWS Secrets Manager](https://docs.aws.amazon.com/secretsmanager/latest/userguide/intro.html)
