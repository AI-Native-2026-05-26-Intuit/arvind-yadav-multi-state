package com.uptimecrew.multistate.graphql;

import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.service.AllocationService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * GraphQL entrypoint for the tenant read model. Resolves the {@code tenant} and
 * {@code latestTenants} queries declared in {@code resources/graphql/schema.graphqls}
 * by delegating to {@link AllocationService}.
 *
 * <p>Field resolvers for {@code Tenant.lines} (the N+1 fix via {@code @BatchMapping})
 * and the {@code summarizeTenant} mutation are wired in W3 D4 Task 2 / Task 3.
 */
@Controller
public class TenantGraphQlController {

    private static final Logger LOG = LoggerFactory.getLogger(TenantGraphQlController.class);

    private final AllocationService service;

    public TenantGraphQlController(AllocationService service) {
        this.service = service;
    }

    @QueryMapping
    public TenantReadModel tenant(@Argument String id) {
        LOG.info("graphql query tenant id={}", id);
        return service.findById(id).orElse(null);
    }

    @QueryMapping
    public List<TenantReadModel> latestTenants(@Argument Integer limit) {
        return service.findLatest(limit == null ? 10 : limit);
    }

    /**
     * Batch resolver for {@code Tenant.lines}. Without this, a query like
     * {@code { latestTenants(limit: 50) { lines { id } } }} would invoke a
     * per-parent resolver 50 times. {@code @BatchMapping} flips that: Spring
     * for GraphQL hands us the FULL list of parent documents once and we
     * return a {@code Map<Parent, List<Child>>} in a single call.
     *
     * <p>Chosen over a manual {@code DataLoader} registration because the
     * parent is already a hydrated document (no key-to-row lookup is needed)
     * — {@code @BatchMapping} keeps the wiring declarative and the test
     * surface small while still solving the same problem.
     */
    @BatchMapping(typeName = "Tenant", field = "lines")
    public Map<TenantReadModel, List<LineItem>> lines(List<TenantReadModel> parents) {
        return service.loadLineItemsByParent(parents);
    }
}