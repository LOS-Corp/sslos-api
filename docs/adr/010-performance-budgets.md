# ADR-010 · Booking read paths have measured performance budgets

**Status:** Accepted (28/10/2026)

## Context
The timeline, machine search and recommendation endpoints are public. The customer apps also poll them every 30 seconds, so their cost multiplies with traffic. Nothing measured them, and two problems went unnoticed:
- the recommendation path issued one `COUNT` per machine (N+1);
- the occupancy query filtered by branch, so PostgreSQL read every finished booking of the branch. The cost grew with history, not with live bookings.

## Decision
1. **Measure before changing.** `BookingPerformanceIT` seeds a busy branch on PostgreSQL:
   - 20 washers and 20 dryers;
   - 960 live bookings;
   - 50,000 finished bookings in history.

   It counts SQL statements per call, using a DataSource proxy scoped to the measuring thread. It also records median and p90 times, and writes `target/booking-performance.md`.
2. **Budgets fail the build.** The test holds a statement budget for each endpoint, so an N+1 cannot come back unnoticed. A second test asserts that the occupancy query's plan contains no sequential scan on `booking_resource_allocations`.
3. **Live allocations are read through the V44 index.** Queries for live allocations use `a.active` and `tstzrange(occupied_from, occupied_until, '[)') && tstzrange(?, ?, '[)')`, which match the partial GiST index of the no-overlap constraint. No extra index is needed.
4. **Measured results must be real.** Each call must return machines or recommendations; an empty answer fails the test. Simulated machines get a heartbeat in the future, so the offline detector does not empty the branch during the run.

## Consequences
- Recommendations went from 48 to 9 statements and from 80 to 16 ms. The other read paths are 38–43 % faster.
- The occupancy query takes 2.6 ms instead of 12.7 ms and no longer grows with history.
- The per-minute availability loops were measured at 2–9 ms and left as they are: rewriting them would add complexity for little gain.
- Absolute times depend on the machine (Docker on Windows here). The statement counts and the query plan are the stable signals.
