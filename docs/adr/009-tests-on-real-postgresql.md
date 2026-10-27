# ADR-009 · Integration tests run on real PostgreSQL

**Status:** Accepted (27/10/2026)

## Context
Integration tests ran on H2 with `ddl-auto=create-drop` and Flyway disabled. They passed while the lifecycle SQL failed with `bad SQL grammar`, and they could not see foreign keys, `EXCLUDE` constraints, triggers, JSONB or `SKIP LOCKED`.

## Decision
- One PostgreSQL 16 Testcontainer is shared by the whole test JVM (`@IntegrationTest`).
- Flyway builds the schema from the real migrations and Hibernate runs with `validate`, exactly as in production (NFR-12).
- `*IT` classes run in Failsafe during `verify`.
- CI runs `./mvnw -B verify` and uploads the Surefire and JaCoCo reports.

## Consequences
- Moving the tests surfaced real defects: fixtures violating foreign keys, and the refund state that could never be approved.
- A test run needs Docker. The full suite takes a few minutes, which is accepted for the confidence it gives.
