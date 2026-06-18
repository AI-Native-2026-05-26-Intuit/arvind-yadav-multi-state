package com.uptimecrew.multistate.llm;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.uptimecrew.multistate.graphql.TenantSummary;
import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds a structured {@link TenantSummary} from the tenant read model using
 * Spring AI's Anthropic chat client and re-validates the output against a
 * hand-written JSON Schema.
 *
 * <p>Two-stage contract: Spring AI's {@code .responseEntity(TenantSummary.class)}
 * converter coerces the LLM JSON into the record (schema-shape via Jackson),
 * then we re-validate the same record against
 * {@code resources/schemas/TenantSummary.schema.json}. Why both?
 * The Jackson binding only enforces what the record type captures (field names
 * + Java types). The JSON Schema also enforces {@code additionalProperties: false},
 * {@code required: [...]}, and value constraints (e.g. {@code minimum: 0} on
 * {@code stateCount}). A future model release that drifts the shape — adds an
 * extra field, omits one, or sends a negative {@code stateCount} — fails loudly
 * here instead of silently shipping a malformed summary.
 *
 * <p>Uses the networknt 3.x API ({@link SchemaRegistry} / {@link Schema} /
 * {@link Error}) bound to Jackson 3 ({@code tools.jackson.*}). This is the
 * minimum line compatible with the {@code mcp-json-jackson3} module that
 * Spring AI's MCP server starter pulls in: MCP calls
 * {@code Schema.validate(tools.jackson.databind.JsonNode)} on startup, and
 * networknt 1.x/2.x only expose the Jackson 2 overload (and 1.x lacks the
 * {@code Dialects} class entirely). Spring's autoconfigured
 * {@code com.fasterxml.jackson.databind.ObjectMapper} is the wrong type here,
 * so we own a Jackson 3 {@link JsonMapper} locally — used ONLY for the
 * schema-validation conversion, never elsewhere in the app.
 *
 * <p>The ChatClient call is wrapped in a manual OTel span named
 * {@code llm.summarize} so the LLM segment is visible in Jaeger as a child of
 * whatever resolver / controller span is current. We tag the static
 * {@code llm.model} and {@code llm.input.aggregate_id} attributes before the
 * call (so a failure still carries them on the span) and the dynamic
 * {@code llm.tokens.in} / {@code llm.tokens.out} attributes after the call,
 * sourced from {@link ChatResponse#getMetadata()} — those values only exist
 * once the provider has answered, which is why they go on after.
 */
@Service
public class LlmSummaryService {

    private static final Logger LOG = LoggerFactory.getLogger(LlmSummaryService.class);

    private static final AttributeKey<String> ATTR_MODEL        = AttributeKey.stringKey("llm.model");
    private static final AttributeKey<String> ATTR_AGGREGATE_ID = AttributeKey.stringKey("llm.input.aggregate_id");
    private static final AttributeKey<Long>   ATTR_TOKENS_IN    = AttributeKey.longKey("llm.tokens.in");
    private static final AttributeKey<Long>   ATTR_TOKENS_OUT   = AttributeKey.longKey("llm.tokens.out");

    private final ChatClient chatClient;
    private final TenantReadModelRepository readModelRepository;
    private final ObjectMapper jackson3 = JsonMapper.builder().build();
    private final Schema schema;
    private final Tracer tracer;
    private final String configuredModel;

    public LlmSummaryService(ChatClient.Builder builder,
                             TenantReadModelRepository readModelRepository,
                             OpenTelemetry openTelemetry,
                             @Value("${spring.ai.anthropic.chat.options.model:unknown}") String configuredModel) {
        this.chatClient = builder.build();
        this.readModelRepository = readModelRepository;
        this.tracer = openTelemetry.getTracer("com.uptimecrew.multistate.llm");
        this.configuredModel = configuredModel;
        try (InputStream in = new ClassPathResource(
                "schemas/TenantSummary.schema.json").getInputStream()) {
            this.schema = SchemaRegistry
                    .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(in);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to load TenantSummary schema", ex);
        }
    }

    public TenantSummary summarize(String id) {
        TenantReadModel doc = readModelRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown id " + id));
        String prompt = "Summarise this tenant as a JSON object matching the "
                + "TenantSummary schema. Output JSON only, no prose. Domain data: "
                + doc.toString();

        Span span = tracer.spanBuilder("llm.summarize")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(ATTR_MODEL, configuredModel)
                .setAttribute(ATTR_AGGREGATE_ID, id)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            // responseEntity(Class) gives us both the parsed record AND the raw
            // ChatResponse in one call — needed so we can read token usage off
            // ChatResponse.getMetadata() without firing the model twice.
            ResponseEntity<ChatResponse, TenantSummary> resp =
                    chatClient.prompt().user(prompt).call().responseEntity(TenantSummary.class);
            TenantSummary result = resp.entity();

            Usage usage = resp.response().getMetadata().getUsage();
            long promptTokens = safeLong(usage == null ? null : usage.getPromptTokens());
            long completionTokens = safeLong(usage == null ? null : usage.getCompletionTokens());
            span.setAttribute(ATTR_TOKENS_IN, promptTokens);
            span.setAttribute(ATTR_TOKENS_OUT, completionTokens);

            validate(result);
            span.setStatus(StatusCode.OK);
            LOG.info("structured-output ok id={} model={} tokens.in={} tokens.out={}",
                     id, configuredModel, promptTokens, completionTokens);
            return result;
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getClass().getSimpleName());
            throw ex;
        } finally {
            span.end();
        }
    }

    private static long safeLong(Number n) {
        return n == null ? 0L : n.longValue();
    }

    private void validate(TenantSummary candidate) {
        JsonNode node = jackson3.valueToTree(candidate);
        List<Error> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            LOG.warn("schema violation errors={}", errors);
            throw new IllegalStateException("LLM output failed JSON Schema validation: " + errors);
        }
    }
}
