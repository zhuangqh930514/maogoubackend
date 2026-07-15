package com.maogou.stock.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PerformanceMonitoringFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PerformanceMonitoringFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    private final long slowRequestThresholdMs;

    public PerformanceMonitoringFilter(
            @Value("${maogou.performance.slow-request-threshold-ms:1000}") long slowRequestThresholdMs
    ) {
        this.slowRequestThresholdMs = Math.max(1, slowRequestThresholdMs);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader("X-Request-Id"));
        long startedAt = System.nanoTime();
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        wrappedResponse.setHeader("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, wrappedResponse);
        } finally {
            double durationMs = (System.nanoTime() - startedAt) / 1_000_000.0;
            wrappedResponse.setHeader(
                    "Server-Timing",
                    "app;dur=" + String.format(java.util.Locale.ROOT, "%.1f", durationMs)
            );
            if (durationMs >= slowRequestThresholdMs) {
                log.warn("slow api request, method={}, path={}, status={}, durationMs={}",
                        request.getMethod(), request.getRequestURI(), wrappedResponse.getStatus(), Math.round(durationMs));
            }
            MDC.remove("requestId");
            wrappedResponse.copyBodyToResponse();
        }
    }

    private static String resolveRequestId(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
