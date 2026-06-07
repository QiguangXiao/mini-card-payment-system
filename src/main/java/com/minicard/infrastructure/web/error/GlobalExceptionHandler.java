package com.minicard.infrastructure.web.error;

import java.time.Instant;

import com.minicard.authorization.application.AuthorizationNotFoundException;
import com.minicard.authorization.application.IdempotencyConflictException;
import com.minicard.authorization.domain.InvalidAuthorizationStateException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthorizationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(AuthorizationNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "AUTHORIZATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
            IdempotencyConflictException exception
    ) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(InvalidAuthorizationStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAuthorizationState(
            InvalidAuthorizationStateException exception
    ) {
        return error(HttpStatus.CONFLICT, "INVALID_AUTHORIZATION_STATE", exception.getMessage());
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

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, Instant.now()));
    }
}
