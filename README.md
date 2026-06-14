# UptimeCrew Multi-State

A Java domain library for splitting a worker's income across the tax jurisdictions where the work was actually performed. The motivating problem: a worker who lives in one state but performs services across several owes income tax to each jurisdiction in proportion to the work done there. This project models the inputs (where work happened) and the outputs (how income is divided), and houses the strategies that compute the split.

The domain core is now packaged as a **Spring Boot 3.3** application: the service and its allocation strategies are Spring-managed beans, and the build produces a runnable boot jar with Actuator health/info endpoints. The pure-Java domain types and standards (see [CLAUDE.md](CLAUDE.md)) are unchanged — Spring sits around them, not inside them.

**Week 2 Day 4:** mapped the three W2 D1 tables to JPA entities (`Tenant`, `Allocation`, `Jurisdiction`) with a LAZY `Tenant`↔`Allocation` relationship, added three Spring Data `JpaRepository` interfaces (derived + `@Query` JPQL methods), wired `TenantRepository` into `AllocationService` under `@Transactional`, and added a Testcontainers-backed `@DataJpaTest` slice ([TenantRepositoryIT](src/test/java/com/uptimecrew/multistate/repository/TenantRepositoryIT.java)).

**Week 2 Day 5:** added a polyglot read side — a Mongo `@Document` read model ([TenantReadModel](src/main/java/com/uptimecrew/multistate/readmodel/TenantReadModel.java)) with embedded allocations and its `MongoRepository`, write-through from `AllocationService` to Mongo inside the existing JPA transaction, and a Redis-backed `@Cacheable` `findById` read path (`@EnableCaching`), all verified end-to-end against Postgres + Mongo + Redis containers in [TenantPolyglotIT](src/test/java/com/uptimecrew/multistate/TenantPolyglotIT.java).

**Week 3 Day 1:** put the read side behind a secured HTTP API. Added an OAuth2 resource-server [SecurityConfig](src/main/java/com/uptimecrew/multistate/security/SecurityConfig.java) (stateless, JWT-validated, default-deny under `/api/**`, `/actuator/health` public) with a `JwtAuthenticationConverter` that maps the `scope` claim to `SCOPE_*` authorities and a custom `roles` claim to `ROLE_*` authorities, enabling `@PreAuthorize` SpEL on the controller. [TenantController](src/main/java/com/uptimecrew/multistate/api/TenantController.java) exposes `GET /api/tenants/{id}` and `GET /api/tenants/{id}/summary`, both gated on `SCOPE_tenants.read` **and** `ROLE_TENANT_READER`. A per-subject Bucket4j [RateLimitFilter](src/main/java/com/uptimecrew/multistate/security/RateLimitFilter.java) (10 requests / minute, in-memory `ConcurrentHashMap` keyed by JWT subject, scoped to `/api/**/summary`) is registered *after* `BearerTokenAuthenticationFilter` so the JWT principal is resolved before the bucket lookup; over-limit responses return `429` with `Retry-After: 60`. End-to-end coverage in [TenantSecurityIT](src/test/java/com/uptimecrew/multistate/TenantSecurityIT.java) asserts 200/401/403 on the read endpoint and 429-after-10 on the summary endpoint, against real Postgres + Mongo + Redis containers.

## Build & test

```bash
./gradlew build       # compile + test
./gradlew test        # run JUnit 5 tests
./gradlew check       # tests + JaCoCo branch-coverage gate (≥ 0.70)
./gradlew bootRun     # boot the app (defaults to the `local` profile)
./gradlew clean       # wipe build outputs
```

Requires JDK 17+.

## Coverage

JaCoCo is wired into the build. After any test run, the HTML report lives at `build/reports/jacoco/test/html/index.html` and the XML report at `build/reports/jacoco/test/jacocoTestReport.xml`. `./gradlew check` is gated by `jacocoTestCoverageVerification` — the build fails if **branch** coverage drops below **0.70**. Current snapshot: project total 86% branch / 96% instruction; `com.uptimecrew.multistate.service` 89% branch / 97% instruction.

## Spring application

The project is bootstrapped as a Spring Boot 3.3 application (Boot + dependency-management Gradle plugins).

- [Application](src/main/java/com/uptimecrew/multistate/Application.java) — the `@SpringBootApplication` (also `@EnableCaching`) entry point. Component-scans everything under `com.uptimecrew.multistate.*`. The Hikari `DataSource` is auto-configured from `spring.datasource.*` (via `spring-boot-starter-jdbc` + the PostgreSQL driver), which lets Spring Data JPA stand up the `EntityManagerFactory` and repositories the service depends on.
- **Beans.** [AllocationService](src/main/java/com/uptimecrew/multistate/service/AllocationService.java) is a `@Service`; the strategies are `@Component`s. [DayCountAllocationStrategy](src/main/java/com/uptimecrew/multistate/service/DayCountAllocationStrategy.java) is marked `@Primary`, so it is the `AllocationStrategy` Spring injects into the service unless a `@Qualifier` narrows the choice. [WeightedDayCountAllocationStrategy](src/main/java/com/uptimecrew/multistate/service/WeightedDayCountAllocationStrategy.java) is registered as a secondary bean (no `@Primary`), instantiated via its no-arg constructor with the default per-jurisdiction weight. Constructor injection on the single-constructor service needs no `@Autowired` (Spring 6).
- **Factory still matters.** [AllocationStrategies](src/main/java/com/uptimecrew/multistate/service/AllocationStrategies.java) is retained after the migration. Spring is the factory for the default injected strategy, but the façade still serves callers *outside* the Spring context — unit tests that don't boot Spring, and the parameterized variants (`byIncomeProportion(...)` needs a revenue map, `byHybridBlend(...)` needs two delegates and a weight) that component scanning cannot construct on its own.

### Configuration & profiles

[application.yml](src/main/resources/application.yml) defines two profiles via a YAML multi-document split:

| Profile | Use | Notable settings |
| --- | --- | --- |
| `local` (default) | local dev | Postgres `localhost:5432`, HikariCP pool size 5, password from `${DB_PASSWORD:devpass}`, `com.uptimecrew.multistate` log level `DEBUG`. |
| `test` | integration tests | Placeholder datasource (Testcontainers injects the real URL at runtime); log level `INFO`. Activate with `@ActiveProfiles("test")`. |

### Actuator

`spring-boot-starter-actuator` exposes management endpoints over HTTP (embedded Tomcat via `spring-boot-starter-web`). The `local` profile exposes `health` and `info`; the `test` profile exposes only `health`. Health detail is shown `when-authorized`.

## HTTP API

All `/api/**` routes require a valid Bearer JWT and authority `SCOPE_tenants.read` + role `TENANT_READER`. `/actuator/health` is unauthenticated. The session is stateless; CSRF is disabled because there is no cookie-based auth surface.

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `GET` | `/api/tenants/{id}` | JWT + `SCOPE_tenants.read` + `ROLE_TENANT_READER` | Returns the [TenantReadModel](src/main/java/com/uptimecrew/multistate/readmodel/TenantReadModel.java) from the Redis-cached Mongo read side. 404 if not found. |
| `GET` | `/api/tenants/{id}/summary` | Same as above | Stubbed LLM-style summary. Rate-limited to **10 requests / minute per JWT subject**; over-limit returns `429` with `Retry-After: 60`. |
| `GET` | `/actuator/health` | none | Liveness probe. |

Anonymous → `401`. Authenticated but missing scope/role → `403`. The rate-limit filter is in-memory per process; in a horizontally scaled deployment swap in `bucket4j-redis` so the bucket is shared.

## Domain model

Located under [src/main/java/com/uptimecrew/multistate/model/](src/main/java/com/uptimecrew/multistate/model/).

| Type | Role |
| --- | --- |
| [Jurisdiction](src/main/java/com/uptimecrew/multistate/model/Jurisdiction.java) | A taxing authority — identified by `code` (e.g. `"US-CA"`), with a human display name and a [JurisdictionKind](src/main/java/com/uptimecrew/multistate/model/JurisdictionKind.java) (`FEDERAL`, `STATE`, `COUNTY`, `CITY`). |
| [WorkDay](src/main/java/com/uptimecrew/multistate/model/WorkDay.java) | One day of work attributed to one jurisdiction — the raw input to allocation. |
| [IncomeAllocation](src/main/java/com/uptimecrew/multistate/model/IncomeAllocation.java) | The result: a portion of a worker's income assigned to a jurisdiction for a given pay period. Money is `BigDecimal`, scale 2, HALF_UP. |
| [IncomeAllocationDraft](src/main/java/com/uptimecrew/multistate/model/IncomeAllocationDraft.java) | A pre-persistence shape of an allocation — same fields minus `workerId`, used while a batch is being assembled. |
| [IncomeAllocationTestDataBuilder](src/main/java/com/uptimecrew/multistate/model/IncomeAllocationTestDataBuilder.java) | Fluent builder for `IncomeAllocation`. Lives in production so tests in any module can call `IncomeAllocationTestDataBuilder.aIncomeAllocation().withAmount(...).build()`. Defaults satisfy the record's compact-constructor validation; override only what the test cares about. |

## Allocation strategies

Located under [src/main/java/com/uptimecrew/multistate/service/](src/main/java/com/uptimecrew/multistate/service/).

- [AllocationStrategy](src/main/java/com/uptimecrew/multistate/service/AllocationStrategy.java) — the interface every allocator implements: given a worker, a total income, the work days, and the period being allocated, return one `IncomeAllocation` per jurisdiction.
- [DayCountAllocationStrategy](src/main/java/com/uptimecrew/multistate/service/DayCountAllocationStrategy.java) — splits income proportionally to the count of work days in each jurisdiction, rounding each share HALF_UP. Throws [JurisdictionUnsupportedException](src/main/java/com/uptimecrew/multistate/exception/JurisdictionUnsupportedException.java) on negative `totalIncome`. Known limitation: rounded shares may not re-sum to the total to the penny.
- [IncomeProportionalAllocationStrategy](src/main/java/com/uptimecrew/multistate/service/IncomeProportionalAllocationStrategy.java) — splits income across the participating jurisdictions in proportion to a configured per-jurisdiction revenue map. When a work day references a jurisdiction with no configured revenue, the lookup failure is wrapped (with the underlying cause preserved) and rethrown as [IncomeAllocationFailedException](src/main/java/com/uptimecrew/multistate/exception/IncomeAllocationFailedException.java).
- [HybridAllocationStrategy](src/main/java/com/uptimecrew/multistate/service/HybridAllocationStrategy.java) — blends two delegate strategies by a fixed `primaryWeight` in `[0, 1]`, summing per-jurisdiction contributions and rounding HALF_UP to scale 2.
- [WeightedDayCountAllocationStrategy](src/main/java/com/uptimecrew/multistate/service/WeightedDayCountAllocationStrategy.java) — splits income by **business-day** count (Mon–Fri; weekends filtered) weighted by a per-jurisdiction factor (default `1.00`). Shares are HALF_UP to scale 2. Negative `totalIncome` raises `JurisdictionUnsupportedException`. Built test-first; see [WeightedDayCountAllocationStrategyTest](src/test/java/com/uptimecrew/multistate/service/WeightedDayCountAllocationStrategyTest.java).
- [AllocationStrategies](src/main/java/com/uptimecrew/multistate/service/AllocationStrategies.java) — factory façade exposing `byDayCount()`, `byIncomeProportion(...)`, and `byHybridBlend(...)`; non-instantiable.
- [AllocationRegistry](src/main/java/com/uptimecrew/multistate/service/AllocationRegistry.java) — name → strategy lookup so callers can resolve a strategy by key.
- [AllocationService](src/main/java/com/uptimecrew/multistate/service/AllocationService.java) — domain service that runs an injected strategy. Validates inputs at the boundary, logs INFO on entry and on successful return via SLF4J's `{}`-parameterised form, and wraps the delegation in a `catch (AllocationException ex)` that logs WARN with the exception attached (so the stack trace renders) before rethrowing. No broader `RuntimeException` / `Exception` / `Throwable` catches.

## Exception hierarchy

Located under [src/main/java/com/uptimecrew/multistate/exception/](src/main/java/com/uptimecrew/multistate/exception/).

- [AllocationException](src/main/java/com/uptimecrew/multistate/exception/AllocationException.java) — abstract `RuntimeException`, the root of the multistate domain hierarchy. Lets callers write a single `catch (AllocationException ex)` while preserving concrete subtypes for selective handling. Unchecked because the failure modes are either programmer errors at the call site or transient upstream failures that compose poorly with checked exceptions in lambdas/streams.
- [JurisdictionUnsupportedException](src/main/java/com/uptimecrew/multistate/exception/JurisdictionUnsupportedException.java) — final; thrown on invalid input the strategy cannot allocate against (e.g. negative `totalIncome`).
- [IncomeAllocationFailedException](src/main/java/com/uptimecrew/multistate/exception/IncomeAllocationFailedException.java) — final; thrown when an underlying operation fails. Built via the `(message, cause)` constructor so the original cause (e.g. an `IOException`) is preserved in the stack trace.

## Logging

`AllocationService` uses SLF4J 2.x with a Logback runtime. Messages use the `{}`-parameterised form — never string concatenation. Tests attach a `ListAppender` to the `AllocationService` logger to assert that exactly one WARN event with the exception's message is emitted on a failed delegation; see [AllocationServiceExceptionPathTest](src/test/java/com/uptimecrew/multistate/service/AllocationServiceExceptionPathTest.java).

## Tests

JUnit 5 throughout. Mockito (with `MockitoExtension`) is used where stubbing a strategy is the clearest way to express a scenario; real objects and hand-rolled fakes are preferred for pure domain logic. AssertJ provides the fluent exception assertions used in the exception-path tests (`assertThatThrownBy(...).hasRootCauseInstanceOf(...)`).

Test conventions for new code (Day 5 onward):

- **AAA markers** — every test body carries explicit `// Arrange` / `// Act` / `// Assert` comment markers.
- **Naming** — `methodUnderTest_condition_expectation`, mirrored in a `@DisplayName`.
- **AssertJ only** in new test files — `assertThat` / `assertThatThrownBy`; no `org.junit.jupiter.api.Assertions` static imports.
- **Test data builders** — prefer `IncomeAllocationTestDataBuilder.aIncomeAllocation().withX(...).build()` over calling the record constructor directly, so tests describe only the fields they care about.

Notable test classes:

- [WeightedDayCountAllocationStrategyTest](src/test/java/com/uptimecrew/multistate/service/WeightedDayCountAllocationStrategyTest.java) — TDD-built suite for the Day 5 strategy. Four AAA-shaped tests covering happy path, negative-input rejection, empty-input edge case, and weighted business-day math.
- [AllocationServiceTest](src/test/java/com/uptimecrew/multistate/service/AllocationServiceTest.java) — boundary validation and happy-path delegation.
- [AllocationServiceMockitoTest](src/test/java/com/uptimecrew/multistate/service/AllocationServiceMockitoTest.java) — verifies the service delegates to the injected strategy; uses `IncomeAllocationTestDataBuilder`.
- [AllocationRegistryTest](src/test/java/com/uptimecrew/multistate/service/AllocationRegistryTest.java) — registry semantics; uses `IncomeAllocationTestDataBuilder`.
- [AllocationServiceExceptionPathTest](src/test/java/com/uptimecrew/multistate/service/AllocationServiceExceptionPathTest.java) — typed exception propagation, root-cause preservation, and WARN-log emission.
- [HybridAllocationStrategyTest](src/test/java/com/uptimecrew/multistate/service/HybridAllocationStrategyTest.java) — blend behaviour, including the divergent-jurisdiction contract.

Integration tests (`*IT` suffix) boot a real context or container:

- [ApplicationContextLoadIT](src/test/java/com/uptimecrew/multistate/ApplicationContextLoadIT.java) — `@SpringBootTest` under the `test` profile. Asserts the context loads and the `AllocationService` bean is wired, then exercises it end-to-end through the injected `@Primary` (day-count) strategy.
- [TenantQueryIT](src/test/java/com/uptimecrew/multistate/repository/TenantQueryIT.java) — Testcontainers-backed Postgres query test. Waits on the listening port (not the default log wait) and a 120s startup timeout to stay reliable on Rancher Desktop's moby engine.
- [TenantSecurityIT](src/test/java/com/uptimecrew/multistate/TenantSecurityIT.java) — `@SpringBootTest` + `@AutoConfigureMockMvc` with real Postgres, Mongo and Redis via Testcontainers `@ServiceConnection`. Uses Spring Security's `jwt()` request post-processor to mint test JWTs and asserts the full authentication / authorization / rate-limit contract: `200` with the right scope+role, `401` anonymous, `403` when the role is missing, and `429` + `Retry-After: 60` after 10 `/summary` calls for one subject.

## Dependencies

Versions for the Spring Boot starters are managed by the Boot BOM (`io.spring.dependency-management`), pinned by the plugin version `3.3.2`.

| Scope | Library |
| --- | --- |
| `implementation` | `org.springframework.boot:spring-boot-starter`, `spring-boot-starter-web` (embedded Tomcat for Actuator), `spring-boot-starter-actuator`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server` |
| `implementation` | `com.bucket4j:bucket4j-core` (per-subject token-bucket rate limiting) |
| `testImplementation` | `org.springframework.security:spring-security-test` (MockMvc `jwt()` post-processor) |
| `runtimeOnly` | `org.springframework.boot:spring-boot-starter-jdbc` (HikariCP + `DataSource`), `org.postgresql:postgresql:42.7.3` |
| `implementation` | `org.slf4j:slf4j-api:2.0.12` |
| `runtimeOnly` | `ch.qos.logback:logback-classic:1.5.6` |
| `testImplementation` | `org.springframework.boot:spring-boot-starter-test` |
| `testImplementation` | `org.junit.jupiter:junit-jupiter` (BOM 5.10.2), `junit-jupiter-params` |
| `testImplementation` | `org.mockito:mockito-core:5.10.0`, `mockito-junit-jupiter:5.10.0` |
| `testImplementation` | `org.assertj:assertj-core:3.25.3` |
| `testImplementation` | `ch.qos.logback:logback-classic:1.5.6` (for `ListAppender` in tests) |
| `testImplementation` | `org.testcontainers:postgresql`, `testcontainers:junit-jupiter` (BOM 1.19.7) |

## Project layout

```
src/main/java/com/uptimecrew/multistate/
    Application.java  # Spring Boot entry point (@SpringBootApplication)
    api/              # @RestController endpoints (TenantController)
    security/         # SecurityFilterChain + JWT converter + RateLimitFilter
    model/            # domain types (value objects, entities)
    entity/           # JPA @Entity types
    readmodel/        # Mongo @Document read-side
    repository/       # Spring Data JPA + Mongo repositories
    service/          # allocation strategies + AllocationService (Spring beans)
    exception/        # AllocationException hierarchy
src/main/resources/
    application.yml   # local + test profiles, datasource, Actuator, logging
src/test/java/com/uptimecrew/multistate/
    ApplicationContextLoadIT.java  # @SpringBootTest context + bean wiring
    TenantPolyglotIT.java          # Postgres + Mongo + Redis end-to-end
    TenantSecurityIT.java          # JWT + role + rate-limit MockMvc tests
    model/
    service/
    repository/       # Testcontainers Postgres integration tests
```

Package root: `com.uptimecrew.multistate`. New code goes under this root.

The `db/` directory holds the Postgres schema (`V1__schema.sql`), seed (`V2__seed.sql`), verification queries (`verify.sql`), and ER diagram + design notes (`db/README.md`).

## Coding standards

See [CLAUDE.md](CLAUDE.md) for the hard rules. Highlights: JDK 17+, `BigDecimal` (scale 2, HALF_UP) for all money, `String` IDs, `java.time` for dates and timestamps, `private final` by default, JUnit 5. Mockito is allowed where it expresses the scenario more clearly than a hand-rolled fake (e.g. exception-path stubs).
