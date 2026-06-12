package com.uptimecrew.multistate.readmodel;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Mongo read model for a tenant and its allocations — the query-side mirror of
 * the W2 D4 JPA {@code Tenant}/{@code Allocation} aggregate.
 *
 * <p>Where the JPA side LAZY-loads {@code Tenant.allocations} via a separate
 * JOIN (and risks N+1), this document <strong>embeds</strong> the same children
 * inline as {@link EmbeddedAllocation}: ONE Mongo round-trip returns the whole
 * tree. The {@code id} is deliberately the SAME application-generated TEXT id as
 * the JPA entity, so a Mongo lookup and a Postgres lookup resolve to the same
 * logical row.
 *
 * <p>Implements {@link Serializable} (as does every nested class) so a
 * {@code RedisCacheManager} can serialise it — without it, the first
 * {@code @Cacheable} miss blows up with a {@code SerializationException} at
 * runtime. Unlike the JPA entity this is a regular (non-{@code final}) class
 * with mutable fields and a public no-arg constructor, because Spring Data Mongo
 * populates documents by reflection just as Hibernate does — the justified
 * exception to the project's "final fields / final classes" default.
 */
@Document(collection = "tenants")
public class TenantReadModel implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private String id;                                          // SAME id as the JPA Tenant — never int/long

    @Indexed                                                    // secondary index → findByPrimaryState is index-backed
    private String primaryState;

    private String legalName;

    private String status;

    private Instant capturedAt;                                 // when this projection was materialised

    private List<EmbeddedAllocation> allocations = new ArrayList<>();   // embedded, NOT a foreign reference

    public TenantReadModel() {}                                 // required by Spring Data Mongo

    public TenantReadModel(String id,
                           String primaryState,
                           String legalName,
                           String status,
                           Instant capturedAt,
                           List<EmbeddedAllocation> allocations) {
        this.id = Objects.requireNonNull(id, "id");
        this.primaryState = Objects.requireNonNull(primaryState, "primaryState");
        this.legalName = Objects.requireNonNull(legalName, "legalName");
        this.status = Objects.requireNonNull(status, "status");
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.allocations = new ArrayList<>(Objects.requireNonNull(allocations, "allocations"));
    }

    public String getId()                          { return id; }
    public String getPrimaryState()                { return primaryState; }
    public String getLegalName()                   { return legalName; }
    public String getStatus()                      { return status; }
    public Instant getCapturedAt()                 { return capturedAt; }
    public List<EmbeddedAllocation> getAllocations() { return allocations; }

    // Identity on the shared primary key only — never on the embedded collection.
    @Override public boolean equals(Object o) { return o instanceof TenantReadModel other && Objects.equals(id, other.id); }
    @Override public int hashCode()           { return Objects.hashCode(id); }

    /**
     * Denormalised copy of the JPA {@code Allocation} child — the data the
     * {@code @OneToMany} would lazy-load, captured inline. Also
     * {@link Serializable} so the enclosing document caches cleanly.
     */
    public static final class EmbeddedAllocation implements Serializable {

        private static final long serialVersionUID = 1L;

        private String id;                                      // SAME id as the JPA Allocation
        private String jurisdictionCode;
        private int taxYear;
        private LocalDate allocatedFor;                         // calendar date → LocalDate, never Date
        private BigDecimal amount;                              // money → BigDecimal, scale 2, HALF_UP
        private String strategyName;
        private Instant computedAt;

        public EmbeddedAllocation() {}                          // required by Spring Data Mongo

        public EmbeddedAllocation(String id,
                                  String jurisdictionCode,
                                  int taxYear,
                                  LocalDate allocatedFor,
                                  BigDecimal amount,
                                  String strategyName,
                                  Instant computedAt) {
            this.id = Objects.requireNonNull(id, "id");
            this.jurisdictionCode = Objects.requireNonNull(jurisdictionCode, "jurisdictionCode");
            this.taxYear = taxYear;
            this.allocatedFor = Objects.requireNonNull(allocatedFor, "allocatedFor");
            // Money is always scale 2, HALF_UP — enforce on construction.
            this.amount = Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.HALF_UP);
            this.strategyName = Objects.requireNonNull(strategyName, "strategyName");
            this.computedAt = Objects.requireNonNull(computedAt, "computedAt");
        }

        public String getId()               { return id; }
        public String getJurisdictionCode() { return jurisdictionCode; }
        public int getTaxYear()             { return taxYear; }
        public LocalDate getAllocatedFor()  { return allocatedFor; }
        public BigDecimal getAmount()       { return amount; }
        public String getStrategyName()     { return strategyName; }
        public Instant getComputedAt()      { return computedAt; }

        @Override public boolean equals(Object o) { return o instanceof EmbeddedAllocation other && Objects.equals(id, other.id); }
        @Override public int hashCode()           { return Objects.hashCode(id); }
    }
}
