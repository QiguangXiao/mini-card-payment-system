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
 * repayment.auto-debit.* 绑定到 AutoDebitProperties，供模拟银行 controller 注入。</p>
 */
@Configuration
// 没有这个启用入口时，AutoDebitProperties 只是一个带注解的 record，不一定会成为 bean。
// 结果是 SimulatedBankController 的 constructor injection 在启动期失败。
@EnableConfigurationProperties(AutoDebitProperties.class)
public class AutoDebitConfiguration {
}
