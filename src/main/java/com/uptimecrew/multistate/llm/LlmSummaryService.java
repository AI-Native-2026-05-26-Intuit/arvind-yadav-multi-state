package com.uptimecrew.multistate.llm;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.uptimecrew.multistate.graphql.TenantSummary;
import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import java.io.InputStream;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
 * <p>Two-stage contract: Spring AI's {@code .entity(TenantSummary.class)}
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
 */
@Service
public class LlmSummaryService {

    private static final Logger LOG = LoggerFactory.getLogger(LlmSummaryService.class);

    private final ChatClient chatClient;
    private final TenantReadModelRepository readModelRepository;
    private final ObjectMapper jackson3 = JsonMapper.builder().build();
    private final Schema schema;

    public LlmSummaryService(ChatClient.Builder builder,
                             TenantReadModelRepository readModelRepository) {
        this.chatClient = builder.build();
        this.readModelRepository = readModelRepository;
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
        TenantSummary result = chatClient.prompt().user(prompt).call().entity(TenantSummary.class);
        validate(result);
        LOG.info("structured-output ok id={}", id);
        return result;
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
