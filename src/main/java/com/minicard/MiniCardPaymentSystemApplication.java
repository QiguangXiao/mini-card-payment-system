package com.minicard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Spring Boot 启动入口。
 *
 * <p>真正的业务结构从各 bounded context package 开始；这个类只负责启动应用和组件扫描。</p>
 */
@SpringBootApplication
@EnableFeignClients
public class MiniCardPaymentSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniCardPaymentSystemApplication.class, args);
    }
}
