# Kafka 配置与面试学习笔记

这份文档专门解释本项目里的 Kafka 配置、它们解决什么问题，以及 PayPay Card / 信用卡发卡后台面试里可能被追问的点。

当前项目使用 Kafka 的方式是：

```text
业务事务写 MySQL + Outbox row
OutboxWorker 稍后发布 Kafka
Notification / Risk consumer 异步消费
consumer 用 Inbox / unique key 做幂等
```

重点先记住一句话：

```text
Kafka 负责异步消息传递，但不替代数据库事务，也不消除 consumer 幂等。
```

## 1. 当前 Kafka 配置在哪里

主要在三个地方：

- `src/main/resources/application.yml`
- `KafkaMessagingConfiguration`
- `KafkaOutboxMessagePublisher`

`application.yml` 配置 broker、producer、consumer 和 listener 行为。

`KafkaMessagingConfiguration` 创建 topic、consumer container factory、error handler 和 DLT。

`KafkaOutboxMessagePublisher` 把 Outbox row 转成 Kafka record，并等待 broker acknowledgement。

## 2. Broker 地址

配置：

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

作用：

- 告诉 producer / consumer 从哪个 Kafka broker 集群开始连接。
- `bootstrap` 不是说只连这一台；生产环境里可以配置多台 broker 地址。
- 本地默认是 `localhost:9092`。

面试可能问：

为什么叫 bootstrap servers？

可以回答：

> Client 先连接这些地址拿到 cluster metadata，然后知道 topic partition 分布在哪些 broker 上。生产环境通常配置多个 broker，避免单个地址不可用导致 client 无法启动。

## 3. Producer 配置

### 3.1 `acks: all`

配置：

```yaml
spring:
  kafka:
    producer:
      acks: all
```

作用：

- Producer 等待 leader 和 in-sync replicas 确认后才认为发送成功。
- 比 `acks=1` 更安全，因为 broker leader 写入后如果立刻宕机，副本没同步时可能丢消息。

本项目为什么这么配：

- 金融系统里事件丢失比延迟更严重。
- Authorization / posting 事件用于通知、风控投影、后续运营视图，不能随便丢。

面试回答：

> `acks=all` 提高 durability，但会增加 latency。金融后台通常会优先选择可靠性。即使如此，它也只保证 Kafka 写入更可靠，不解决 MySQL 和 Kafka 的 dual-write 原子性，所以本项目仍然使用 Outbox。

### 3.2 `enable.idempotence: true`

配置：

```yaml
spring:
  kafka:
    producer:
      properties:
        enable.idempotence: true
```

作用：

- Kafka producer retry 时，broker 可以识别同一个 producer session 内的重复写入。
- 能减少 producer retry 造成的 Kafka 内部重复消息。

容易误解的点：

```text
enable.idempotence=true
不等于业务 exactly-once
不等于 consumer 不需要幂等
不等于 MySQL + Kafka 原子提交
```

本项目仍然需要：

- Outbox：解决 MySQL 业务状态和 Kafka publish 的 dual-write 问题。
- Inbox / unique constraint：解决 Kafka redelivery 导致 consumer side effect 重复的问题。

面试回答：

> Kafka producer idempotence 只处理 producer retry 到 Kafka 的重复写入，不能覆盖应用在 Kafka ack 后、更新 MySQL Outbox 状态前宕机的情况。这个窗口仍然会导致同一 Outbox event 再次发布，所以 consumer 必须按 `eventId` 幂等。

### 3.3 `max.in.flight.requests.per.connection: 5`

配置：

```yaml
spring:
  kafka:
    producer:
      properties:
        max.in.flight.requests.per.connection: 5
```

作用：

- 单个连接上允许多少个未收到响应的请求同时飞行。
- 值越大，吞吐更好。
- 乱序风险和 producer idempotence / broker 版本有关。

当前配置含义：

- 保持默认可接受吞吐。
- 配合 idempotent producer，在现代 Kafka 中可以保持安全。

面试回答：

> 如果非常强调单 producer 严格顺序，可以降低 in-flight requests；但在本项目里，同一 authorization 的业务顺序主要依赖 Kafka key 进入同一 partition，以及事件本身按业务状态产生。Producer idempotence 也降低 retry 乱序风险。

### 3.4 `delivery.timeout.ms` 和 `request.timeout.ms`

配置：

```yaml
spring:
  kafka:
    producer:
      properties:
        delivery.timeout.ms: 10000
        request.timeout.ms: 3000
```

作用：

- `request.timeout.ms`：单次 broker 请求等待多久。
- `delivery.timeout.ms`：一条消息从发送到最终成功/失败的总时间上限，包含 retry。

和 Outbox 的关系：

```yaml
outbox:
  publisher:
    send-timeout-ms: 5000
    processing-timeout-seconds: 30
```

- `send-timeout-ms` 是应用等待 `kafkaTemplate.send(...).get(...)` 的时间。
- `processing-timeout-seconds` 是 Outbox row 的 lease 时间。
- lease 应该大于正常 send timeout，避免健康 worker 还在等 broker ack 时，被 recoverer 当成 stuck event。

面试回答：

> Kafka timeout 控制 producer 等 broker 的时间；Outbox lease 控制数据库里这条事件被 worker 占用多久。两者要配合，通常 lease 要明显大于 send timeout。

## 4. Consumer 配置

### 4.1 `enable-auto-commit: false`

配置：

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false
```

作用：

- 不让 consumer 自动定时提交 offset。
- Spring Kafka listener 成功处理完 record 后再提交。

为什么重要：

如果自动提交 offset：

```text
Kafka offset committed
consumer 还没写 MySQL
consumer crash
消息不会再投递
side effect 丢失
```

本项目希望：

```text
listener 处理成功
MySQL side effect commit
listener 返回
Kafka offset commit
```

但仍然不是原子事务：

```text
MySQL commit 成功
Kafka offset commit 前 crash
Kafka redeliver
consumer 幂等挡住重复 side effect
```

面试回答：

> 禁用 auto commit 可以避免“消息还没处理完 offset 已经提交”。但 Kafka offset 和 MySQL transaction 仍然不能原子提交，所以 consumer 仍要使用 Inbox 或 unique constraint 做幂等。

### 4.2 `auto-offset-reset: earliest`

配置：

```yaml
spring:
  kafka:
    consumer:
      auto-offset-reset: earliest
```

作用：

- 当 consumer group 没有历史 offset 时，从哪里开始读。
- `earliest` 表示从当前保留的最早消息开始读。

适合本项目的原因：

- 学习环境里新建 consumer group，希望能读到已有事件，方便观察。
- 对投影类 consumer，例如 Risk feature projection，从早期事件开始构建状态更合理。

生产环境注意：

- 新 consumer group 如果直接 `earliest`，可能会重放大量历史消息。
- 是否使用 `earliest` 要结合 topic retention、投影重建策略、消费能力评估。

面试回答：

> `earliest` 适合可重建 projection 或学习环境；生产环境要谨慎，因为新 group 可能从历史消息开始大量回放。

### 4.3 `listener.ack-mode: record`

配置：

```yaml
spring:
  kafka:
    listener:
      ack-mode: record
```

作用：

- 每条 record 成功处理后提交 offset。
- 相比 batch ack，一条失败不会让同 batch 后面已经处理的 offset 语义变复杂。

本项目为什么适合：

- Notification / Risk consumer 都有数据库 side effect。
- 每条事件独立幂等处理，record-level ack 更容易解释。

面试回答：

> `record` ack 牺牲一点吞吐，但语义更清晰。金融后台里每条消息都有独立 side effect 和幂等记录，record-level ack 更适合作为学习项目的默认选择。

## 5. Topic 和 partition 配置

### 5.1 authorization events topic

代码：

```java
TopicBuilder.name(properties.authorizationEvents())
        .partitions(3)
        .replicas(1)
        .build();
```

配置名：

```yaml
messaging:
  topics:
    authorization-events: mini-card.authorization-events.v1
```

作用：

- 所有 authorization lifecycle events 先放在一个 topic。
- 当前事件包括：
  - `authorization.approved`
  - `authorization.declined`
  - `authorization.posted`
  - `authorization.expired`

为什么 topic 名带 `.v1`：

- 表示 event contract 的大版本。
- 如果未来做破坏性 schema 变更，可以新建 `.v2` topic，让新旧 consumer 平滑迁移。

### 5.2 partitions = 3

作用：

- Partition 是 Kafka 并行度和顺序边界。
- 同一个 partition 内有序。
- 不同 partition 可以并行消费。

本项目怎么选 key：

```java
new ProducerRecord<>(
        topic,
        event.partitionKey(),
        event.payload()
)
```

当前 `partitionKey` 使用 authorization id。

效果：

```text
同一 authorization 的 approved / posted / expired 等事件进入同一 partition
同一 authorization 内保持顺序
不同 authorization 可以并行处理
```

面试回答：

> Kafka 只保证 partition 内顺序，不保证 topic 全局顺序。我们用 authorizationId 作为 key，把同一个 aggregate 的事件固定到同一 partition，获得 aggregate-level ordering。

### 5.3 replicas = 1

当前代码：

```java
.replicas(1)
```

原因：

- 本地 Docker 只有一个 Kafka broker。
- replication factor 只能是 1。

生产环境：

- 通常至少 3 brokers，replication factor 设为 3。
- 配合 `acks=all` 才能真正提升 broker 故障下的数据可靠性。

面试回答：

> 本地 replicas=1 只是开发限制，不是生产建议。生产会提高 replication factor，并配置 min.insync.replicas，防止 leader 单点写入后丢失。

## 6. Consumer group 和 concurrency

配置：

```yaml
messaging:
  consumers:
    notification:
      group-id: mini-card-notification-v1
    risk-feature:
      group-id: mini-card-risk-feature-v1
```

代码：

```java
factory.setConcurrency(2); // notification
factory.setConcurrency(3); // risk feature
```

Consumer group 语义：

- 同一个 group 内，一条消息只会被一个 consumer instance 处理。
- 不同 group 会各自收到同一条消息。

所以本项目中：

```text
authorization event
-> notification group 收到一份
-> risk-feature group 也收到一份
```

这正是 event-driven 架构的价值：不同 bounded context 可以独立处理同一业务事实。

Concurrency 语义：

- `concurrency=3` 表示该 application instance 内最多启动 3 个 listener threads。
- 但最大有效并行度受 partition 数限制。
- topic 只有 3 partitions 时，单个 group 的有效并行度最多就是 3。

面试回答：

> Consumer group 让同一业务能力横向扩展；不同 group 让不同业务能力独立消费同一事件。Concurrency 不能无限提高吞吐，它受 partition 数限制。

## 7. DLT 和错误处理

代码：

```java
DefaultErrorHandler errorHandler = new DefaultErrorHandler(
        recoverer,
        new FixedBackOff(1000L, 2L)
);
errorHandler.addNotRetryableExceptions(EventContractException.class);
```

含义：

- 普通异常：间隔 1 秒，重试 2 次。
- 仍失败：发送到对应 DLT。
- `EventContractException`：不重试，直接 DLT。

为什么 contract error 不重试：

- JSON 格式错误、header 和 payload eventId 不一致、eventVersion 不支持，这些不会因为等 1 秒就变好。
- 重试只会堵住 partition。

DLT topic：

```yaml
mini-card.authorization-notification.dlt.v1
mini-card.authorization-risk-feature.dlt.v1
```

为什么每个 consumer 一个 DLT：

- 同一条消息可能对 Risk 成功、对 Notification 失败。
- 分开 DLT 后，可以只修复和 replay 失败的 consumer，不影响其他 bounded context。

代码里保留 original partition：

```java
(record, exception) -> new TopicPartition(deadLetterTopic, record.partition())
```

作用：

- 排查时能看出原 partition。
- replay 时更容易保持 partition 相关顺序。

面试回答：

> Retry 适合 transient failure，例如数据库短暂不可用。Contract failure 是 permanent failure，应直接进入 DLT。DLT 不应该自动无限重放，需要告警、排查、修复、再人工或受控 replay。

## 8. Kafka headers 和 envelope

Publisher 写 headers：

```java
eventId
eventType
eventVersion
aggregateType
aggregateId
```

Payload 是 `IntegrationEvent` envelope：

```json
{
  "eventId": "...",
  "eventType": "authorization.approved",
  "eventVersion": 1,
  "occurredAt": "...",
  "payload": {
    "authorizationId": "...",
    "cardId": "card-123",
    "amount": "1000.00",
    "currency": "JPY"
  }
}
```

为什么 header 和 payload 都有 eventId/eventType：

- Header 方便 routing、logging、DLT 排查，不用先 parse JSON。
- Payload 是完整事件内容，方便持久化和 replay。
- `IntegrationEventReader` 会校验 header 和 payload 是否一致，避免 transport contract 损坏。

面试回答：

> Header 是 transport-level metadata，payload 是 durable event contract。两者重复关键字段是为了提高可观测性和校验能力。

## 9. 为什么还需要 Outbox

很多人会问：

```text
Kafka 已经很可靠了，为什么还要 Outbox？
```

因为业务状态在 MySQL，消息在 Kafka。没有分布式事务时会有 dual-write 问题：

```text
MySQL commit 成功，Kafka publish 失败 -> 业务状态存在，但事件丢失
Kafka publish 成功，MySQL rollback -> 消费者看到不存在的业务事实
```

Outbox 做法：

```text
业务状态 + outbox_events row 同一个 MySQL transaction commit
OutboxWorker 后台发布 Kafka
发布成功后标记 outbox row PUBLISHED
失败则 retry / DEAD
```

它保证的是：

```text
业务状态只要提交，消息发布意图就不会丢
```

它不保证：

```text
Kafka 消息绝不重复
```

所以仍然是 at-least-once delivery。

面试回答：

> Outbox 把“业务事务”和“消息发布”拆成两个可靠步骤，避免 dual-write lost event。代价是消息可能重复，所以 consumer 必须幂等。

## 10. Consumer 幂等怎么做

本项目有两种方式：

### Notification

`notifications.source_event_id` 有 unique constraint。

同一个 eventId 重复消费时，不会创建重复 notification。

### Risk Feature Projection

使用 `consumer_inbox`：

```text
consumer_name + event_id
```

作为主键。

处理流程：

```text
begin transaction
  insert consumer_inbox row
  update projection table
commit
```

如果 insert 失败，说明处理过，直接跳过 side effect。

面试回答：

> Kafka redelivery 是正常情况。Consumer side effect 必须幂等，常见做法是用 eventId unique constraint 或 consumer inbox table，把“是否处理过”和业务更新放在同一个数据库事务里。

## 11. 面试高频问题

### Kafka 能保证消息顺序吗？

只能保证 partition 内顺序，不保证 topic 全局顺序。

本项目用 authorizationId 作为 key，让同一 authorization 的 lifecycle events 进入同一 partition。

### Kafka 是 exactly-once 吗？

Kafka 有 producer idempotence 和 transaction API，但本项目涉及 MySQL side effect，Kafka 和 MySQL 不是一个原子事务。

所以从业务视角仍然按 at-least-once 设计，consumer 幂等。

### 为什么不用同步调用 Notification / Risk？

授权请求是金融实时路径，应该尽快返回。

Notification 和 Risk projection 是 follow-up side effects，适合异步处理。Kafka 让这些 bounded context 独立扩缩、独立失败。

### 如果 Kafka 挂了，授权还能成功吗？

可以。

主事务只写 `outbox_events`。Kafka 挂了会导致 Outbox row 停留在 `PENDING/PROCESSING`，之后 worker retry。

### 如果 consumer 挂了，会丢消息吗？

不会因为 consumer 挂了就丢。

消息保留在 Kafka topic 中，consumer 恢复后从 group offset 继续读。但如果消息超过 retention 被删除，仍可能无法消费，所以生产环境要配置 retention 和 lag alert。

### DLT 里的消息怎么处理？

生产环境通常流程：

```text
alert on DLT growth
inspect payload and exception
fix code or data
controlled replay
```

不要无限自动 replay DLT，否则 poison message 会反复冲击系统。

### Partition 数怎么决定？

看吞吐、consumer 并行度、key 分布和未来扩容。

太少：consumer 扩不起来。

太多：broker metadata、文件句柄、rebalance 成本上升。

本项目用 3 partitions 是学习用配置，能演示 consumer concurrency。

### 为什么同一个 topic 放多个 eventType？

当前 authorization lifecycle 事件吞吐和权限边界还不复杂，用一个 topic 更简单。

什么时候拆 topic：

- 不同事件 retention 差异很大。
- 访问权限不同。
- 吞吐差异很大。
- consumer 只订阅某类事件导致过滤成本明显。

### 为什么 eventVersion 很重要？

Event 是跨 bounded context contract。

字段一旦变更，旧 consumer 可能无法解析。`eventVersion` 允许 consumer 显式判断自己是否支持该版本。

## 12. 当前项目和生产环境差距

当前本地配置适合学习：

- 单 broker
- replication factor = 1
- 无认证
- 简单 retry / DLT

生产环境通常还要补：

- 多 broker，replication factor >= 3
- `min.insync.replicas`
- TLS / SASL 认证
- topic retention 策略
- consumer lag alert
- DLT alert 和 replay 工具
- schema registry 或更严格的 event contract 管理
- producer / consumer metrics dashboard

## 13. 一句话总结

面试里可以这样讲：

> 我们用 Kafka 解耦授权后的异步副作用，用 Outbox 解决 MySQL 和 Kafka dual-write 的 lost-event 风险；整体按 at-least-once 设计，所以 producer 等 broker ack，Outbox 负责 retry，consumer 用 eventId 做幂等，DLT 用于隔离无法自动恢复的坏消息。
