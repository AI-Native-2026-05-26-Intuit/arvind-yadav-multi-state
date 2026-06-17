# UptimeCrew Multi-State

A Java domain library for splitting a worker's income across the tax jurisdictions where the work was actually performed. The motivating problem: a worker who lives in one state but performs services across several owes income tax to each jurisdiction in proportion to the work done there. This project models the inputs (where work happened) and the outputs (how income is divided), and houses the strategies that compute the split.

The domain core is now packaged as a **Spring Boot 4** application: the service and its allocation strategies are Spring-managed beans, and the build produces a runnable boot jar with Actuator health/info endpoints. The pure-Java domain types and standards (see [CLAUDE.md](CLAUDE.md)) are unchanged — Spring sits around them, not inside them.

**Week 2 Day 4:** mapped the three W2 D1 tables to JPA entities (`Tenant`, `Allocation`, `Jurisdiction`) with a LAZY `Tenant`↔`Allocation` relationship, added three Spring Data `JpaRepository` interfaces (derived + `@Query` JPQL methods), wired `TenantRepository` into `AllocationService` under `@Transactional`, and added a Testcontainers-backed `@DataJpaTest` slice ([TenantRepositoryIT](src/test/java/com/uptimecrew/multistate/repository/TenantRepositoryIT.java)).

**Week 2 Day 5:** added a polyglot read side — a Mongo `@Document` read model ([TenantReadModel](src/main/java/com/uptimecrew/multistate/readmodel/TenantReadModel.java)) with embedded allocations and its `MongoRepository`, write-through from `AllocationService` to Mongo inside the existing JPA transaction, and a Redis-backed `@Cacheable` `findById` read path (`@EnableCaching`), all verified end-to-end against Postgres + Mongo + Redis containers in [TenantPolyglotIT](src/test/java/com/uptimecrew/multistate/TenantPolyglotIT.java).

**Week 3 Day 1:** put the read side behind a secured HTTP API. Added an OAuth2 resource-server [SecurityConfig](src/main/java/com/uptimecrew/multistate/security/SecurityConfig.java) (stateless, JWT-validated, default-deny under `/api/**`, `/actuator/health` public) with a `JwtAuthenticationConverter` that maps the `scope` claim to `SCOPE_*` authorities and a custom `roles` claim to `ROLE_*` authorities, enabling `@PreAuthorize` SpEL on the controller. A per-subject Bucket4j [RateLimitFilter](src/main/java/com/uptimecrew/multistate/security/RateLimitFilter.java) (10 requests / minute, in-memory `ConcurrentHashMap` keyed by JWT subject, scoped to `/api/**/summary`) is registered *after* `BearerTokenAuthenticationFilter` so the JWT principal is resolved before the bucket lookup; over-limit responses return `429` with `Retry-After: 60`. End-to-end coverage in [TenantSecurityIT](src/test/java/com/uptimecrew/multistate/TenantSecurityIT.java) asserts 200/401/403 on the read endpoint and 429-after-10 on the summary endpoint, against real Postgres + Mongo + Redis containers.

**Week 3 Day 2:** versioned the API under `/api/v1`, documented it with OpenAPI 3.1, made the summary endpoint a write-shaped idempotent POST, and wired a declarative identity client behind a Resilience4j circuit breaker.

- **URI versioning + OpenAPI.** Routes moved to `/api/v1/tenants/**`. [OpenApiConfig](src/main/java/com/uptimecrew/multistate/config/OpenApiConfig.java) registers an `@Bean OpenAPI` with `Info(title, v1.0.0, description)` and an HTTP/bearer `SecurityScheme` named `bearer-jwt`; both controller routes carry `@Operation` + `@ApiResponses`. springdoc serves `/v3/api-docs` and `/swagger-ui.html`; both paths are permit-listed in `SecurityConfig` so the Swagger UI works without a token.
- **Idempotent POST `/summary`.** `GET → POST /api/v1/tenants/{id}/summary` now requires an `Idempotency-Key` UUID header (400 on missing/non-UUID). [IdempotencyService](src/main/java/com/uptimecrew/multistate/api/IdempotencyService.java) wraps `StringRedisTemplate`: `idem:{namespace}:{key}` is set with `SETNX` and a 24h TTL, the response body is Jackson-serialised on success, and a concurrent retry while in-flight returns `409 Conflict`. Idempotency complements (does not replace) the per-subject rate limit — Bucket4j caps *frequency*, idempotency makes a *single* request safe to retry.
- **Identity client + circuit breaker.** [TenantIdentityClient](src/main/java/com/uptimecrew/multistate/clients/TenantIdentityClient.java) is a declarative HTTP-client interface bound to `${identity.base-url}`. [IdentityService](src/main/java/com/uptimecrew/multistate/clients/IdentityService.java) wraps it with `@CircuitBreaker(name = "identity", fallbackMethod = "fallbackProfile")`; the fallback returns a degraded `IdentityProfile(userId, "", "unknown")`. `TenantController.summary` calls `identityService.getProfile(jwt.getSubject())` and returns `displayName` in the body. The breaker config lives in `application.yml` under `resilience4j.circuitbreaker.instances.identity` (sliding window 10, failure-rate threshold 50%, 10s open, 3 permitted in half-open). The breaker is on the `@Service`, not the client interface — the declarative-client proxy stack short-circuits before the Resilience4j AOP advisor, so an annotation on the interface is silently a no-op.
- **Deviation — declarative client framework.** The W3 D2 assignment specifies Spring Cloud OpenFeign. The current Spring Cloud line (incl. `spring-cloud-dependencies:2025.0.0`) still compiles against Boot 3 internals (`org.springframework.boot.web.context.WebServerInitializedEvent`), so context refresh on Boot 4 fails with `NoClassDefFoundError`. We use Spring Boot's native `@HttpExchange` interface bound to a `RestClient` via `HttpServiceProxyFactory` — same declarative-interface shape, no Spring Cloud BOM. Every other piece (the `IdentityService` wrapper, the `@CircuitBreaker` and fallback signature, the breaker config, the controller wiring, the contract test) is unchanged.
- **Contract test.** [IdentityClientCircuitBreakerIT](src/test/java/com/uptimecrew/multistate/contract/IdentityClientCircuitBreakerIT.java) runs in-process WireMock on port 8090 (matching `identity.base-url`) and covers four behaviours: the 200 happy path, breaker transitioning to OPEN after repeated 5xx (with subsequent calls short-circuiting to the fallback without reaching WireMock), the controller POST returning `200` with `$.displayName` in the body, and `/v3/api-docs` exposing `/api/v1/tenants/{id}` with the `bearer-jwt` scheme.

**Week 3 Day 3:** wired the write path through Kafka via a transactional outbox, mirrored back into Mongo by an idempotent consumer with DLT safety net, and exposed a narrow MCP tool for LLM clients.

- **Transactional outbox.** [V3__event_outbox.sql](db/V3__event_outbox.sql) adds `multistate.event_outbox` with a partial index on `published_at IS NULL`. [EventOutboxEntity](src/main/java/com/uptimecrew/multistate/outbox/EventOutboxEntity.java) maps it (JSONB payload via `@JdbcTypeCode(SqlTypes.JSON)`); [EventOutboxRepository](src/main/java/com/uptimecrew/multistate/outbox/EventOutboxRepository.java) exposes `findUnpublishedForUpdate(Pageable)` as a native query using `FOR UPDATE SKIP LOCKED` so concurrent publishers grab disjoint batches instead of blocking each other. `AllocationService.allocate(...)` inserts one outbox row inside the existing JPA `@Transactional` block, alongside the Mongo write-through — the event is committed atomically with the business state. The Kafka topic is `tenants.events`, keyed by the tenant id (per-aggregate ordering).
- **Outbox publisher.** [OutboxPublisher](src/main/java/com/uptimecrew/multistate/outbox/OutboxPublisher.java) is a `@Scheduled(fixedDelay = 1000) @Transactional` loop that reads up to 50 unpublished rows per pass, sends each synchronously via `KafkaTemplate` with a 5s timeout, and stamps `published_at` on success only — a transient Kafka failure leaves the row unpublished so the next poll retries it. `@EnableScheduling` is on `Application`. [KafkaProducerConfig](src/main/java/com/uptimecrew/multistate/outbox/KafkaProducerConfig.java) registers an explicit `KafkaTemplate<String, String>` (`acks=all` + `enable.idempotence=true`) because Spring Boot 4 + spring-kafka 4 isn't auto-registering one in this project.
- **Consumer + DLT.** [AllocationCreatedListener](src/main/java/com/uptimecrew/multistate/consumer/AllocationCreatedListener.java) is a single `@KafkaListener` on `tenants.events` that parses the JSON payload into an [AllocationCreatedEvent](src/main/java/com/uptimecrew/multistate/consumer/AllocationCreatedEvent.java) record, then `findById(...).orElseGet(new TenantReadModel(id))` + `applyEvent(event)` + `save(...)` — applying the same event twice produces the same document, so at-least-once redelivery is safe. [KafkaErrorHandlingConfig](src/main/java/com/uptimecrew/multistate/consumer/KafkaErrorHandlingConfig.java) (`@EnableKafka`) registers the `ConsumerFactory` (using `ErrorHandlingDeserializer` wrapping `StringDeserializer` for both key and value), a `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` routing failed records to `tenants.events.DLT` after `FixedBackOff(1000ms, 3)`, and the `ConcurrentKafkaListenerContainerFactory` wiring them together. The wrapper means a Jackson parse failure on a poison-pill payload surfaces as a handler exception and lands in the DLT instead of crashing the container.
- **MCP server.** [TenantMcpServer](src/main/java/com/uptimecrew/multistate/mcp/TenantMcpServer.java) exposes one `@Tool` method `lookupTenant(String id)` over MCP. [McpToolConfig](src/main/java/com/uptimecrew/multistate/mcp/McpToolConfig.java) bridges it to the MCP server with a `MethodToolCallbackProvider` bean (Spring AI 2.0's annotation-scanner targets `@McpTool`, a different annotation, so an explicit provider is required). `application.yml` sets `spring.ai.mcp.server.type: SYNC` and `transport: SSE`; the running app publishes the tool at `http://localhost:8080/sse` and clients message it at the session-scoped `/mcp/message` endpoint. [mcp.json](mcp.json) at the repo root is the SSE-mode Claude Code registration. `SecurityConfig` permits `/sse` and `/mcp/**` for local development — production MCP exposure should sit behind a dedicated filter chain (mTLS or MCP-only bearer).
- **Event-flow IT.** [TenantEventFlowIT](src/test/java/com/uptimecrew/multistate/TenantEventFlowIT.java) boots the full context against four real containers (Postgres + Mongo + Redis via `@ServiceConnection`, Kafka via `@DynamicPropertySource` because Boot 4.0.6's `spring-boot-testcontainers` jar ships no Kafka `ConnectionDetailsFactory`) and verifies the round trip: a domain write surfaces on `tenants.events` keyed by the aggregate id, a directly-produced event materialises in Mongo via the listener, and a malformed payload retries three times and lands on `tenants.events.DLT`.

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

The project is bootstrapped as a Spring Boot 4 application (Boot + dependency-management Gradle plugins).

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

All `/api/v1/**` routes require a valid Bearer JWT and authority `SCOPE_tenants.read` + role `TENANT_READER`. `/actuator/health`, `/v3/api-docs/**`, and `/swagger-ui/**` are unauthenticated. The session is stateless; CSRF is disabled because there is no cookie-based auth surface.

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `GET` | `/api/v1/tenants/{id}` | JWT + `SCOPE_tenants.read` + `ROLE_TENANT_READER` | Returns the [TenantReadModel](src/main/java/com/uptimecrew/multistate/readmodel/TenantReadModel.java) from the Redis-cached Mongo read side. 404 if not found. |
| `POST` | `/api/v1/tenants/{id}/summary` | Same as above + `Idempotency-Key` UUID header | Stubbed LLM-style summary. Body includes `displayName` resolved from the identity service (degraded value if the breaker is OPEN). Rate-limited to **10 requests / minute per JWT subject**; over-limit returns `429` with `Retry-After: 60`. `400` on missing/non-UUID header; `409` on concurrent retry with the same key while the original is still in flight. |
| `GET` | `/v3/api-docs` | none | OpenAPI 3.1 JSON. |
| `GET` | `/swagger-ui.html` | none | Swagger UI. |
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
- [TenantSecurityIT](src/test/java/com/uptimecrew/multistate/TenantSecurityIT.java) — `@SpringBootTest` + `@AutoConfigureMockMvc` with real Postgres, Mongo and Redis via Testcontainers `@ServiceConnection`. Uses Spring Security's `jwt()` request post-processor to mint test JWTs and asserts the full authentication / authorization / rate-limit contract: `200` with the right scope+role, `401` anonymous, `403` when the role is missing, and `429` + `Retry-After: 60` after 10 `POST /summary` calls (with unique `Idempotency-Key` headers) for one subject.
- [IdentityClientCircuitBreakerIT](src/test/java/com/uptimecrew/multistate/contract/IdentityClientCircuitBreakerIT.java) — `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureMockMvc` with in-process WireMock on port 8090 (matching `identity.base-url`). Covers the identity 200 path, breaker transitioning to OPEN after repeated 5xx with subsequent calls short-circuiting to the fallback (no further WireMock hits), the controller POST returning `200` with `$.displayName`, and `/v3/api-docs` exposing `/api/v1/tenants/{id}` with the `bearer-jwt` scheme. Resets the `identity` breaker `@BeforeEach` so the 500-loop test does not bleed OPEN state into the 200-stub test.
- [TenantEventFlowIT](src/test/java/com/uptimecrew/multistate/TenantEventFlowIT.java) — four-container `@SpringBootTest` (Postgres + Mongo + Redis + Kafka). Three scenarios: a domain write goes through `AllocationService.allocate(...)` and the outbox publisher; a directly-produced JSON event flows through the listener into Mongo; a malformed payload retries 3× via `FixedBackOff(1000ms, 3)` and lands on `tenants.events.DLT`. Postgres/Mongo/Redis are wired via `@ServiceConnection`; Kafka uses `@DynamicPropertySource` because Boot 4.0.6's `spring-boot-testcontainers` jar ships no Kafka `ConnectionDetailsFactory`.

Every IT that touches the JPA layer also applies `db/V3__event_outbox.sql` in `@BeforeAll` — `EventOutboxEntity` is part of the persistence unit, so Hibernate's `validate` mode requires the table to exist before context refresh.

## Dependencies

Versions for the Spring Boot starters are managed by the Boot BOM (`io.spring.dependency-management`), pinned by the `org.springframework.boot` plugin version `4.0.6`.

| Scope | Library |
| --- | --- |
| `implementation` | `org.springframework.boot:spring-boot-starter`, `spring-boot-starter-web` (embedded Tomcat for Actuator), `spring-boot-starter-actuator`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server` |
| `implementation` | `com.bucket4j:bucket4j-core`, `com.bucket4j:bucket4j-redis` (per-subject token-bucket rate limiting) |
| `implementation` | `org.springframework.boot:spring-boot-starter-data-redis` (also backs the W3 D2 idempotency store) |
| `implementation` | `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0` (OpenAPI 3.1 + Swagger UI) |
| `implementation` | `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` (`@CircuitBreaker` advisor on the identity wrapper) |
| `implementation` | `org.springframework.kafka:spring-kafka` (outbox publisher + `@KafkaListener` consumer + DLT) |
| `implementation` | `org.springframework.ai:spring-ai-starter-mcp-server-webmvc:2.0.0` (MCP SSE server hosting the `lookupTenant` `@Tool`) |
| `testImplementation` | `org.springframework.security:spring-security-test` (MockMvc `jwt()` post-processor) |
| `testImplementation` | `org.wiremock:wiremock-standalone:3.9.1` (contract test for the identity client) |
| `testImplementation` | `org.springframework.kafka:spring-kafka-test`, `org.testcontainers:kafka:1.20.4`, `org.awaitility:awaitility:4.2.2` (event-flow IT) |
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
    Application.java  # Spring Boot entry point (@SpringBootApplication, @EnableScheduling)
    api/              # @RestController endpoints (TenantController) + IdempotencyService
    clients/          # declarative identity HTTP client + IdentityService (breaker wrapper)
    config/           # OpenApiConfig (OpenAPI 3.1 bean + bearer-jwt scheme)
    consumer/         # @KafkaListener (re-projection) + DLT error handler + event record
    mcp/              # @Tool lookupTenant + MethodToolCallbackProvider bridge
    outbox/           # EventOutboxEntity/Repository, OutboxPublisher, KafkaProducerConfig
    security/         # SecurityFilterChain + JWT converter + RateLimitFilter
    model/            # domain types (value objects, entities)
    entity/           # JPA @Entity types
    readmodel/        # Mongo @Document read-side (with applyEvent for consumer projection)
    repository/       # Spring Data JPA + Mongo repositories
    service/          # allocation strategies + AllocationService (Spring beans)
    exception/        # AllocationException hierarchy
src/main/resources/
    application.yml   # local + test profiles, datasource, Actuator, logging,
                      # springdoc paths, identity.base-url, resilience4j breaker,
                      # kafka producer/consumer, spring.ai.mcp.server (SYNC + SSE)
src/test/java/com/uptimecrew/multistate/
    ApplicationContextLoadIT.java     # @SpringBootTest context + bean wiring
    TenantPolyglotIT.java             # Postgres + Mongo + Redis end-to-end
    TenantSecurityIT.java             # JWT + role + rate-limit MockMvc tests
    TenantEventFlowIT.java            # 4-container: outbox -> Kafka -> consumer -> Mongo + DLT
    contract/
        IdentityClientCircuitBreakerIT.java  # WireMock + breaker + OpenAPI assertion
    model/
    service/
    repository/       # Testcontainers Postgres integration tests
mcp.json              # Claude Code MCP client registration (SSE mode)
```

Package root: `com.uptimecrew.multistate`. New code goes under this root.

The `db/` directory holds the Postgres schema (`V1__schema.sql`), seed (`V2__seed.sql`), the W3 D3 outbox migration (`V3__event_outbox.sql`), verification queries (`verify.sql`), and ER diagram + design notes (`db/README.md`).

## Coding standards

See [CLAUDE.md](CLAUDE.md) for the hard rules. Highlights: JDK 17+, `BigDecimal` (scale 2, HALF_UP) for all money, `String` IDs, `java.time` for dates and timestamps, `private final` by default, JUnit 5. Mockito is allowed where it expresses the scenario more clearly than a hand-rolled fake (e.g. exception-path stubs).
