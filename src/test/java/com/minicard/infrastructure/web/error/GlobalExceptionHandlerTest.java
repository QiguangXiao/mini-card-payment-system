package com.minicard.infrastructure.web.error;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @RestController
    private static class FailingController {

        @GetMapping("/test/database-unavailable")
        void failToStartTransaction() {
            throw new CannotCreateTransactionException("could not open JDBC connection");
        }
    }
}
