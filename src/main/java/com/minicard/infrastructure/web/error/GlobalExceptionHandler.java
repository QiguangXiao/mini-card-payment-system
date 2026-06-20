package com.minicard.infrastructure.web.error;

import java.time.Instant;
import java.util.NoSuchElementException;

import com.minicard.authorization.application.IdempotencyConflictException;
import com.minicard.repayment.application.RepaymentConflictException;
import com.minicard.repayment.application.RepaymentRejectedException;
import com.minicard.statement.application.StatementGenerationRejectedException;
import com.minicard.transaction.application.PresentmentConflictException;
import com.minicard.transaction.application.PresentmentRejectedException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(StatementGenerationRejectedException.class)
    public ResponseEntity<ErrorResponse> handleStatementGenerationRejected(
            StatementGenerationRejectedException exception
    ) {
        return error(HttpStatus.CONFLICT, "STATEMENT_GENERATION_REJECTED", exception.getMessage());
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

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            ServletRequestBindingException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
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
