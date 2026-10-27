# ADR-008 · Scheduled jobs are cluster-safe through PostgreSQL advisory locks

**Status:** Accepted (27/10/2026)

## Context
Nine `@Scheduled` jobs (hold expiry, offline detection, command timeout, window close…) would all run on every instance as soon as the API was scaled out. UC-28 requires the jobs to be idempotent.

## Decision
`JobLock` uses PostgreSQL advisory locks:
- `tryLockForTransaction(job)` uses `pg_try_advisory_xact_lock` for jobs that already run in a transaction; the lock is released at commit.
- `runExclusive(job, task)` uses `pg_try_advisory_lock` on a dedicated connection for jobs that only delegate.

An instance that does not get the lock skips the tick. Batch queries use `FOR UPDATE SKIP LOCKED`.
The runtime-snapshot flush is **not** locked, because it writes each instance's own in-memory cache.

## Rejected
ShedLock: an extra dependency and table for a feature PostgreSQL already provides.
