package com.uptimecrew.multi_state.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

public final class TenantLookupHandler
    implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final Logger LOG = LoggerFactory.getLogger(TenantLookupHandler.class);

  // Deferred until first request so SAM local --env-vars overrides are visible at client build.
  private static volatile DynamoDbClient ddb;

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context ctx) {
    String tenantId =
        Optional.ofNullable(event.getPathParameters()).map(p -> p.get("tenantId")).orElse(null);

    String correlationId =
        Optional.ofNullable(event.getHeaders())
            .map(this::correlationIdFromHeaders)
            .orElse(ctx.getAwsRequestId());

    LOG.info(
        "lookup attempt {\"correlationId\":\"{}\",\"tenantId\":\"{}\",\"remainingMs\":{}}",
        correlationId,
        tenantId,
        ctx.getRemainingTimeInMillis());

    if (tenantId == null || tenantId.isBlank()) {
      LOG.warn(
          "error response status=400 msg=missing tenantId correlationId={}", correlationId);
      return errorResponse(400, "missing tenantId path parameter", correlationId);
    }

    TenantRecord record = loadFromDynamo(tenantId);
    if (record == null) {
      EmfPublisher.emitCount("TenantNotFound");
      LOG.warn(
          "error response status=404 msg=tenant not found correlationId={} tenantId={}",
          correlationId,
          tenantId);
      return errorResponse(404, "tenant not found", correlationId);
    }

    try {
      String body = JSON.writeValueAsString(record);
      EmfPublisher.emitCount("TenantLookupSuccess");
      LOG.info(
          "lookup success correlationId={} tenantId={} remainingMs={}",
          correlationId,
          tenantId,
          ctx.getRemainingTimeInMillis());
      return APIGatewayV2HTTPResponse.builder()
          .withStatusCode(200)
          .withHeaders(
              Map.of("Content-Type", "application/json", "x-correlation-id", correlationId))
          .withBody(body)
          .build();
    } catch (Exception e) {
      LOG.error("serialisation failure correlationId={} tenantId={}", correlationId, tenantId, e);
      return errorResponse(500, "serialisation failure", correlationId);
    }
  }

  private String correlationIdFromHeaders(Map<String, String> headers) {
    String direct = headers.get("x-correlation-id");
    if (direct != null) {
      return direct;
    }
    return headers.entrySet().stream()
        .filter(e -> "x-correlation-id".equalsIgnoreCase(e.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private static DynamoDbClient ddb() {
    DynamoDbClient client = ddb;
    if (client == null) {
      String endpoint = System.getenv("DYNAMODB_ENDPOINT_URL");
      String region =
          Optional.ofNullable(System.getenv("AWS_REGION"))
              .filter(r -> !r.isBlank())
              .or(() ->
                  Optional.ofNullable(System.getenv("AWS_DEFAULT_REGION")).filter(r -> !r.isBlank()))
              .orElse("us-east-1");
      var builder = DynamoDbClient.builder().region(Region.of(region));
      if (endpoint != null && !endpoint.isBlank()) {
        builder
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
      }
      client = builder.build();
      ddb = client;
    }
    return client;
  }

  private TenantRecord loadFromDynamo(String id) {
    String table = System.getenv("TENANTS_TABLE");
    if (table == null) {
      throw new IllegalStateException("TENANTS_TABLE env var is not set");
    }
    GetItemRequest req =
        GetItemRequest.builder()
            .tableName(table)
            .key(Map.of("id", AttributeValue.builder().s(id).build()))
            .consistentRead(false)
            .build();
    var resp = ddb().getItem(req);
    if (!resp.hasItem() || resp.item().isEmpty()) {
      return null;
    }
    return TenantRecord.fromItem(resp.item());
  }

  private APIGatewayV2HTTPResponse errorResponse(int status, String msg, String correlationId) {
    return APIGatewayV2HTTPResponse.builder()
        .withStatusCode(status)
        .withHeaders(
            Map.of("Content-Type", "application/json", "x-correlation-id", correlationId))
        .withBody("{\"error\":\"" + msg + "\"}")
        .build();
  }
}
