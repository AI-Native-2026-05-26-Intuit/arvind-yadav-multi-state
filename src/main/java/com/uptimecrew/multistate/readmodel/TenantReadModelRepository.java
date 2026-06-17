package com.uptimecrew.multistate.readmodel;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Mongo repository for {@link TenantReadModel}. The id type is
 * {@code String} — the SAME application-generated TEXT id as the JPA
 * {@code Tenant}, so a Mongo lookup and a Postgres lookup resolve to the same
 * logical row.
 */
@Repository
public interface TenantReadModelRepository extends MongoRepository<TenantReadModel, String> {

    /**
     * Derived query — Spring Data parses the method name into a Mongo find.
     * Backed by the {@code @Indexed} {@code primaryState} field, so the
     * secondary lookup by state stays index-driven rather than a collection scan.
     */
    List<TenantReadModel> findByPrimaryState(String primaryState);

    /**
     * Most-recently-projected tenants first. Used by the GraphQL
     * {@code latestTenants} query. Sort is on {@code capturedAt}; the field is
     * unindexed today, which is acceptable because the GraphQL query caps the
     * result via {@code limit} and the collection is small in this curriculum.
     */
    List<TenantReadModel> findAllByOrderByCapturedAtDesc(org.springframework.data.domain.Pageable pageable);
}
