package com.minicard.infrastructure.web.error;

import java.sql.SQLException;
import java.util.UUID;

import com.minicard.authorization.application.IdempotencyConflictException;
import com.minicard.repayment.application.RepaymentConflictException;
import com.minicard.repayment.application.RepaymentRejectedException;
import com.minicard.transaction.application.PresentmentConflictException;
import com.minicard.transaction.application.PresentmentRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    // 测试 @ExceptionHandler 的真实 MVC 分派，防止方法本身正确但异常仍被 catch-all 500 抢走。
    void dispatchesTransactionConnectionFailureToDatabaseUnavailableHandler() throws Exception {
        MockMvc mockMvc = standaloneSetup(new FailingController())
                .setControllerAdvice(handler)
                .build();

        mockMvc.perform(get("/test/database-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
    }

    @Test
    // 非法 UUID 是客户端 path 参数错误；如果没有显式 MVC 类型转换异常映射，会被 catch-all 误报为 500。
    void mapsPathVariableTypeMismatchToBadRequest() throws Exception {
        MockMvc mockMvc = standaloneSetup(new FailingController())
                .setControllerAdvice(handler)
                .build();

        mockMvc.perform(get("/test/resources/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    // malformed JSON 在 controller 执行前就失败，仍应归类为客户端 400，而不是内部 500。
    void mapsUnreadableJsonToBadRequest() throws Exception {
        MockMvc mockMvc = standaloneSetup(new FailingController())
                .setControllerAdvice(handler)
                .build();

        mockMvc.perform(post("/test/request-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    // HTTP status 都是 409，但稳定 error code 必须保留具体原因，客户端不能靠解析 message 分支。
    void mapsBusinessConflictsToDistinctConflictCodes() {
        assertConflict(
                handler.handleIdempotencyConflict(new IdempotencyConflictException()),
                "IDEMPOTENCY_CONFLICT"
        );
        assertConflict(
                handler.handlePresentmentConflict(new PresentmentConflictException()),
                "PRESENTMENT_CONFLICT"
        );
        assertConflict(
                handler.handlePresentmentRejected(new PresentmentRejectedException("state conflict")),
                "PRESENTMENT_REJECTED"
        );
        assertConflict(
                handler.handleRepaymentConflict(new RepaymentConflictException()),
                "REPAYMENT_CONFLICT"
        );
        assertConflict(
                handler.handleRepaymentRejected(new RepaymentRejectedException("state conflict")),
                "REPAYMENT_REJECTED"
        );
    }

    @Test
    // 测试目的：事务入口拿不到 Hikari connection 时必须返回明确可重试的 503，而不是 catch-all 500。
    void mapsCannotCreateTransactionToServiceUnavailable() {
        var response = handler.handleDatabaseUnavailable(
                new CannotCreateTransactionException("could not open JDBC connection")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("DATABASE_UNAVAILABLE");
    }

    @Test
    // variant：非事务 MyBatis/JDBC 查询直接拿连接失败时使用同一 503 contract。
    void mapsCannotGetJdbcConnectionToServiceUnavailable() {
        var response = handler.handleDatabaseUnavailable(
                new CannotGetJdbcConnectionException("could not get JDBC connection", new SQLException("timeout"))
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("DATABASE_UNAVAILABLE");
    }

    private void assertConflict(ResponseEntity<ErrorResponse> response, String expectedCode) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(expectedCode);
    }

    @RestController
    private static class FailingController {

        @GetMapping("/test/database-unavailable")
        void failToStartTransaction() {
            throw new CannotCreateTransactionException("could not open JDBC connection");
        }

        @GetMapping("/test/resources/{id}")
        void readResource(@PathVariable UUID id) {
            // 空方法即可：测试关注的是进入 controller 前的 UUID conversion failure。
        }

        @PostMapping("/test/request-body")
        void readRequestBody(@RequestBody TestRequest request) {
            // 空方法即可：测试关注的是进入 controller 前的 JSON deserialization failure。
        }
    }

    private record TestRequest(String value) {
    }
}
