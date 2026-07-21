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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局 API 错误映射，把 application/domain exception 转换成稳定的 HTTP error contract。
 *
 * <p>关键词：全局异常处理, HTTP 错误码, 稳定契约, global exception handler,
 * error contract, API error, 例外ハンドラー(れいがいハンドラー),
 * エラー契約(エラーけいやく)。</p>
 *
 * <p>interview重点：金融 API 不应该把 Java stack trace 或数据库错误直接暴露给客户端；
 * 客户端需要稳定 code，服务端日志保留细节用于排查。</p>
 *
 * <p>本类同时维护两层契约：</p>
 * <ul>
 *     <li>HTTP status 表达通用协议语义，例如 400、404、409、429、503；</li>
 *     <li>{@link ErrorResponse#code()} 表达本系统可稳定分支处理的业务/技术原因。</li>
 * </ul>
 * <p>客户端不应解析 message 做业务判断，因为 message 是给人看的诊断信息，可能随着说明改进而变化；
 * 应使用 HTTP status 判断错误大类，再使用稳定 code 决定是否提示修正请求、查询原结果或延迟重试。</p>
 *
 * <p>本项目中的 409 Conflict 不是单一返回，而是以下五种 error code：</p>
 * <ul>
 *     <li>{@code IDEMPOTENCY_CONFLICT}：authorization 的 Idempotency-Key 被不同请求复用；</li>
 *     <li>{@code PRESENTMENT_CONFLICT}：networkTransactionId 被不同 presentment 复用；</li>
 *     <li>{@code PRESENTMENT_REJECTED}：presentment 与 authorization 或当前处理状态冲突；</li>
 *     <li>{@code REPAYMENT_CONFLICT}：repayment 的 Idempotency-Key 被不同请求复用；</li>
 *     <li>{@code REPAYMENT_REJECTED}：repayment 与 statement/account 当前状态冲突。</li>
 * </ul>
 */
// @RestControllerAdvice 会拦截所有 controller 的异常并自动写 JSON body。
// 如果每个 controller 自己 try/catch，错误码会分散，也容易漏掉 validation/绑定异常。
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 查询不到资源时返回 404。
     *
     * <p>当前 application 查询方法会显式抛出 {@link NoSuchElementException} 表示业务资源不存在。
     * 这是一个轻量但偏宽的映射：不要在内部代码随意调用 {@code Optional.get()}，否则程序 bug 也可能
     * 被误报成 404。API 面继续扩大时，可再收敛成专用 ResourceNotFoundException。</p>
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage());
    }

    /**
     * Authorization 幂等键被不同请求体复用时返回 409 + IDEMPOTENCY_CONFLICT。
     *
     * <p>相同 key + 相同 request fingerprint 会返回首次处理结果，不会进入这里；
     * 相同 key + 不同 fingerprint 才是调用方违反幂等契约，不能通过立即重试解决。</p>
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
            IdempotencyConflictException exception
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage());
    }

    /**
     * 同一个 networkTransactionId 被用于不同 presentment 时返回 409 + PRESENTMENT_CONFLICT。
     *
     * <p>networkTransactionId 是清算/入账侧的幂等标识，一旦归属于某笔 authorization、金额和币种，
     * 就不能再代表另一笔交易；否则服务端无法安全判断是网络重放还是一笔新资金动作。</p>
     */
    @ExceptionHandler(PresentmentConflictException.class)
    public ResponseEntity<ErrorResponse> handlePresentmentConflict(
            PresentmentConflictException exception
    ) {
        return error(HttpStatus.CONFLICT, "PRESENTMENT_CONFLICT", exception.getMessage());
    }

    /**
     * Presentment 请求格式有效，但与 authorization 或当前处理状态冲突时返回
     * 409 + PRESENTMENT_REJECTED。
     *
     * <p>典型原因包括 authorization 非 APPROVED、已过期、仅支持 full presentment 时金额不一致，
     * 或同一 presentment 仍是 PENDING。它和 PRESENTMENT_CONFLICT 的区别是：外部幂等标识未必被
     * “不同请求”复用，而是当前业务状态不允许完成 posting。</p>
     */
    @ExceptionHandler(PresentmentRejectedException.class)
    public ResponseEntity<ErrorResponse> handlePresentmentRejected(
            PresentmentRejectedException exception
    ) {
        return error(HttpStatus.CONFLICT, "PRESENTMENT_REJECTED", exception.getMessage());
    }

    /**
     * Repayment 幂等键被不同请求体复用时返回 409 + REPAYMENT_CONFLICT。
     *
     * <p>客户端应核对原 idempotency request，而不是自动换一个新 key 重放；
     * 对资金 API 盲目换 key 可能绕过去重边界，制造 double repayment 风险。</p>
     */
    @ExceptionHandler(RepaymentConflictException.class)
    public ResponseEntity<ErrorResponse> handleRepaymentConflict(
            RepaymentConflictException exception
    ) {
        return error(HttpStatus.CONFLICT, "REPAYMENT_CONFLICT", exception.getMessage());
    }

    /**
     * Repayment 请求格式有效，但与锁定后的 statement/account 状态冲突时返回
     * 409 + REPAYMENT_REJECTED。
     *
     * <p>典型原因包括账单已付清、还款金额超过 remaining amount、币种不一致，或同一还款仍在处理。
     * 这些判断在 row lock 后基于最新状态执行，所以 409 表达的是业务状态冲突，而不是 JSON/字段格式错误。</p>
     */
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
        // 429 与 409 的语义区分：409 是“请求和资源当前状态/既有幂等记录冲突”，客户端必须先理解
        // error code，不能把所有 409 都盲目重试；例如不同 payload 复用幂等键必须修正请求，
        // 而“正在处理中”则可能在状态推进后成功。429 是“当前请求频率过高”，等待后可重试。
        // Retry-After 用整数秒告诉客户端最短等待时间，避免客户端/SDK 立即重试并放大洪峰。
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
            ServletRequestBindingException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
        // MethodArgumentNotValidException 来自 @Valid request body，
        // ConstraintViolationException 来自 @Validated header/path variable，二者都属于 400。
        // HttpMessageNotReadableException 覆盖 malformed JSON；MethodArgumentTypeMismatchException
        // 覆盖 UUID 等 path/query 类型转换失败。没有这两个显式映射，它们会被 catch-all 误报成 500。
        // IllegalArgumentException 目前还覆盖 HTTP adapter 的 Currency.getInstance 和部分 domain invariant。
        // 这是当前小型项目为减少异常层级做的取舍，但边界偏宽：如果未来出现更多 repository restore、
        // worker 或内部组装路径，应改成专用 InvalidRequestException，避免把程序 bug/脏数据误报成客户端 400。
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        // catch-all 必须放在已知业务异常和 Spring MVC 异常之后，只为“未预期故障”兜底。
        // 以后增加新的 HTTP 输入形式时，也要同步补相应 4xx 映射；否则未识别的客户端解析异常
        // 会落到这里，被错误地报告成 500，污染服务端故障率指标。
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
     *
     * <p>status 面向 HTTP intermediary/client 的通用行为，code 面向调用方的稳定程序分支，
     * message 仅用于人类阅读；三者不能互相替代。</p>
     */
    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, Instant.now()));
    }
}
