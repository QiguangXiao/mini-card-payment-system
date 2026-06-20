package com.minicard.infrastructure.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP request logging filter。
 *
 * <p>关键词：请求日志, request id, Servlet filter, HTTP logging,
 * correlation id, OncePerRequestFilter, リクエストログ,
 * 相関ID(そうかんID)。</p>
 *
 * <p>OncePerRequestFilter 是 Spring Web 的高级基类，保证一次 request dispatch 内只执行一次，
 * 适合打 request_started/request_completed 这种基础设施日志。</p>
 */
@Component
@Slf4j
public class HttpRequestLoggingFilter extends OncePerRequestFilter {

    /** 返回给客户端的 request id header，方便排查时串起服务端日志。 */
    static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * 包裹后续 filter/controller 调用，记录开始和结束日志。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // 每个请求生成 correlation id；生产系统通常会优先复用入口网关传入的 id。
        String requestId = UUID.randomUUID().toString();
        // System.nanoTime 适合测 duration，不适合当业务时间戳。
        long startTime = System.nanoTime();

        response.setHeader(REQUEST_ID_HEADER, requestId);
        log.info(
                "request_started requestId={} method={} path={}",
                requestId,
                request.getMethod(),
                request.getRequestURI()
        );

        try {
            filterChain.doFilter(request, response);
        } finally {
            // finally 保证 controller 抛异常时也能记录 request_completed。
            long durationMillis = (System.nanoTime() - startTime) / 1_000_000;
            log.info(
                    "request_completed requestId={} method={} path={} status={} durationMs={}",
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMillis
            );
        }
    }
}
