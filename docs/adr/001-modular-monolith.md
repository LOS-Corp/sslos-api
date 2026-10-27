# ADR-001 · Modular monolith, package by business module

**Status:** Accepted (27/10/2026)

## Context
One small team, one PostgreSQL database. A booking hold, its payment and a wallet refund must commit together (BR-04, BR-15). The code base had grown a 1,864-line `ScheduledBookingService` and packages that mixed unrelated concerns.

## Decision
One Spring Boot application split into business modules (`booking`, `payment`, `refund`, `wallet`, `machine`, `iot`, `locker`, `user`, `audit`) plus `shared` infrastructure.
Inside a module:
- `controller`: maps HTTP to calls, with no rules and no repositories.
- `service`: orchestrates a use case in one transaction.
- `domain`: pure rules; no Spring, no JDBC.
- `repository`: data access.

## Rejected
- **Microservices.** Hold, payment and refund would need sagas and compensations for what one ACID transaction does today. That cost is not justified at the scale of one laundry chain.
- **Full hexagonal ports/adapters everywhere.** Adapters exist only where an external system sits (payOS, EMQX, SMTP).

## Consequences
- ArchUnit enforces three boundaries: domain purity, `shared` not depending on modules, and controllers not touching repositories.
- If a module later needs to become a service, its boundary already exists.
