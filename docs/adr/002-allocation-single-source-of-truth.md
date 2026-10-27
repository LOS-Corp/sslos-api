# ADR-002 · Resource allocations are the source of truth; the DB refuses overlaps

**Status:** Accepted (27/10/2026). The transitional mirror was removed on 28/10/2026 (V51).

## Context
A machine interval was written three times: `scheduled_bookings`, `booking_resource_allocations` and the older `machine_reservations`. Double booking was prevented only by application locks (advisory lock + `SELECT … FOR UPDATE`) followed by an overlap query (BR-02, NFR-06).

## Decision
1. `booking_resource_allocations` is the only source of truth for "who holds which resource when".
2. V44 adds `active` and the constraint
   `EXCLUDE USING gist (resource_id WITH =, tstzrange(occupied_from, occupied_until, '[)') WITH &&) WHERE (active)`.
   A trigger on `scheduled_bookings.status` keeps `active` in step. The set of resource-holding statuses lives in one SQL function, `booking_holds_resources()`, which queries also reuse.
3. Application locks stay. They let the API answer with MSG07 and suggestions; the constraint is the last line of defence and maps to `SLOT_TAKEN`.
4. Before placing a hold, lapsed holds on the locked resources are expired. This keeps the constraint from ever being stricter than the application rule.

## Mirror removed (V51)
Until 28/10 every washer/dryer allocation was copied into `machine_reservations`. Its status also carried the machine's cycle, and QR check-in stored its id. Now:
- **Cycle state lives on the allocation:** `cycle_state` goes PENDING → RUNNING → DONE. A CHECK constraint ties it to washer and dryer allocations only.
- **START authorizations point at the allocation and the booking:** they store `allocation_id` and `booking_id`. The remote START override now checks the booking id the operator sends; before, it was compared with a reservation id and could never match.
- **A cancelled booking cannot keep an unlocked START button:** cancelling a paid booking publishes `BookingReleased`. After commit, the machine module revokes active authorizations and sends `REVOKE_START` to the device.
- **The mirror and dead tables are gone:**
  - tables dropped: the mirror itself, the tables of the removed counter/kiosk purchase flow (`laundry_service_orders`, `drop_off_locker_reservations`) and `qr_tokens`;
  - code removed: 16 legacy `transaction` classes. `PurchaseService` was replaced by a read-only `TransactionQueryService`.

## Evidence
- `HoldConcurrencyIT`: 20 concurrent holds on one interval → exactly 1 succeeds. A direct overlapping `INSERT` is rejected by PostgreSQL.
- `MachineStartAuthorizationServiceTest` (33 tests, on PostgreSQL):
  - check-in, one cycle per allocation, a second booking on the same machine, and the sweep that closes a lost COMPLETED;
  - `MoneyFlowIT.cancellationRevokesStart`.
- V50 and V51 were dry-run on a copy of a real local database.
