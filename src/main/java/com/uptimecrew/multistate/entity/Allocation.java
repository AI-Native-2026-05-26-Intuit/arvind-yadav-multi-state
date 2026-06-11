package com.uptimecrew.multistate.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Maps the {@code multistate.allocation} table (W2 D1 DDL): the persistence
 * form of the Week 1 {@code IncomeAllocation} — one row per
 * (tenant, jurisdiction, tax_year, allocated_for).
 *
 * <p>The child side of the tenant/allocation relationship: it holds the
 * {@code @ManyToOne} back-reference to its {@link Tenant} and no
 * {@code @OneToMany} of its own. Regular class (not a record) for the same JPA
 * reasons described on {@link Tenant}.
 */
@Entity
@Table(schema = "multistate", name = "allocation")
public class Allocation {

    @Id
    @Column(length = 64)
    private String id;                                          // TEXT id, application-generated — never @GeneratedValue

    @ManyToOne(fetch = FetchType.LAZY)                          // LAZY to dodge N+1
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "jurisdiction_code", nullable = false)
    private String jurisdictionCode;                            // FK→jurisdiction(code); kept as a scalar reference value

    @Column(name = "tax_year", nullable = false)
    private int taxYear;

    @Column(name = "allocated_for", nullable = false)
    private LocalDate allocatedFor;                             // calendar date → LocalDate, never Date

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;                                  // money → BigDecimal, NUMERIC(12,2)

    @Column(name = "strategy_name", nullable = false)
    private String strategyName;                                // 'EQUAL_SPLIT' | 'WEIGHTED_DAY_COUNT' | 'PRIMARY_ONLY'

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected Allocation() {}                                   // required by JPA

    public Allocation(String id,
                      Tenant tenant,
                      String jurisdictionCode,
                      int taxYear,
                      LocalDate allocatedFor,
                      BigDecimal amount,
                      String strategyName,
                      Instant computedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        this.jurisdictionCode = Objects.requireNonNull(jurisdictionCode, "jurisdictionCode");
        this.taxYear = taxYear;
        this.allocatedFor = Objects.requireNonNull(allocatedFor, "allocatedFor");
        // Money is always scale 2, HALF_UP — enforce on construction.
        this.amount = Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.HALF_UP);
        this.strategyName = Objects.requireNonNull(strategyName, "strategyName");
        this.computedAt = Objects.requireNonNull(computedAt, "computedAt");
    }

    public String getId()               { return id; }
    public Tenant getTenant()           { return tenant; }
    public String getJurisdictionCode() { return jurisdictionCode; }
    public int getTaxYear()             { return taxYear; }
    public LocalDate getAllocatedFor()  { return allocatedFor; }
    public BigDecimal getAmount()       { return amount; }
    public String getStrategyName()     { return strategyName; }
    public Instant getComputedAt()      { return computedAt; }

    // equals/hashCode on the primary key only — never on the @ManyToOne back-reference.
    @Override public boolean equals(Object o) { return o instanceof Allocation other && Objects.equals(id, other.id); }
    @Override public int hashCode()           { return Objects.hashCode(id); }
}
