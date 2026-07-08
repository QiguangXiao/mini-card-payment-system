package com.minicard.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class HttpRequestLoggingFilterTest {

    private final HttpRequestLoggingFilter filter = new HttpRequestLoggingFilter();

    @Test
    void logsRequestStartAndCompletion(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(output)
                .contains("request_started")
                .contains("method=GET")
                .contains("path=/api/health")
                .contains("request_completed")
                .contains("status=200")
                .contains("durationMs=");
    }

    @Test
    void reusesIncomingRequestIdAndExposesItThroughMdcDuringRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER, "client-request-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(HttpRequestLoggingFilter.MDC_REQUEST_ID_KEY))
                        .isEqualTo("client-request-001"));

        assertThat(response.getHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER))
                .isEqualTo("client-request-001");
        // Tomcat 线程池会复用 request thread；请求结束后必须清掉 MDC，避免下一次请求串号。
        assertThat(MDC.get(HttpRequestLoggingFilter.MDC_REQUEST_ID_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenWhenDownstreamThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boom");
        MockHttpServletResponse response = new MockHttpServletResponse();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                            throw new IllegalStateException("boom");
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(HttpRequestLoggingFilter.MDC_REQUEST_ID_KEY)).isNull();
    }
}
