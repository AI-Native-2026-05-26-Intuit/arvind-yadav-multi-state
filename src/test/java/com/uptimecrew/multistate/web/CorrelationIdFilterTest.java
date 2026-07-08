package com.uptimecrew.multistate.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Mock HttpServletRequest request;
  @Mock HttpServletResponse response;
  @Mock FilterChain chain;

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  @DisplayName("echoes caller correlation id when header is present")
  void echoesCallerCorrelationId() throws Exception {
    when(request.getHeader(CorrelationIdFilter.HEADER)).thenReturn("caller-abc");
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/tenants/t-1");

    filter.doFilterInternal(request, response, chain);

    verify(response).setHeader(CorrelationIdFilter.HEADER, "caller-abc");
    verify(chain).doFilter(request, response);
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("generates UUID when header is absent")
  void generatesUuidWhenHeaderAbsent() throws Exception {
    when(request.getHeader(CorrelationIdFilter.HEADER)).thenReturn(null);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/actuator/health");

    filter.doFilterInternal(request, response, chain);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(response).setHeader(eq(CorrelationIdFilter.HEADER), captor.capture());
    assertThat(captor.getValue()).matches(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  @Test
  @DisplayName("generates UUID when header is blank")
  void generatesUuidWhenHeaderBlank() throws Exception {
    when(request.getHeader(CorrelationIdFilter.HEADER)).thenReturn("   ");
    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/api/v1/tenants");

    filter.doFilterInternal(request, response, chain);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(response).setHeader(eq(CorrelationIdFilter.HEADER), captor.capture());
    assertThat(captor.getValue()).isNotBlank().isNotEqualTo("   ");
  }
}
