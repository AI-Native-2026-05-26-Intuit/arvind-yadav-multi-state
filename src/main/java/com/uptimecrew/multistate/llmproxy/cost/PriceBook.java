package com.uptimecrew.multistate.llmproxy.cost;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public final class PriceBook {

    public static final String CLAUDE_35_SONNET =
            "anthropic.claude-3-5-sonnet-20241022-v2:0";
    public static final String TITAN_EMBED_V2 =
            "amazon.titan-embed-text-v2:0";

    private final Map<String, BigDecimal> pricePerKTokens;

    public PriceBook(Map<String, BigDecimal> pricePerKTokens) {
        this.pricePerKTokens = Map.copyOf(pricePerKTokens);
    }

    public static PriceBook defaults() {
        return new PriceBook(Map.of(
                CLAUDE_35_SONNET, new BigDecimal("0.00300000"),
                TITAN_EMBED_V2,   new BigDecimal("0.00002000")
        ));
    }

    public BigDecimal priceFor(String modelId) {
        BigDecimal price = pricePerKTokens.get(modelId);
        if (price == null) {
            throw new IllegalArgumentException("unknown modelId: " + modelId);
        }
        return price;
    }

    public boolean hasModel(String modelId) {
        return pricePerKTokens.containsKey(Objects.requireNonNull(modelId));
    }
}
