package com.uptimecrew.multistate.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * W3 D2 correlation-id propagation: caller {@code x-correlation-id} wins, else a
 * synthetic UUID. Value is stored in MDC as {@code correlationId} for JSON logs
 * and echoed on the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {

  static final String HEADER = "x-correlation-id";
  static final String MDC_KEY = "correlationId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = req.getHeader(HEADER);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }
    MDC.put(MDC_KEY, correlationId);
    res.setHeader(HEADER, correlationId);
    try {
      chain.doFilter(req, res);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
