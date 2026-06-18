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
 * Spring AI's Anthropic chat client, re-validates the output against a
 * hand-written JSON Schema, and surfaces the call as a manual OTel CLIENT
 * span named {@code llm.summarize}.
 *
 * <p>Two-stage contract: Spring AI parses the model's JSON output into the
 * record (schema-shape via Jackson 3), then we re-validate the same record
 * against {@code resources/schemas/TenantSummary.schema.json}. Jackson
 * binding only enforces field names + Java types; the JSON Schema also
 * enforces {@code additionalProperties: false}, {@code required: [...]} and
 * value constraints (e.g. {@code minimum: 0} on {@code stateCount}).
 *
 * <p>The {@code llm.summarize} span has four attributes:
 * <ul>
 *   <li>{@code llm.model} — set BEFORE the call from configured model name.</li>
 *   <li>{@code llm.input.aggregate_id} — set BEFORE the call (the id being
 *       summarised) so the trace can be filtered by input.</li>
 *   <li>{@code llm.tokens.in} / {@code llm.tokens.out} — set AFTER the call
 *       from {@link ChatResponse#getMetadata()}'s {@link Usage}. Token counts
 *       don't exist until the provider replies, hence post-call.</li>
 * </ul>
 *
 * <p>Uses the networknt 3.x API ({@link SchemaRegistry} / {@link Schema} /
 * {@link Error}) bound to Jackson 3 ({@code tools.jackson.*}) to stay
 * compatible with the {@code mcp-json-jackson3} module that Spring AI's MCP
 * server starter pulls in; the autoconfigured Jackson 2 {@code ObjectMapper}
 * is the wrong type for {@link Schema#validate(JsonNode)}.
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
                             @Value("${spring.ai.anthropic.chat.options.model:claude-sonnet-4-5}") String configuredModel) {
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
            ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
            String body = response == null ? null : response.getResult().getOutput().getText();
            if (body == null || body.isBlank()) {
                throw new IllegalStateException("LLM returned empty body");
            }
            TenantSummary result = jackson3.readValue(body, TenantSummary.class);

            // Spring AI surfaces token counts on ChatResponse.getMetadata().getUsage().
            // If the provider didn't return usage, both values come back as 0L.
            Usage usage = response.getMetadata().getUsage();
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
        } catch (Exception ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getClass().getSimpleName());
            throw new IllegalStateException("LLM call failed", ex);
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
