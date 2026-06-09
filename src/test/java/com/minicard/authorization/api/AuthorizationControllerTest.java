package com.minicard.authorization.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.minicard.authorization.application.AuthorizationService;
import com.minicard.authorization.application.AuthorizationNotFoundException;
import com.minicard.authorization.application.IdempotencyConflictException;
import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.authorization.domain.Money;
import com.minicard.infrastructure.web.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthorizationController.class)
@Import(GlobalExceptionHandler.class)
class AuthorizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthorizationService authorizationService;

    @Test
    void createsAuthorization() throws Exception {
        when(authorizationService.authorize(any())).thenReturn(approvedAuthorization());

        mockMvc.perform(post("/api/authorizations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": "card-123",
                                  "amount": 100,
                                  "currency": "JPY",
                                  "merchantId": "merchant-123",
                                  "merchantCountry": "JP",
                                  "cardholderCountry": "JP"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.cardId").value("card-123"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.declineReason").doesNotExist())
                .andExpect(jsonPath("$.decidedAt").exists());
    }

    @Test
    void returnsDeclinedAuthorizationWithReason() throws Exception {
        when(authorizationService.authorize(any())).thenReturn(declinedAuthorization());

        mockMvc.perform(post("/api/authorizations")
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": "card-123",
                                  "amount": 200000,
                                  "currency": "JPY",
                                  "merchantId": "merchant-123",
                                  "merchantCountry": "JP",
                                  "cardholderCountry": "JP"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason")
                        .value("SINGLE_TRANSACTION_LIMIT_EXCEEDED"));
    }

    @Test
    void rejectsInvalidAmount() throws Exception {
        mockMvc.perform(post("/api/authorizations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": "card-123",
                                  "amount": 0,
                                  "currency": "JPY",
                                  "merchantId": "merchant-123",
                                  "merchantCountry": "JP",
                                  "cardholderCountry": "JP"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsConflictWhenIdempotencyKeyIsReusedForDifferentRequest() throws Exception {
        when(authorizationService.authorize(any())).thenThrow(new IdempotencyConflictException());

        mockMvc.perform(post("/api/authorizations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": "card-123",
                                  "amount": 200,
                                  "currency": "JPY",
                                  "merchantId": "merchant-123",
                                  "merchantCountry": "JP",
                                  "cardholderCountry": "JP"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void getsAuthorization() throws Exception {
        Authorization authorization = approvedAuthorization();
        when(authorizationService.get(authorization.id())).thenReturn(authorization);

        mockMvc.perform(get("/api/authorizations/{id}", authorization.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(authorization.id().toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void returnsNotFoundForUnknownAuthorization() throws Exception {
        UUID id = UUID.fromString("8f2d8907-0471-4209-9862-73e09f62cd1f");
        when(authorizationService.get(id)).thenThrow(new AuthorizationNotFoundException(id));

        mockMvc.perform(get("/api/authorizations/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_NOT_FOUND"));
    }

    @Test
    void hidesUnexpectedInternalErrorDetails() throws Exception {
        when(authorizationService.authorize(any()))
                .thenThrow(new RuntimeException("database connection details"));

        mockMvc.perform(post("/api/authorizations")
                        .header("Idempotency-Key", "key-internal-error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": "card-123",
                                  "amount": 100,
                                  "currency": "JPY",
                                  "merchantId": "merchant-123",
                                  "merchantCountry": "JP",
                                  "cardholderCountry": "JP"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    private Authorization approvedAuthorization() {
        return Authorization.restore(
                UUID.fromString("fb6933e2-20ea-4268-b1c2-21c6705b1884"),
                "fingerprint-1",
                "card-123",
                new Money(new BigDecimal("100.00"), Currency.getInstance("JPY")),
                AuthorizationStatus.APPROVED,
                null,
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:01Z")
        );
    }

    private Authorization declinedAuthorization() {
        return Authorization.restore(
                UUID.fromString("9e5958ad-8be0-44dc-bded-da06199c4a73"),
                "fingerprint-2",
                "card-123",
                new Money(new BigDecimal("200000.00"), Currency.getInstance("JPY")),
                AuthorizationStatus.DECLINED,
                AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED,
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:01Z")
        );
    }
}
