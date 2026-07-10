package com.uptimecrew.multistate.llmproxy.cost;

import java.time.Instant;
import java.util.Objects;

public final class CallContext {

    private final Instant at;
    private final String tenant;

    public CallContext(Instant at, String tenant) {
        this.at     = Objects.requireNonNull(at);
        this.tenant = Objects.requireNonNull(tenant);
    }

    public Instant at() {
        return at;
    }

    public String tenant() {
        return tenant;
    }
}
