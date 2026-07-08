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
 * <p>Interceptor 实现"做什么"，这里声明"挂在哪"。分开的原因：挂载点（路由集合）
 * 是应用级决策，未来给别的端点限流只改这里，不动拦截器本身。</p>
 *
 * <p>用 {@link ObjectProvider} 而不是直接注入：拦截器 bean 有两种合法的缺席场景——
 * (1) {@code api.rate-limit.enabled=false} 时 {@link RateLimitConfiguration} 整体不装配；
 * (2) {@code @WebMvcTest} 切片会加载 WebMvcConfigurer（本类），但不会加载普通
 * {@code @Configuration}（RateLimitConfiguration）。两种场景下这里都应静默跳过注册，
 * 而不是让 context 启动失败。</p>
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
        // 只挂授权热路径的 collection path：POST /api/authorizations 是全系统唯一的
        // 高频写入口（容量分析的对象）。GET /api/authorizations/{id} 是另一个 pattern，
        // 不会被匹配；其余读端点有缓存层保护，暂不限流。
        interceptorProvider.ifAvailable(interceptor ->
                registry.addInterceptor(interceptor).addPathPatterns("/api/authorizations"));
    }
}
