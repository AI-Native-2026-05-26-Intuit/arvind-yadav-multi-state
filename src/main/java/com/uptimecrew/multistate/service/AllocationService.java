package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.entity.Tenant;
import com.uptimecrew.multistate.exception.AllocationException;
import com.uptimecrew.multistate.model.IncomeAllocation;
import com.uptimecrew.multistate.model.WorkDay;
import com.uptimecrew.multistate.readmodel.TenantReadModel;
import com.uptimecrew.multistate.readmodel.TenantReadModelRepository;
import com.uptimecrew.multistate.repository.TenantRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
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
    static final String CACHE_NAME = "multistate.byId";

    private final AllocationStrategy strategy;
    private final TenantRepository repository;                   // second constructor arg
    private final TenantReadModelRepository readModelRepository; // third arg — Mongo write-through target

    public AllocationService(AllocationStrategy strategy,
                             TenantRepository repository,
                             TenantReadModelRepository readModelRepository) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.readModelRepository = Objects.requireNonNull(readModelRepository, "readModelRepository");
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
        LOG.info("write-through to mongo id={} primaryState={} allocations={}",
            saved.getId(), saved.getPrimaryJurisdictionCode(), embedded.size());

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
}
