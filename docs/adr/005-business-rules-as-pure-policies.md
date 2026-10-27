# ADR-005 · Business rules as pure policies with an injected clock

**Status:** Accepted (27/10/2026)

## Context
Time rules were scattered and compared against `LocalDateTime.now()` in the JVM zone. Examples: the check-in window "until endTime" instead of T+10, and a 10-minute hold instead of 5. Boundary tests were therefore impossible to write.

## Decision
Rules with boundaries live in small classes without Spring or a database, and take the time as a parameter:
- `SlotPlanner`: phases, Wash–Dry, opening hours across midnight, overlaps.
- `CheckInWindowPolicy`: T−15 … max(T, PAID)+10 (BR-06/07).
- `RefundPolicy`: T−60, rolling 90-day rank, 100/70/50/0 %, system failure (BR-10…13).
- `Money`: whole đồng, rounding down.

Services pass in an injected `Clock` (UTC) and the configured parameters (SRS 5.1 "single source of values").

## Consequences
- Each acceptance scenario is a row in a table-driven test: SC-06/07/18/19/29/35/37.
- ArchUnit forbids Spring, JPA, JDBC and Servlet imports in `..domain..`. JaCoCo requires ≥ 80 % line coverage there.
