package com.uptimecrew.multi_state.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TenantLookupHandlerTest {

  private final TenantLookupHandler handler = new TenantLookupHandler();

  @AfterEach
  void resetDdbClient() throws Exception {
    Field f = TenantLookupHandler.class.getDeclaredField("ddb");
    f.setAccessible(true);
    f.set(null, null);
  }

  @Test
  void returns400OnBlankPathParam() {
    APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
    event.setPathParameters(Map.of("tenantId", "  "));
    Context ctx = Mockito.mock(Context.class);
    Mockito.when(ctx.getAwsRequestId()).thenReturn("blank-param");
    Mockito.when(ctx.getRemainingTimeInMillis()).thenReturn(9_000);

    APIGatewayV2HTTPResponse resp = handler.handleRequest(event, ctx);

    assertThat(resp.getStatusCode()).isEqualTo(400);
    assertThat(resp.getBody()).contains("missing tenantId");
  }

  @Test
  void returns404WhenTenantNotInDynamo() throws Exception {
    DynamoDbClient mockDdb = Mockito.mock(DynamoDbClient.class);
    Mockito.when(mockDdb.getItem(Mockito.any(GetItemRequest.class)))
        .thenReturn(GetItemResponse.builder().build());

    Field f = TenantLookupHandler.class.getDeclaredField("ddb");
    f.setAccessible(true);
    f.set(null, mockDdb);

    APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
    event.setPathParameters(Map.of("tenantId", "tnt_missing"));

    Context ctx = Mockito.mock(Context.class);
    Mockito.when(ctx.getAwsRequestId()).thenReturn("not-found-req");
    Mockito.when(ctx.getRemainingTimeInMillis()).thenReturn(9_000);

    APIGatewayV2HTTPResponse resp = handler.handleRequest(event, ctx);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("tenant not found");
  }

  @Test
  void returns200WithTenantJsonOnHit() throws Exception {
    Map<String, AttributeValue> item = new HashMap<>();
    item.put("id", AttributeValue.builder().s("tnt_synth_001").build());
    item.put("legalName", AttributeValue.builder().s("Acme LLC").build());
    item.put("primaryState", AttributeValue.builder().s("CA").build());
    item.put("status", AttributeValue.builder().s("ACTIVE").build());
    item.put("capturedAt", AttributeValue.builder().s("2026-01-01T00:00:00Z").build());
    item.put("totalAllocated", AttributeValue.builder().n("100.00").build());

    DynamoDbClient mockDdb = Mockito.mock(DynamoDbClient.class);
    Mockito.when(mockDdb.getItem(Mockito.any(GetItemRequest.class)))
        .thenReturn(GetItemResponse.builder().item(item).build());

    Field f = TenantLookupHandler.class.getDeclaredField("ddb");
    f.setAccessible(true);
    f.set(null, mockDdb);

    APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
    event.setPathParameters(Map.of("tenantId", "tnt_synth_001"));

    Context ctx = Mockito.mock(Context.class);
    Mockito.when(ctx.getAwsRequestId()).thenReturn("success-req");
    Mockito.when(ctx.getRemainingTimeInMillis()).thenReturn(9_000);

    APIGatewayV2HTTPResponse resp = handler.handleRequest(event, ctx);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("tnt_synth_001").contains("Acme LLC");
    assertThat(resp.getHeaders()).containsEntry("x-correlation-id", "success-req");
  }

  @Test
  void resolvesCorrelationIdFromMixedCaseHeader() {
    APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
    event.setHeaders(Map.of("X-Correlation-Id", "mixed-case-id"));
    Context ctx = Mockito.mock(Context.class);
    Mockito.when(ctx.getAwsRequestId()).thenReturn("aws-req-y");
    Mockito.when(ctx.getRemainingTimeInMillis()).thenReturn(9_000);

    APIGatewayV2HTTPResponse resp = handler.handleRequest(event, ctx);

    assertThat(resp.getHeaders()).containsEntry("x-correlation-id", "mixed-case-id");
  }

  @Test
  void returns400OnMissingPathParam() {
    APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
    Context ctx = Mockito.mock(Context.class);
    Mockito.when(ctx.getAwsRequestId()).thenReturn("test-req-1");
    Mockito.when(ctx.getRemainingTimeInMillis()).thenReturn(9_000);

    APIGatewayV2HTTPResponse resp = handler.handleRequest(event, ctx);

    assertThat(resp.getStatusCode()).isEqualTo(400);
    assertThat(resp.getBody()).contains("missing tenantId");
    assertThat(resp.getHeaders()).containsEntry("x-correlation-id", "test-req-1");
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

  @Test
  void returns500WithCorrelationIdOnDynamoDbFailure() throws Exception {
    DynamoDbClient mockDdb = Mockito.mock(DynamoDbClient.class);
    Mockito.when(mockDdb.getItem(Mockito.any(GetItemRequest.class)))
        .thenThrow(DynamoDbException.builder().message("throttled").build());

    Field f = TenantLookupHandler.class.getDeclaredField("ddb");
    f.setAccessible(true);
    f.set(null, mockDdb);

    APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
    event.setPathParameters(Map.of("tenantId", "tnt_synth_001"));

    Context ctx = Mockito.mock(Context.class);
    Mockito.when(ctx.getAwsRequestId()).thenReturn("ddb-fail-req");
    Mockito.when(ctx.getRemainingTimeInMillis()).thenReturn(9_000);

    APIGatewayV2HTTPResponse resp = handler.handleRequest(event, ctx);

    assertThat(resp.getStatusCode()).isEqualTo(500);
    assertThat(resp.getBody()).contains("lookup failure");
    assertThat(resp.getHeaders()).containsEntry("x-correlation-id", "ddb-fail-req");
  }
}
