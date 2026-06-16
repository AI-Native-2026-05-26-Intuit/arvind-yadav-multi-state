package com.uptimecrew.multistate.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges the {@link TenantMcpServer} bean's {@code @Tool}-annotated methods
 * into a {@link ToolCallbackProvider}, which Spring AI's MCP server picks up
 * via {@code ToolCallbackConverterAutoConfiguration.syncTools(...)} and
 * publishes over the configured transport.
 *
 * <p>Without this bridge the {@code @Tool} annotations exist but no provider
 * surfaces them to the MCP server (Spring AI 2.0's annotation-scanner
 * targets {@code @McpTool}, a different annotation).
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider tenantMcpToolCallbacks(TenantMcpServer tenantMcpServer) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tenantMcpServer)
                .build();
    }
}
