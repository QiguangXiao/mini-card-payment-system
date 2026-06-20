package com.minicard.repayment.infrastructure;

import com.minicard.repayment.application.AutoDebitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 自动扣款配置入口。
 *
 * <p>关键词：配置绑定, 自动扣款配置, configuration properties,
 * auto debit configuration, 設定バインド(せっていバインド),
 * 口座振替設定(こうざふりかえせってい)。</p>
 *
 * <p>@EnableConfigurationProperties 是 Spring Boot 高级配置语法：把
 * repayment.auto-debit.* 绑定到 AutoDebitProperties，供 simulated gateway 注入。</p>
 */
@Configuration
@EnableConfigurationProperties(AutoDebitProperties.class)
public class AutoDebitConfiguration {
}
