package io.github.cyonecoder.salatapi.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Attaches a correlation id to every request, for tracing across the log and the
 * error body.
 *
 * <p>An inbound {@code X-Correlation-Id} is honoured (so a caller can trace a
 * request end to end); otherwise one is generated. The id is put on the response
 * header, the SLF4J {@link MDC} (so it appears in every log line), and the request
 * attribute the error handler reads.
 */
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** The header carrying the correlation id, inbound and outbound. */
    public static final String HEADER = "X-Correlation-Id";
    /** The request attribute the error handler reads it from. */
    public static final String ATTRIBUTE = "correlationId";
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
