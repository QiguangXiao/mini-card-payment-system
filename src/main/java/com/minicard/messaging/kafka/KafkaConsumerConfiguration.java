package com.minicard.messaging.kafka;

import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费错误处理配置：统一 retry 策略 + 按失败 consumer group 路由的 DLT。
 *
 * <p>关键词：消费容器, 死信路由, 重试策略, common error handler,
 * DLT routing, retry policy, 消費設定(しょうひせってい)。</p>
 *
 * <h3>先理解 Spring Kafka listener container</h3>
 * <p>业务代码只写 {@code @KafkaListener} 方法，但真正持续运行的是 listener container。它在后台：</p>
 * <ol>
 *   <li>向 broker poll 一批 record；</li>
 *   <li>按 partition 顺序调用 listener；</li>
 *   <li>listener 正常返回后按 ack mode 推进 offset；</li>
 *   <li>listener 抛异常时交给 error handler 决定 retry 或 DLT。</li>
 * </ol>
 *
 * <h3>装配方式：不再手建 factory，只提供一个 error handler</h3>
 * <p>本类不定义任何 ConcurrentKafkaListenerContainerFactory。Spring Boot 自动配置的默认
 * factory 会套用 {@code spring.kafka.consumer/listener} 配置（enable-auto-commit=false、
 * ack-mode=record 等），并把容器内唯一的 CommonErrorHandler bean——也就是下面这个
 * {@link DefaultErrorHandler}——自动装进去。各 listener 的并发在自己的
 * {@code @KafkaListener(concurrency)} 上声明；topic 创建由 {@link KafkaTopicsConfiguration} 负责。</p>
 *
 * <p>反事实（为什么从多个 per-context factory 收敛成一个 handler）：这些 factory 只差
 * DLT 名和 concurrency，是同一消费模型套不同参数值，且带来两个真实的坑。其一，Boot 的默认
 * factory 只按 bean 名 kafkaListenerContainerFactory 让位，三个自定义名 bean 存在时它仍然
 * 被创建——新 listener 忘写 containerFactory 属性会静默挂上没有 DLT 的默认 error handler，
 * 快速重试后记日志、提交 offset、消息丢弃。其二，DLT 正确性依赖每个 listener 手选 factory，
 * 旧注释甚至需要警告"误用其他 context factory 会进错 DLT"。现在默认 factory 自带本 handler，
 * 新 listener 默认安全；DLT 跟随失败的消费组，没有可以绑错的配置点。只有当各 context 的
 * ack 模式、批量消费、serde、事务或目标集群真正不同时，才应该回到多 factory。</p>
 *
 * <p>DLT 按失败 consumer group 路由，避免将不同消费职责的失败混在一起。
 * 当前只有 Notification consumer，但仍保留 group 路由，以便未来增加新的异步下游。</p>
 *
 * <h3>反序列化边界</h3>
 * <p>Kafka 层是 String 反序列化，poll 阶段基本不可能失败；malformed JSON 在
 * {@link IntegrationEventReader} 内变成 {@link InvalidIntegrationEventException}（not-retryable，直进
 * DLT）。所以这里不需要 ErrorHandlingDeserializer——那是 JsonDeserializer 架构下的解法，
 * 本项目刻意把 payload 解析放在 listener 内的 tolerant reader 里。</p>
 */
@Configuration
// group-id 和 concurrency 从 messaging.consumers.* 绑定/占位符消费（KafkaConsumersProperties 由主类
// @ConfigurationPropertiesScan 注册），调整不需要改代码。
// 绑定类 KafkaConsumersProperties 在这里还承担第二职责：为 DLT 路由表提供各 context 的 group-id。
public class KafkaConsumerConfiguration {

    @Bean
    public DefaultErrorHandler kafkaListenerCommonErrorHandler(
            // KafkaTemplate 在这里不是发业务事件，而是给 recoverer 发布失败 record 到 DLT。
            KafkaTemplate<Object, Object> kafkaTemplate,
            // topics 提供 per-context DLT 名，避免在 Java 中写死环境相关字符串。
            KafkaTopicsProperties topics,
            // consumers 提供各 context 的 group-id，作为 DLT 路由表的 key。
            KafkaConsumersProperties consumers
    ) {
        // 阶段 1：失败 group -> DLT 路由表。key 与各 @KafkaListener 的 groupId 占位符读同一个
        // YAML 值（messaging.consumers.*.group-id），来源唯一；Map.of 对重复 key fail-fast，
        // 新增 context 时继续在这里显式声明路由，不根据 source topic 猜消费职责。
        Map<String, String> deadLetterTopicByGroupId = Map.of(
                consumers.notification().groupId(), topics.notificationDeadLetter()
        );

        // 阶段 2：定义"这条 record 最终处理不了时发到哪里"。DLT 不是自动修复机制，
        // 它把 poison message 留下来，避免一条坏消息永远堵住 partition，后续由人工修复/replay。
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // 按失败的消费组路由：同一 topic 被多个 group 消费，按 topic 查表会进错 DLT。
                // 保留源 partition 编号，便于用 record key 关联排查；注意 DLT 内顺序不等于源
                // topic 顺序（只有失败消息进入、各自经历不同次数 retry），replay 的正确性仍靠
                // Inbox + 业务唯一键幂等，不能依赖 DLT 顺序恢复业务顺序。
                // 为了稳定保留源 partition，DLT partitions 不应少于源 topic；否则 recoverer 在确认
                // 目标 partition 不存在时会退化为 producer 自动选 partition，关联排查能力变弱。
                // 该对齐策略由 KafkaTopicsConfiguration 声明、KafkaTopicsConfigurationTest 钉住。
                (record, exception) -> new TopicPartition(
                        resolveDeadLetterTopic(deadLetterTopicByGroupId, exception),
                        record.partition()
                )
        );

        // 阶段 3：把短暂 retry 与最终 DLT recoverer 组合成统一 error handler。
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer, // retry 用尽后的最终处理器。
                // FixedBackOff(1000, 2) 表示 listener 抛异常后，container 自动等待 1s 再重试，
                // 最多做 2 次重试；仍失败时交给上面的 recoverer publish 到 DLT。
                // 所以业务 listener 不需要自己 catch 后 sleep/retry，除非是业务语义上的补偿。
                new FixedBackOff(
                        1000L, // 每次重试前固定等待 1 秒，避免立即热循环。
                        2L     // 原始调用之外最多重试 2 次，总计最多调用 listener 3 次。
                )
        );
        // malformed JSON 或 unsupported schema version 重试也不会自愈，
        // 所以 permanent contract failure 不消耗这 2 次 retry，直接进入 DLT。
        // 例如 payload 缺必填字段、时间格式错误；这些不是 DB 短暂抖动，重试同一条消息也不会变好。
        errorHandler.addNotRetryableExceptions(InvalidIntegrationEventException.class);

        // 阶段 4：交给 Spring Boot 的 Kafka 自动配置装进默认 listener factory。之后每个
        // @KafkaListener container 的异常路径统一是：
        // 1. 可重试异常：按 FixedBackOff retry，同一 record 重新调用 listener。
        // 2. 不可重试或重试耗尽：recoverer 按失败 groupId 发对应 DLT。
        // 3. DLT publish 成功后，这条原消息被视为 handled，container 才能提交/推进 offset。
        // 如果 DLT publish 失败（含下面 resolveDeadLetterTopic 的 fail-loud），offset 不前进，
        // 消息留在原 partition 反复重试直到人工介入——poison message 不会静默丢失。
        return errorHandler;
    }

    /**
     * 从失败异常中取出消费组并查 DLT 路由表；查不到时 fail loud 而不是猜。
     */
    static String resolveDeadLetterTopic(
            Map<String, String> deadLetterTopicByGroupId,
            Exception exception
    ) {
        // container 调用 listener 失败时会把异常包成带失败 groupId 的 ListenerExecutionFailedException；
        // 这里读的就是"哪个消费组处理不了这条消息"，与 DLT 按 consumer 职责拆分的语义一一对应。
        if (exception instanceof ListenerExecutionFailedException failure && failure.getGroupId() != null) {
            String deadLetterTopic = deadLetterTopicByGroupId.get(failure.getGroupId());
            if (deadLetterTopic != null) {
                return deadLetterTopic;
            }
        }
        // 无法确定失败来源时宁可让 DLT publish 失败、offset 不推进（消息留在原 partition，
        // 表现为 lag 告警），也不能猜一个 DLT 把消息发进别的 context 的死信队列——
        // 那会让"只修复/重放失败 consumer"的隔离承诺静默失效。
        throw new IllegalStateException(
                "no dead-letter topic mapping for consumer failure: " + exception);
    }
}
