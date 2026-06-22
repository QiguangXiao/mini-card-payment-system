package com.minicard.statement.infrastructure;

import com.minicard.statement.application.StatementBatchProperties;
import com.minicard.statement.application.StatementPolicyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Statement 相关配置属性绑定入口。
 *
 * <p>关键词：配置绑定, 账单策略, 批处理配置, configuration properties,
 * statement policy, batch properties, 設定バインド(せっていバインド),
 * 請求設定(せいきゅうせってい)。</p>
 *
 * <p>@EnableConfigurationProperties 让 Spring Boot 把 application.yml 中的 statement.policy.*
 * 和 statement.batch.* 绑定成强类型 record，避免 service 直接读字符串配置。</p>
 */
@Configuration
// 一个配置入口同时启用 policy 和 batch properties，避免 service 直接散落读取 YAML。
// 如果漏启用其中一个 properties，相关 service 的 constructor injection 会在启动期失败。
@EnableConfigurationProperties({
        StatementPolicyProperties.class,
        StatementBatchProperties.class
})
public class StatementPolicyConfiguration {
}
