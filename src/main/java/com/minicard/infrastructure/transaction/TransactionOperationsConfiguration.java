package com.minicard.infrastructure.transaction;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 共享 transaction helper 配置。
 *
 * <p>关键词：显式事务, TransactionTemplate, finalize 事务, transaction operations,
 * transaction boundary, 明示的トランザクション(めいじてきトランザクション),
 * 境界(きょうかい)。</p>
 *
 * <p>DelayJobWorker 用 TransactionOperations 显式拆分 handler 事务和 finalize 事务；
 * 这个能力属于 transaction infrastructure，不属于 scheduler 线程池配置。</p>
 */
@Configuration
public class TransactionOperationsConfiguration {

    /**
     * 把 Spring PlatformTransactionManager 包装成 TransactionOperations。
     */
    @Bean
    public TransactionOperations transactionOperations(
            PlatformTransactionManager transactionManager
    ) {
        // TransactionTemplate 适合 scheduler/worker 这种需要在一个方法里显式开多个事务的场景。
        // 这也绕开了 Spring self-invocation 陷阱：同一个对象内部调用 @Transactional 方法不会经过 proxy。
        return new TransactionTemplate(transactionManager);
    }
}
