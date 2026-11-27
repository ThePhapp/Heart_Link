package com.example.heartlink.common.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTests {
    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearRequestId() {
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    @Test
    void preservesCanonicalUuidAndClearsLogContextAfterRequest() throws Exception {
        String requestId = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, requestId);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(requestId);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesArbitraryHeaderValueInsteadOfReflectingIt() throws Exception {
        String sensitiveValue = "Bearer secret-token-user@example.com";
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, sensitiveValue);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String responseId = response.getHeader(RequestIdFilter.HEADER);
        assertThat(responseId).isNotEqualTo(sensitiveValue);
        assertThat(UUID.fromString(responseId).toString()).isEqualTo(responseId);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
