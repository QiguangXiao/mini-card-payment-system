package com.minicard.infrastructure.web.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把限流拦截器挂到授权路由上（WebMvcConfigurer 注册点）。
 *
 * <p>关键词：拦截器注册, WebMvcConfigurer, addInterceptors, path pattern,
 * interceptor registration, インターセプター登録(とうろく)。</p>
 *
 * <p>基础背景：创建一个 {@link org.springframework.web.servlet.HandlerInterceptor} bean
 * 并不会自动让它拦截请求，还必须通过 {@link WebMvcConfigurer#addInterceptors(InterceptorRegistry)}
 * 注册 path pattern。Interceptor 实现“怎么判断”，本类声明“哪些路由使用它”。</p>
 *
 * <p>{@link ObjectProvider} 可以理解成“向 Spring 容器按需询问一个 bean”，与普通构造器注入
 * 的区别是：目标不存在时不会立即导致启动失败。这里的 interceptor 有两种合法缺席场景——
 * (1) {@code api.rate-limit.enabled=false} 时 {@link RateLimitConfiguration} 整体不装配；
 * (2) {@code @WebMvcTest} 切片会加载 WebMvcConfigurer（本类），但不会加载普通
 * {@code @Configuration}（RateLimitConfiguration）。两种场景下这里都应静默跳过注册，
 * 而不是让 context 启动失败。普通必选 service/repository 不应照搬这种写法，缺失时仍应 fail fast。</p>
 */
@Configuration
public class RateLimitWebMvcConfiguration implements WebMvcConfigurer {

    private final ObjectProvider<AuthorizationRateLimitInterceptor> interceptorProvider;

    public RateLimitWebMvcConfiguration(
            ObjectProvider<AuthorizationRateLimitInterceptor> interceptorProvider
    ) {
        this.interceptorProvider = interceptorProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ifAvailable 表示“容器里有 interceptor 才执行注册 lambda”；关闭限流或 MVC slice test 时什么也不做。
        // 只挂授权热路径的 collection path：POST /api/authorizations 是全系统唯一的
        // 高频写入口（容量分析的对象）。GET /api/authorizations/{id} 是另一个 pattern，
        // 不会被匹配；其余读端点有缓存层保护，暂不限流。
        interceptorProvider.ifAvailable(interceptor ->
                registry.addInterceptor(interceptor).addPathPatterns("/api/authorizations"));
    }
}
