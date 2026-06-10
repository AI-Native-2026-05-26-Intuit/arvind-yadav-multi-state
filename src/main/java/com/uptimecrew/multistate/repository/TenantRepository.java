package com.uptimecrew.multistate.repository;

import com.uptimecrew.multistate.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Data JPA repository for {@link Tenant}. The PK type is {@code String}
 * (the W2 D1 TEXT id from Task 1).
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {

    /** Derived query — Spring Data parses the method name into JPQL. */
    List<Tenant> findByPrimaryJurisdictionCode(String primaryJurisdictionCode);

    /**
     * Explicit JPQL: tenants whose total allocated amount exceeds {@code minTotal}.
     * An aggregate over the {@code allocations} relationship with a HAVING clause —
     * not expressible via method-name derivation.
     */
    @Query("""
           select t from Tenant t
           join t.allocations a
           group by t
           having sum(a.amount) > :minTotal
           """)
    List<Tenant> findWithTotalAllocatedAmountAbove(@Param("minTotal") BigDecimal minTotal);
}
