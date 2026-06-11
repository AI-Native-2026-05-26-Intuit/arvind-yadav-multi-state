package com.uptimecrew.multistate.repository;

import com.uptimecrew.multistate.entity.Allocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Data JPA repository for {@link Allocation}. The PK type is
 * {@code String} (the W2 D1 TEXT id from Task 1).
 */
@Repository
public interface AllocationRepository extends JpaRepository<Allocation, String> {

    /** Derived query — Spring Data parses the method name into JPQL. */
    List<Allocation> findByJurisdictionCode(String jurisdictionCode);

    /**
     * Explicit JPQL: ids of tenants whose allocations for a given tax year sum
     * to more than {@code minTotal}. An aggregate grouped over the {@code tenant}
     * relationship with a HAVING clause — not expressible via method-name derivation.
     */
    @Query("""
           select a.tenant.id from Allocation a
           where a.taxYear = :taxYear
           group by a.tenant.id
           having sum(a.amount) > :minTotal
           """)
    List<String> findTenantIdsWithYearTotalAbove(@Param("taxYear") int taxYear,
                                                 @Param("minTotal") BigDecimal minTotal);
}
