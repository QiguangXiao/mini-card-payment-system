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
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/statements");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(output)
                .contains("request_started")
                .contains("method=GET")
                .contains("path=/api/statements")
                .contains("request_completed")
                .contains("status=200")
                .contains("durationMs=");
    }

    @Test
    void reusesIncomingRequestIdAndExposesItThroughMdcDuringRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/statements");
        request.addHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER, "client-request_001.trace:abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(HttpRequestLoggingFilter.MDC_REQUEST_ID_KEY))
                        .isEqualTo("client-request_001.trace:abc"));

        assertThat(response.getHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER))
                .isEqualTo("client-request_001.trace:abc");
        // Tomcat 线程池会复用 request thread；请求结束后必须清掉 MDC，避免下一次请求串号。
        assertThat(MDC.get(HttpRequestLoggingFilter.MDC_REQUEST_ID_KEY)).isNull();
    }

    @Test
    void replacesUnsafeIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/statements");
        request.addHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER, "bad request\nid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(HttpRequestLoggingFilter.MDC_REQUEST_ID_KEY))
                        .isNotEqualTo("bad request\nid")
                        .matches("[0-9a-f-]{36}"));

        assertThat(response.getHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER))
                .isNotEqualTo("bad request\nid")
                .matches("[0-9a-f-]{36}");
        assertThat(MDC.get(HttpRequestLoggingFilter.MDC_REQUEST_ID_KEY)).isNull();
    }

    @Test
    void replacesOverlongIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/statements");
        String overlong = "a".repeat(65);
        request.addHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER, overlong);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(HttpRequestLoggingFilter.REQUEST_ID_HEADER))
                .isNotEqualTo(overlong)
                .matches("[0-9a-f-]{36}");
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
