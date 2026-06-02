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
- [DayCountAllocationStrategy](src/main/java/com/uptimecrew/multistate/service/DayCountAllocationStrategy.java) — splits income proportionally to the count of work days in each jurisdiction, rounding each share HALF_UP. Known limitation: rounded shares may not re-sum to the total to the penny; a residual-aware strategy is intended as a follow-up.

## Project layout

```
src/main/java/com/uptimecrew/multistate/
    model/      # domain types (value objects, entities)
    service/    # domain behavior — allocation strategies
src/test/java/com/uptimecrew/multistate/
    model/
    service/
```

Package root: `com.uptimecrew.multistate`. New code goes under this root.

## Coding standards

See [CLAUDE.md](CLAUDE.md) for the hard rules. Highlights: JDK 17+, `BigDecimal` (scale 2, HALF_UP) for all money, `String` IDs, `java.time` for dates and timestamps, `private final` by default, JUnit 5 with no mocking framework.
