package com.minicard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Spring Boot 启动入口。
 *
 * <p>真正的业务结构从各 bounded context package 开始；这个类只负责启动应用和组件扫描。</p>
 */
// @SpringBootApplication 会触发 component scan、auto configuration 和 configuration properties 支持。
// 如果这里只写一个普通 main 方法，Controller/Service/Repository/@Bean 都不会进入 Spring container。
@SpringBootApplication
// @ConfigurationPropertiesScan 扫描 com.minicard 下所有 @ConfigurationProperties record 并注册成 bean，
// 取代散落在各模块配置类上的 @EnableConfigurationProperties。新增 properties record 不再需要手动登记；
// 代价是切片测试（@WebMvcTest 等）不触发扫描，测试里需要属性 bean 时要自己写 @EnableConfigurationProperties。
@ConfigurationPropertiesScan
// @EnableFeignClients 告诉 Spring Cloud OpenFeign 扫描 @FeignClient 接口并生成 HTTP client 代理。
// 如果省掉它，ExternalRiskClient 只是普通 interface，Risk adapter 启动时会因为找不到 bean 失败。
@EnableFeignClients
public class MiniCardPaymentSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniCardPaymentSystemApplication.class, args);
    }
}
