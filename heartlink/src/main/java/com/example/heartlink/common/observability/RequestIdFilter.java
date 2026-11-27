package com.example.heartlink.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Adds a safe, bounded-format request ID to responses and log context. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = safeUuid(request.getHeader(HEADER));
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    private static String safeUuid(String supplied) {
        if (supplied != null) {
            try {
                UUID parsed = UUID.fromString(supplied);
                if (parsed.toString().equalsIgnoreCase(supplied)) {
                    return parsed.toString();
                }
            } catch (IllegalArgumentException ignored) {
                // Never reflect arbitrary header contents into logs or response headers.
            }
        }
        return UUID.randomUUID().toString();
    }
}
