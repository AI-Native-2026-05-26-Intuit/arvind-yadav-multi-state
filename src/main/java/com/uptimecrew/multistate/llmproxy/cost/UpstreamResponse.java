package com.uptimecrew.multistate.llmproxy.cost;

import java.util.Objects;

public final class UpstreamResponse {

    private final String modelId;
    private final long inputTokens;
    private final long outputTokens;
    private final long latencyMs;
    private final boolean success;

    public UpstreamResponse(String modelId,
                            long inputTokens,
                            long outputTokens,
                            long latencyMs,
                            boolean success) {
        this.modelId      = Objects.requireNonNull(modelId);
        this.inputTokens  = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs    = latencyMs;
        this.success      = success;
    }

    public String modelId() {
        return modelId;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long latencyMs() {
        return latencyMs;
    }

    public boolean success() {
        return success;
    }
}
