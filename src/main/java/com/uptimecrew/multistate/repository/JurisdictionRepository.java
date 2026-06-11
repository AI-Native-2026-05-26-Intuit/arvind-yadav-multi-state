package com.uptimecrew.multistate.repository;

import com.uptimecrew.multistate.entity.Jurisdiction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Data JPA repository for {@link Jurisdiction}. The PK type is
 * {@code String} (the 2-letter USPS {@code code} from Task 1).
 */
@Repository
public interface JurisdictionRepository extends JpaRepository<Jurisdiction, String> {

    /** Derived query — Spring Data parses the method name into JPQL. */
    List<Jurisdiction> findByHasIncomeTaxTrue();

    /**
     * Explicit JPQL: jurisdictions whose allocations sum to more than
     * {@code minTotal}. Allocation references a jurisdiction by code (a scalar,
     * not a mapped relationship), so this correlates via a HAVING-filtered
     * aggregate subquery — not expressible via method-name derivation.
     */
    @Query("""
           select j from Jurisdiction j
           where j.code in (
               select a.jurisdictionCode from Allocation a
               group by a.jurisdictionCode
               having sum(a.amount) > :minTotal
           )
           """)
    List<Jurisdiction> findReferencedWithTotalAllocatedAbove(@Param("minTotal") BigDecimal minTotal);
}
