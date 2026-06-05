# UptimeCrew Multi-State

A Java domain library for splitting a worker's income across the tax jurisdictions where the work was actually performed. The motivating problem: a worker who lives in one state but performs services across several owes income tax to each jurisdiction in proportion to the work done there. This project models the inputs (where work happened) and the outputs (how income is divided), and houses the strategies that compute the split.

## Build & test

```bash
./gradlew build       # compile + test
./gradlew test        # run JUnit 5 tests
./gradlew clean       # wipe build outputs
```

Requires JDK 17+.

## Domain model

Located under [src/main/java/com/uptimecrew/multistate/model/](src/main/java/com/uptimecrew/multistate/model/).

| Type | Role |
| --- | --- |
| [Jurisdiction](src/main/java/com/uptimecrew/multistate/model/Jurisdiction.java) | A taxing authority — identified by `code` (e.g. `"US-CA"`), with a human display name and a [JurisdictionKind](src/main/java/com/uptimecrew/multistate/model/JurisdictionKind.java) (`FEDERAL`, `STATE`, `COUNTY`, `CITY`). |
| [WorkDay](src/main/java/com/uptimecrew/multistate/model/WorkDay.java) | One day of work attributed to one jurisdiction — the raw input to allocation. |
| [IncomeAllocation](src/main/java/com/uptimecrew/multistate/model/IncomeAllocation.java) | The result: a portion of a worker's income assigned to a jurisdiction for a given pay period. Money is `BigDecimal`, scale 2, HALF_UP. |
| [IncomeAllocationDraft](src/main/java/com/uptimecrew/multistate/model/IncomeAllocationDraft.java) | A pre-persistence shape of an allocation — same fields minus `workerId`, used while a batch is being assembled. |

## Allocation strategies

Located under [src/main/java/com/uptimecrew/multistate/service/](src/main/java/com/uptimecrew/multistate/service/).

- [AllocationStrategy](src/main/java/com/uptimecrew/multistate/service/AllocationStrategy.java) — the interface every allocator implements: given a worker, a total income, the work days, and the period being allocated, return one `IncomeAllocation` per jurisdiction.
- [DayCountAllocationStrategy](src/main/java/com/uptimecrew/multistate/service/DayCountAllocationStrategy.java) — splits income proportionally to the count of work days in each jurisdiction, rounding each share HALF_UP. Throws [JurisdictionUnsupportedException](src/main/java/com/uptimecrew/multistate/exception/JurisdictionUnsupportedException.java) on negative `totalIncome`. Known limitation: rounded shares may not re-sum to the total to the penny.
- [IncomeProportionalAllocationStrategy](src/main/java/com/uptimecrew/multistate/service/IncomeProportionalAllocationStrategy.java) — splits income across the participating jurisdictions in proportion to a configured per-jurisdiction revenue map. When a work day references a jurisdiction with no configured revenue, the lookup failure is wrapped (with the underlying cause preserved) and rethrown as [IncomeAllocationFailedException](src/main/java/com/uptimecrew/multistate/exception/IncomeAllocationFailedException.java).
- [HybridAllocationStrategy](src/main/java/com/uptimecrew/multistate/service/HybridAllocationStrategy.java) — blends two delegate strategies by a fixed `primaryWeight` in `[0, 1]`, summing per-jurisdiction contributions and rounding HALF_UP to scale 2.
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

Notable test classes:

- [AllocationServiceTest](src/test/java/com/uptimecrew/multistate/service/AllocationServiceTest.java) — boundary validation and happy-path delegation.
- [AllocationServiceMockitoTest](src/test/java/com/uptimecrew/multistate/service/AllocationServiceMockitoTest.java) — verifies the service delegates to the injected strategy.
- [AllocationServiceExceptionPathTest](src/test/java/com/uptimecrew/multistate/service/AllocationServiceExceptionPathTest.java) — typed exception propagation, root-cause preservation, and WARN-log emission.
- [HybridAllocationStrategyTest](src/test/java/com/uptimecrew/multistate/service/HybridAllocationStrategyTest.java) — blend behaviour, including the divergent-jurisdiction contract.

## Dependencies

| Scope | Library |
| --- | --- |
| `implementation` | `org.slf4j:slf4j-api:2.0.12` |
| `runtimeOnly` | `ch.qos.logback:logback-classic:1.5.6` |
| `testImplementation` | `org.junit.jupiter:junit-jupiter` (BOM 5.10.2), `junit-jupiter-params` |
| `testImplementation` | `org.mockito:mockito-core:5.10.0`, `mockito-junit-jupiter:5.10.0` |
| `testImplementation` | `org.assertj:assertj-core:3.25.3` |
| `testImplementation` | `ch.qos.logback:logback-classic:1.5.6` (for `ListAppender` in tests) |

## Project layout

```
src/main/java/com/uptimecrew/multistate/
    model/        # domain types (value objects, entities)
    service/     # allocation strategies + AllocationService
    exception/   # AllocationException hierarchy
src/test/java/com/uptimecrew/multistate/
    model/
    service/
```

Package root: `com.uptimecrew.multistate`. New code goes under this root.

## Coding standards

See [CLAUDE.md](CLAUDE.md) for the hard rules. Highlights: JDK 17+, `BigDecimal` (scale 2, HALF_UP) for all money, `String` IDs, `java.time` for dates and timestamps, `private final` by default, JUnit 5. Mockito is allowed where it expresses the scenario more clearly than a hand-rolled fake (e.g. exception-path stubs).
