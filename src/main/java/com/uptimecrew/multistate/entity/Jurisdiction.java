package com.uptimecrew.multistate.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

/**
 * Maps the {@code multistate.jurisdiction} table (W2 D1 DDL): the reference
 * table of US states (and DC) that {@code tenant} and {@code allocation} both
 * FK into.
 *
 * <p>Pure reference data — it is only ever referenced, never references out —
 * so it carries no {@code @OneToMany}/{@code @ManyToOne}. The primary key is
 * the USPS {@code code} column, mapped here as the application-generated String
 * key (length 64, never {@code @GeneratedValue}). Regular class (not a record)
 * for the same JPA reasons described on {@link Tenant}.
 */
@Entity
@Table(schema = "multistate", name = "jurisdiction")
public class Jurisdiction {

    @Id
    @Column(name = "code", length = 64)
    private String code;                                        // 2-letter USPS code, application-supplied PK

    @Column(name = "display_name", nullable = false, unique = true)
    private String displayName;

    @Column(name = "has_income_tax", nullable = false)
    private boolean hasIncomeTax;

    @Column(name = "is_reciprocal", nullable = false)
    private boolean reciprocal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Jurisdiction() {}                                 // required by JPA

    public Jurisdiction(String code,
                        String displayName,
                        boolean hasIncomeTax,
                        boolean reciprocal,
                        Instant createdAt) {
        this.code = Objects.requireNonNull(code, "code");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.hasIncomeTax = hasIncomeTax;
        this.reciprocal = reciprocal;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public String getCode()        { return code; }
    public String getDisplayName() { return displayName; }
    public boolean isHasIncomeTax(){ return hasIncomeTax; }
    public boolean isReciprocal()  { return reciprocal; }
    public Instant getCreatedAt()  { return createdAt; }

    // equals/hashCode on the primary key only.
    @Override public boolean equals(Object o) { return o instanceof Jurisdiction other && Objects.equals(code, other.code); }
    @Override public int hashCode()           { return Objects.hashCode(code); }
}
