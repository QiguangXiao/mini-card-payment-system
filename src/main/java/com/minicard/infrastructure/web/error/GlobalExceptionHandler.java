package com.minicard.infrastructure.web.error;

import java.time.Instant;
import java.util.NoSuchElementException;

import com.minicard.authorization.application.IdempotencyConflictException;
import com.minicard.infrastructure.web.ratelimit.RateLimitExceededException;
import com.minicard.repayment.application.RepaymentConflictException;
import com.minicard.repayment.application.RepaymentRejectedException;
import com.minicard.transaction.application.PresentmentConflictException;
import com.minicard.transaction.application.PresentmentRejectedException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局 API 错误映射，把 application/domain exception 转换成稳定的 HTTP error contract。
 *
 * <p>关键词：全局异常处理, HTTP 错误码, 稳定契约, global exception handler,
 * error contract, API error, 例外ハンドラー(れいがいハンドラー),
 * エラー契約(エラーけいやく)。</p>
 *
 * <p>interview重点：金融 API 不应该把 Java stack trace 或数据库错误直接暴露给客户端；
 * 客户端需要稳定 code，服务端日志保留细节用于排查。</p>
 */
// @RestControllerAdvice 会拦截所有 controller 的异常并自动写 JSON body。
// 如果每个 controller 自己 try/catch，错误码会分散，也容易漏掉 validation/绑定异常。
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 查询不到资源时返回 404。
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
            IdempotencyConflictException exception
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(PresentmentConflictException.class)
    public ResponseEntity<ErrorResponse> handlePresentmentConflict(
            PresentmentConflictException exception
    ) {
        return error(HttpStatus.CONFLICT, "PRESENTMENT_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(PresentmentRejectedException.class)
    public ResponseEntity<ErrorResponse> handlePresentmentRejected(
            PresentmentRejectedException exception
    ) {
        return error(HttpStatus.CONFLICT, "PRESENTMENT_REJECTED", exception.getMessage());
    }

    @ExceptionHandler(RepaymentConflictException.class)
    public ResponseEntity<ErrorResponse> handleRepaymentConflict(
            RepaymentConflictException exception
    ) {
        return error(HttpStatus.CONFLICT, "REPAYMENT_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(RepaymentRejectedException.class)
    public ResponseEntity<ErrorResponse> handleRepaymentRejected(
            RepaymentRejectedException exception
    ) {
        return error(HttpStatus.CONFLICT, "REPAYMENT_REJECTED", exception.getMessage());
    }

    /**
     * 入口限流超限返回 429 + Retry-After。
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(
            RateLimitExceededException exception
    ) {
        // 429 与 409 的语义区分：409 是"这个请求和已有状态冲突"（重试也不会成功），
        // 429 是"来得太快"（等待后重试会成功）。Retry-After 用整数秒，告诉客户端最短等待时间，
        // 行为良好的客户端/SDK 会据此退避，而不是立刻重试放大洪峰。
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(new ErrorResponse("RATE_LIMIT_EXCEEDED", exception.getMessage(), Instant.now()));
    }

    /**
     * 数据库连接池耗尽或数据库不可用时返回 503，明确告诉客户端这是暂时容量故障。
     */
    @ExceptionHandler({
            CannotCreateTransactionException.class,
            CannotGetJdbcConnectionException.class
    })
    public ResponseEntity<ErrorResponse> handleDatabaseUnavailable(Exception exception) {
        // @Transactional 路径通常在事务开始时抛 CannotCreateTransactionException；非事务查询可能直接抛
        // CannotGetJdbcConnectionException。只匹配这两个顶层类型，不遍历 cause 链，避免把 outcome unknown
        // 等其他事务异常误报成可安全重试的连接池过载。
        // 反事实：如果落到 catch-all 500，客户端无法区分“暂时没有 DB capacity”和真正的内部 bug。
        // 503 + Retry-After 允许客户端稍后复用同一个 Idempotency-Key 重试，仍由服务端幂等约束防重复资金动作。
        log.warn(
                "database_connection_unavailable exceptionType={} message={}",
                exception.getClass().getSimpleName(),
                exception.getMessage()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ErrorResponse(
                        "DATABASE_UNAVAILABLE",
                        "Database capacity is temporarily unavailable; retry later and reuse the same "
                                + "Idempotency-Key for state-changing requests",
                        Instant.now()
                ));
    }

    /**
     * URL 存在但 HTTP method 不受支持时返回 405。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception
    ) {
        // 反向事实：如果落到 catch-all 500，客户端会把“入口不存在”误判为服务端故障。
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", exception.getMessage());
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            ServletRequestBindingException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
        // MethodArgumentNotValidException 来自 @Valid request body，
        // ConstraintViolationException 来自 @Validated header/path variable，二者都属于 400。
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        // 内部细节只写 server logs，不返回给客户端，避免泄漏实现细节和敏感信息。
        log.error("Unexpected request processing failure", exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred"
        );
    }

    /**
     * 统一构造 ErrorResponse。
     */
    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, Instant.now()));
    }
}
