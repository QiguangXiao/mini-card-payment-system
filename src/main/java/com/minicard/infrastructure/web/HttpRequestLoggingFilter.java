package com.minicard.infrastructure.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
    /** Logback pattern 使用的 MDC key；同一 HTTP request 内的业务日志会自动带上它。 */
    static final String MDC_REQUEST_ID_KEY = "requestId";

    /**
     * 包裹后续 filter/controller 调用，记录开始和结束日志。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // OncePerRequestFilter 已处理 async/error dispatch 的重复执行问题。
        // 如果直接实现 Filter，同一次请求的错误转发可能重复打 started/completed 日志。
        // 优先复用入口网关/客户端传入的 correlation id；没有时再生成本服务自己的 id。
        String requestId = resolveRequestId(request);
        // System.nanoTime 适合测 duration，不适合当业务时间戳。
        long startTime = System.nanoTime();

        response.setHeader(REQUEST_ID_HEADER, requestId);
        // MDC 底层是 ThreadLocal-backed diagnostic context。放在这里后，同一 Tomcat request
        // thread 内 controller/service/repository 的日志都能通过 %X{requestId} 自动带上 request id。
        MDC.put(MDC_REQUEST_ID_KEY, requestId);
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
            // Tomcat request thread 会被线程池复用；如果不 remove，下一个完全无关的请求可能继承
            // 上一个请求的 requestId，排查时会出现典型的 ThreadLocal 串号/泄漏事故。
            MDC.remove(MDC_REQUEST_ID_KEY);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId != null && !requestId.isBlank()) {
            return requestId.trim();
        }
        return UUID.randomUUID().toString();
    }
}
