package com.uptimecrew.multi_state.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TenantLookupHandlerTest {

  private final TenantLookupHandler handler = new TenantLookupHandler();

  @Test
  void returns400OnMissingPathParam() {
    APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
    Context ctx = Mockito.mock(Context.class);
    Mockito.when(ctx.getAwsRequestId()).thenReturn("test-req-1");
    Mockito.when(ctx.getRemainingTimeInMillis()).thenReturn(9_000);

    APIGatewayV2HTTPResponse resp = handler.handleRequest(event, ctx);

    assertThat(resp.getStatusCode()).isEqualTo(400);
    assertThat(resp.getBody()).contains("missing tenantId");
  }

  @Test
  void echoesCallerCorrelationIdWhenSupplied() {
    APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
    event.setHeaders(Map.of("x-correlation-id", "caller-corr-42"));
    Context ctx = Mockito.mock(Context.class);
    Mockito.when(ctx.getAwsRequestId()).thenReturn("aws-req-x");
    Mockito.when(ctx.getRemainingTimeInMillis()).thenReturn(9_000);

    APIGatewayV2HTTPResponse resp = handler.handleRequest(event, ctx);

    assertThat(resp.getHeaders()).containsEntry("x-correlation-id", "caller-corr-42");
  }
}
