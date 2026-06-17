package com.uptimecrew.multistate.graphql;

import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.service.AllocationService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.Argument;
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
}