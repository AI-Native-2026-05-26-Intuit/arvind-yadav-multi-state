package com.uptimecrew.multi_state.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Emits CloudWatch Embedded Metric Format (EMF) lines through SLF4J — no
 * {@code cloudwatch:PutMetricData} call. Lambda's log pipeline extracts custom
 * metrics from the JSON envelope.
 */
final class EmfPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(EmfPublisher.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String NAMESPACE =
      System.getenv().getOrDefault("METRICS_NAMESPACE", "MultistateDev");
  private static final String SERVICE = "tenant-lookup";

  private EmfPublisher() {}

  static void emitCount(String metricName) {
    LOG.info(toEmfLine(metricName, 1));
  }

  static String toEmfLine(String metricName, int value) {
    ObjectNode root = JSON.createObjectNode();
    ObjectNode aws = root.putObject("_aws");
    aws.put("Timestamp", Instant.now().toEpochMilli());

    ObjectNode metricDef = JSON.createObjectNode();
    metricDef.put("Name", metricName);
    metricDef.put("Unit", "Count");

    ArrayNode metrics = JSON.createArrayNode().add(metricDef);
    ObjectNode cloudWatchMetrics = JSON.createObjectNode();
    cloudWatchMetrics.put("Namespace", NAMESPACE);
    cloudWatchMetrics.set(
        "Dimensions", JSON.createArrayNode().add(JSON.createArrayNode().add("Service")));
    cloudWatchMetrics.set("Metrics", metrics);
    aws.set("CloudWatchMetrics", JSON.createArrayNode().add(cloudWatchMetrics));

    root.put("Service", SERVICE);
    root.put(metricName, value);

    try {
      return JSON.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("EMF serialisation failed for " + metricName, e);
    }
  }
}
