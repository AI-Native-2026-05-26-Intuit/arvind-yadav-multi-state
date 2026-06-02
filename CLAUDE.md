# UptimeCrew Multi-State

Java domain project bootstrapped for multi-state compliance work. Built with Gradle.

## Build & Test

```bash
./gradlew build       # compile + test
./gradlew test        # run JUnit 5 tests
./gradlew clean       # wipe build outputs
```

## Project Layout

```
src/main/java/com/uptimecrew/multistate/
    model/      # domain types (value objects, entities)
    service/    # domain behavior
src/test/java/com/uptimecrew/multistate/
    model/
    service/
```

Package root: `com.uptimecrew.multistate`. New code goes under this root — do not introduce sibling packages.

## Coding Standards

These are hard rules. Apply them without asking.

### Java baseline
- JDK **17+**. Use modern language features (records, `var` where it aids readability, pattern matching, sealed types) when they fit.

### Money
- Always `java.math.BigDecimal` with **scale 2** and `RoundingMode.HALF_UP`.
- **Never** `double` or `float` for monetary values — not in fields, parameters, return types, or intermediate math.
- Set scale explicitly on construction and after any arithmetic that can change it (`multiply`, `divide`).

### Identifiers
- IDs are `String` — either UUID v4 (`UUID.randomUUID().toString()`) or a prefixed synthetic key (e.g. `"ord_..."`, `"emp_..."`).
- **Never** `int` or `long` for identifiers, even for internal/auto-generated ones.

### Dates & times
- `java.time.LocalDate` for calendar dates (pay period, filing date, DOB).
- `java.time.Instant` for timestamps (event times, audit fields, "now").
- **Never** `java.util.Date`, `java.sql.Date`, `java.util.Calendar`, or epoch `long` for date/time values.
- Use `Clock` injection for anything that reads "now" so tests can pin time.

### Fields & classes
- Fields and local references default to `private final` / `final`. Mutability requires a justification.
- Classes default to `final` unless designed for extension.
- **No Lombok `@Data`** (and avoid Lombok generally — prefer records or hand-written constructors/accessors for domain types).
- Validate invariants in the constructor; throw `IllegalArgumentException` for bad input, `NullPointerException` (via `Objects.requireNonNull`) for nulls.

### Tests
- JUnit 5 only: `@Test`, `@BeforeEach`, `@DisplayName`, `assertEquals`, `assertTrue`, `assertThrows`, etc.
- One behavior per test. Name tests for the behavior, not the method (`returnsZeroWhenNoEntries`, not `testCalculate`).
- Mirror the production package under `src/test/java`.
- No mocking framework by default — prefer real objects and hand-rolled fakes for domain logic.

## When in doubt

Match the rules above over any pattern you find in older code. If a rule conflicts with a task the user asked for, surface it before writing the code — don't silently violate.
