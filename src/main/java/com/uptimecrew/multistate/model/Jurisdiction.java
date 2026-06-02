package com.uptimecrew.multistate.model;

import java.util.Objects;

/**
 * A taxing authority that income can be allocated to. Identified by {@code code}
 * (e.g. {@code "US-CA"}, {@code "US-CA-SF"}); the {@code displayName} is for humans
 * and {@link JurisdictionKind} indicates the level of government.
 *
 * <p>Value object: equality is by all three fields.
 */
public final class Jurisdiction {

    private final String code;
    private final String displayName;
    private final JurisdictionKind kind;

    public Jurisdiction(String code, String displayName, JurisdictionKind kind) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(kind, "kind");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        this.code = code;
        this.displayName = displayName;
        this.kind = kind;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public JurisdictionKind kind() {
        return kind;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Jurisdiction other)) return false;
        return code.equals(other.code)
            && displayName.equals(other.displayName)
            && kind == other.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, displayName, kind);
    }

    @Override
    public String toString() {
        return "Jurisdiction[code=" + code
            + ", displayName=" + displayName
            + ", kind=" + kind + "]";
    }
}
