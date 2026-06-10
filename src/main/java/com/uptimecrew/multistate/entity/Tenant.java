package com.uptimecrew.multistate.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps the {@code multistate.tenant} table (W2 D1 DDL): one row per taxpayer
 * entity (the platform's customer) and the parent of its allocations.
 *
 * <p>JPA needs a no-arg constructor (protected is fine) AND mutable state, so
 * this is a regular class, not a {@code record}. Fields are non-final because
 * Hibernate populates them via reflection. Entities are also intentionally
 * non-final (Hibernate proxies subclass them), which is the justified
 * exception to the project's "classes default to final" rule.
 */
@Entity
@Table(schema = "multistate", name = "tenant")
public class Tenant {

    @Id
    @Column(length = 64)
    private String id;                                          // TEXT id, application-generated — never @GeneratedValue

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "primary_jurisdiction_code", nullable = false)
    private String primaryJurisdictionCode;                     // FK→jurisdiction(code); kept as a scalar reference value

    @Column(name = "status", nullable = false)
    private String status;                                      // 'ACTIVE' | 'SUSPENDED' | 'CLOSED' (CHECK in DDL)

    @Column(name = "incorporated_on", nullable = false)
    private LocalDate incorporatedOn;                           // calendar date → LocalDate, never Date

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "tenant",
               fetch = FetchType.LAZY,                          // LAZY to dodge N+1
               cascade = CascadeType.ALL,
               orphanRemoval = true)
    private List<Allocation> allocations = new ArrayList<>();

    protected Tenant() {}                                       // required by JPA

    public Tenant(String id,
                  String legalName,
                  String primaryJurisdictionCode,
                  String status,
                  LocalDate incorporatedOn,
                  Instant createdAt,
                  Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.legalName = Objects.requireNonNull(legalName, "legalName");
        this.primaryJurisdictionCode = Objects.requireNonNull(primaryJurisdictionCode, "primaryJurisdictionCode");
        this.status = Objects.requireNonNull(status, "status");
        this.incorporatedOn = Objects.requireNonNull(incorporatedOn, "incorporatedOn");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public String getId()                       { return id; }
    public String getLegalName()                { return legalName; }
    public String getPrimaryJurisdictionCode()  { return primaryJurisdictionCode; }
    public String getStatus()                   { return status; }
    public LocalDate getIncorporatedOn()        { return incorporatedOn; }
    public Instant getCreatedAt()               { return createdAt; }
    public Instant getUpdatedAt()               { return updatedAt; }
    public List<Allocation> getAllocations()    { return allocations; }

    // equals/hashCode on the primary key only — never on the lazy collection.
    @Override public boolean equals(Object o) { return o instanceof Tenant other && Objects.equals(id, other.id); }
    @Override public int hashCode()           { return Objects.hashCode(id); }
}
