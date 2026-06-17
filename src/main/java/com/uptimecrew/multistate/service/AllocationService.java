package com.uptimecrew.multistate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uptimecrew.multistate.entity.Tenant;
import com.uptimecrew.multistate.exception.AllocationException;
import com.uptimecrew.multistate.graphql.LineItem;
import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;
import com.uptimecrew.multistate.outbox.EventOutboxEntity;
import com.uptimecrew.multistate.outbox.EventOutboxRepository;
import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import com.uptimecrew.multistate.repository.TenantRepository;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain service that runs an injected {@link AllocationStrategy} against a worker's
 * inputs and then persists the worker as a {@link Tenant}. Both collaborators are
 * provided at construction (never created here), letting callers swap allocation
 * behaviour or either persistence backend without changing this service.
 *
 * <p>Validates inputs at the service boundary, delegates the split to the strategy,
 * derives the worker's primary jurisdiction from the result, saves the resulting
 * {@link Tenant} via JPA, and write-throughs a {@link TenantReadModel} projection to
 * Mongo — all inside a single transaction.
 *
 * <p>Spring owns this bean's lifecycle and injects all three collaborators through the
 * single constructor (no field/annotation injection): the {@code @Primary}
 * {@link AllocationStrategy}, the {@link TenantRepository}, and the
 * {@link TenantReadModelRepository}.
 */
@Service
// Not final: @Transactional means Spring wraps this bean in a CGLIB proxy, which
// must subclass it. This is the justified exception to "classes default to final".
public class AllocationService {

    private static final Logger LOG = LoggerFactory.getLogger(AllocationService.class);

    /** New tenants start ACTIVE — one of the W2 D1 status CHECK values. */
    private static final String DEFAULT_STATUS = "ACTIVE";

    /** Cache region for {@link #findById(String)} — Redis-backed in the running app. */
    public static final String CACHE_NAME = "multistate.byId";

    /** Kafka topic for tenant lifecycle events emitted via the outbox. */
    public static final String TENANT_EVENTS_TOPIC = "tenants.events";

    private final AllocationStrategy strategy;
    private final TenantRepository repository;                   // second constructor arg
    private final TenantReadModelRepository readModelRepository; // third arg — Mongo write-through target
    private final EventOutboxRepository outboxRepository;        // W3 D3: transactional-outbox target
    // ObjectMapper is thread-safe once configured — owned here rather than injected to
    // avoid coupling this service to Spring Boot's Jackson autoconfig wiring.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AllocationService(AllocationStrategy strategy,
                             TenantRepository repository,
                             TenantReadModelRepository readModelRepository,
                             EventOutboxRepository outboxRepository) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.readModelRepository = Objects.requireNonNull(readModelRepository, "readModelRepository");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
    }

    /**
     * Runs the strategy and persists the worker as a {@link Tenant}, returning the saved
     * entity. The strategy call and the {@code repository.save(...)} run in one transaction.
     *
     * @param legalName the tenant's legal name (required by the {@code tenant} table)
     * @return the persisted {@link Tenant}
     */
    @Transactional                                      // one tx wraps strategy + save
    public Tenant allocate(
        String workerId,
        String legalName,
        BigDecimal totalIncome,
        List<WorkDay> workDays,
        LocalDate allocatedFor
    ) {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(legalName, "legalName");
        Objects.requireNonNull(totalIncome, "totalIncome");
        Objects.requireNonNull(workDays, "workDays");
        Objects.requireNonNull(allocatedFor, "allocatedFor");
        if (totalIncome.signum() < 0) {
            throw new IllegalArgumentException("totalIncome must not be negative: " + totalIncome);
        }

        LOG.info("invoking strategy={} for workerId={} totalIncome={} workDays={} allocatedFor={}",
            strategy.getClass().getSimpleName(), workerId, totalIncome, workDays.size(), allocatedFor);

        final List<IncomeAllocation> allocations;
        try {
            allocations = List.copyOf(strategy.allocate(workerId, totalIncome, workDays, allocatedFor));
        } catch (AllocationException ex) {
            LOG.warn("strategy failed: {}", ex.getMessage(), ex);
            throw ex;
        }

        // The worker's primary jurisdiction is the one that received the largest share
        // of income in this run — derived directly from the strategy result.
        String primaryJurisdictionCode = allocations.stream()
            .max(Comparator.comparing(IncomeAllocation::amount))
            .map(IncomeAllocation::jurisdictionCode)
            .orElseThrow(() -> new IllegalArgumentException(
                "cannot determine primary jurisdiction: strategy produced no allocations"));

        Instant now = Instant.now();
        Tenant entity = new Tenant(
            workerId,                   // TEXT id == the worker/tenant id
            legalName,
            primaryJurisdictionCode,
            DEFAULT_STATUS,
            allocatedFor,               // incorporated_on
            now,                        // created_at
            now                         // updated_at (== created_at satisfies the DDL CHECK)
        );
        Tenant saved = repository.save(entity);
        LOG.info("persisted tenant id={} primaryJurisdictionCode={} from allocations={}",
            saved.getId(), saved.getPrimaryJurisdictionCode(), allocations.size());

        // Write-through: project the saved JPA aggregate into the Mongo read model so the
        // query side returns the whole tenant+allocations tree in a single round-trip,
        // inside the same @Transactional boundary as the JPA save above.
        List<TenantReadModel.EmbeddedAllocation> embedded = allocations.stream()
            .map(a -> new TenantReadModel.EmbeddedAllocation(
                a.id(),
                a.jurisdictionCode(),
                a.allocatedFor().getYear(),         // tax year derived from the allocation period
                a.allocatedFor(),
                a.amount(),
                strategy.getClass().getSimpleName(),
                now))
            .toList();
        TenantReadModel projection = new TenantReadModel(
            saved.getId(),
            saved.getPrimaryJurisdictionCode(),     // primaryState
            saved.getLegalName(),
            saved.getStatus(),
            now,                                    // capturedAt — same "now" as the JPA timestamps
            embedded);
        readModelRepository.save(projection);
        LOG.info("write-through to mongo id={} status={} primaryState={} allocations={}",
            saved.getId(), saved.getStatus(), saved.getPrimaryJurisdictionCode(), embedded.size());

        // Transactional outbox: the event row is inserted in the SAME @Transactional
        // boundary as the domain writes above, so it's committed atomically with them.
        // OutboxPublisher polls the table on its own schedule and forwards rows to
        // Kafka; we don't talk to Kafka here. The aggregate id (tenant id) is used as
        // the Kafka message key so per-aggregate ordering is preserved on the topic.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "TenantAllocated");
        payload.put("tenantId", saved.getId());
        payload.put("primaryJurisdictionCode", saved.getPrimaryJurisdictionCode());
        payload.put("status", saved.getStatus());
        payload.put("strategy", strategy.getClass().getSimpleName());
        payload.put("allocatedFor", allocatedFor.toString());
        payload.put("allocationCount", allocations.size());
        payload.put("totalIncome", totalIncome.toPlainString());
        payload.put("occurredAt", now.toString());
        final String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // Jackson failing on a Map<String,Object> built from primitives means a
            // genuine programmer error, not an event we can recover from — fail loudly
            // so the surrounding transaction rolls back (the tenant write goes with it).
            throw new IllegalStateException("failed to serialize outbox payload for tenant " + saved.getId(), ex);
        }
        outboxRepository.save(new EventOutboxEntity(saved.getId(), TENANT_EVENTS_TOPIC, payloadJson));
        LOG.info("outbox enqueued tenantId={} topic={}", saved.getId(), TENANT_EVENTS_TOPIC);

        return saved;
    }

    /**
     * Reads a tenant projection by id along the cache-aside path: Redis (via
     * {@link Cacheable}) → Mongo read model → Postgres fallback.
     *
     * <p>{@code unless = "#result == null"} keeps a miss out of the cache: for an
     * {@code Optional} return type Spring caches the <em>unwrapped</em> value, so an
     * empty result evaluates {@code #result} to {@code null} and is not stored — a
     * transient "not found" never gets locked in.
     *
     * <p>The INFO log fires only on a real method invocation (a cache miss); once the
     * value is cached, {@code @Cacheable} short-circuits before the body runs and the
     * read stays silent.
     */
    @Cacheable(value = CACHE_NAME, unless = "#result == null")
    public Optional<TenantReadModel> findById(String id) {
        Objects.requireNonNull(id, "id");
        LOG.info("cache miss on id={}; reading from mongo", id);

        Optional<TenantReadModel> fromMongo = readModelRepository.findById(id);
        if (fromMongo.isPresent()) {
            return fromMongo;
        }

        // Fallback: rebuild a projection from the JPA entity so a Mongo wipe doesn't break
        // the read path. The JPA allocations are LAZY, so this minimal projection carries
        // the tenant scalars only — not the embedded allocation tree.
        return repository.findById(id).map(e -> new TenantReadModel(
            e.getId(),
            e.getPrimaryJurisdictionCode(),     // primaryState
            e.getLegalName(),
            e.getStatus(),
            Instant.now(),                      // capturedAt — rebuilt now
            List.of()));
    }

    /**
     * Returns the most-recently-projected tenant read-model documents, newest
     * first, capped at {@code limit}. Backs the GraphQL {@code latestTenants}
     * query. Reads straight from the Mongo read model — no cache-aside here,
     * since the query is exploratory and ordering changes with every write.
     */
    public List<TenantReadModel> findLatest(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        return readModelRepository.findAllByOrderByCapturedAtDesc(PageRequest.of(0, limit));
    }

    /**
     * Batch resolver for the GraphQL {@code Tenant.lines} field — pairs every
     * parent with its already-embedded allocations in a single pass, so Spring
     * for GraphQL invokes us once per generation regardless of parent count.
     *
     * <p>Because {@link TenantReadModel} embeds its children inline in the same
     * Mongo document, the parent fetch has already brought the allocations
     * along — we issue ZERO extra reads here. The N+1 we're avoiding is in
     * application code, not in the database: without {@code @BatchMapping},
     * Spring for GraphQL would invoke a per-parent resolver once per item in
     * the list, and any per-call work (logging, mapping setup, future eager
     * lookups) would multiply. With this method, the mapping runs exactly
     * once per query.
     */
    public Map<TenantReadModel, List<LineItem>> loadLineItemsByParent(List<TenantReadModel> parents) {
        Objects.requireNonNull(parents, "parents");
        Map<TenantReadModel, List<LineItem>> out = new LinkedHashMap<>(parents.size());
        for (TenantReadModel parent : parents) {
            List<LineItem> lines = parent.getAllocations().stream()
                .map(LineItem::from)
                .toList();
            out.put(parent, lines);
        }
        return out;
    }
}
