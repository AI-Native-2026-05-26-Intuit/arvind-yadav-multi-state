package com.uptimecrew.multistate.mcp;

import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.service.AllocationService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Single read-only MCP-facing surface for LLM clients (e.g. Claude Code).
 * Spring AI's MCP server auto-discovers {@link Tool}-annotated methods on
 * Spring beans and publishes them over the configured transport (SSE here,
 * per application.yml). One id in, one read-model document out — no list,
 * search, or write surface — keeps the blast radius of an LLM tool call to
 * a single document lookup.
 */
@Service
public class TenantMcpServer {

    private static final Logger LOG = LoggerFactory.getLogger(TenantMcpServer.class);

    private final AllocationService service;

    public TenantMcpServer(AllocationService service) {
        this.service = service;
    }

    @Tool(description = "Look up a tenant by id and return its summary read model")
    public Optional<TenantReadModel> lookupTenant(
            @ToolParam(description = "The tenant id") String id) {
        LOG.info("mcp tool lookupTenant invoked id={}", id);
        return service.findById(id);
    }
}
