# ADR-002 · Resource allocations are the source of truth; the DB refuses overlaps

**Status:** Accepted, with a transitional mirror (27/10/2026)

## Context
A machine interval was written three times: `scheduled_bookings`, `booking_resource_allocations` and the older `machine_reservations`. Double booking was prevented only by application locks (advisory lock + `SELECT … FOR UPDATE`) followed by an overlap query (BR-02, NFR-06).

## Decision
1. `booking_resource_allocations` is the only source of truth for "who holds which resource when".
2. V44 adds `active` and the constraint
   `EXCLUDE USING gist (resource_id WITH =, tstzrange(occupied_from, occupied_until, '[)') WITH &&) WHERE (active)`.
   A trigger on `scheduled_bookings.status` keeps `active` in step. The set of resource-holding statuses lives in one SQL function, `booking_holds_resources()`, which queries also reuse.
3. Application locks stay. They let the API answer with MSG07 and suggestions; the constraint is the last line of defence and maps to `SLOT_TAKEN`.
4. Before placing a hold, lapsed holds on the locked resources are expired. This keeps the constraint from ever being stricter than the application rule.

## Transitional part
`machine_reservations` is still written in the same transaction as a **mirror**. QR check-in (`MachineStartAuthorizationService`, 33 tests) references its ids through `machine_start_authorizations.reservation_id`.
Follow-up: move check-in onto allocations, then drop the mirror in a later migration.

## Evidence
`HoldConcurrencyIT`: 20 concurrent holds on one interval → exactly 1 succeeds. A direct overlapping `INSERT` is rejected by PostgreSQL.
