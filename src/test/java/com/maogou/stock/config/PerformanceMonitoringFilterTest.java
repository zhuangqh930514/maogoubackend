package com.maogou.stock.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceMonitoringFilterTest {

    @Test
    void exposesRequestCorrelationAndServerTimingHeadersForApis() throws Exception {
        PerformanceMonitoringFilter filter = new PerformanceMonitoringFilter(10_000);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/home/overview");
        request.addHeader("X-Request-Id", "client-request-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (currentRequest, currentResponse) -> {
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("client-request-42");
        assertThat(response.getHeader("Server-Timing")).startsWith("app;dur=");
    }
}
