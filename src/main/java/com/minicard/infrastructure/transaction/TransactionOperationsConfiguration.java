package com.minicard.infrastructure.transaction;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 共享 transaction helper 配置。
 *
 * <p>DelayJobWorker 用 TransactionOperations 显式拆分 handler 事务和 finalize 事务；
 * 这个能力属于 transaction infrastructure，不属于 scheduler 线程池配置。</p>
 */
@Configuration
public class TransactionOperationsConfiguration {

    @Bean
    public TransactionOperations transactionOperations(
            PlatformTransactionManager transactionManager
    ) {
        return new TransactionTemplate(transactionManager);
    }
}
