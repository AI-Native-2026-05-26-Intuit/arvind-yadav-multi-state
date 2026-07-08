package com.uptimecrew.multistate.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

  private final RateLimitFilter filter = new RateLimitFilter();

  @Mock HttpServletRequest request;
  @Mock HttpServletResponse response;
  @Mock FilterChain chain;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("bypasses non-summary API paths")
  void bypassesNonSummaryPaths() throws Exception {
    when(request.getRequestURI()).thenReturn("/api/v1/tenants/t-1");

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
  }

  @Test
  @DisplayName("bypasses summary path when caller is not JWT-authenticated")
  void bypassesWhenNotJwtAuthenticated() throws Exception {
    when(request.getRequestURI()).thenReturn("/api/v1/tenants/t-1/summary");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("user", "n/a"));

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
  }

  @Test
  @DisplayName("allows requests within the per-subject bucket")
  void allowsWithinBucket() throws Exception {
    when(request.getRequestURI()).thenReturn("/api/v1/tenants/t-1/summary");
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("sub-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .claim("scope", "read")
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
  }

  @Test
  @DisplayName("returns 429 when bucket is exhausted")
  void returns429WhenBucketExhausted() throws Exception {
    when(request.getRequestURI()).thenReturn("/api/v1/tenants/t-1/summary");
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("sub-rate-limited")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    StringWriter body = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(body));

    for (int i = 0; i < 11; i++) {
      filter.doFilterInternal(request, response, chain);
    }

    verify(response).setStatus(429);
    verify(response).setHeader("Retry-After", "60");
    verify(chain, org.mockito.Mockito.atMost(10)).doFilter(any(), any());
  }
}
