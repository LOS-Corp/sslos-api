# Architecture Decision Records — sslos-api

Each record states the context, the decision, the alternatives that were rejected, and the consequences.
Status values: **Accepted** (in the code and enforced), **Transitional** (accepted with a planned follow-up).

| ADR | Title | Status | Enforced by |
|-----|-------|--------|-------------|
| [001](001-modular-monolith.md) | Modular monolith, package by business module | Accepted | ArchUnit `ArchitectureTest` |
| [002](002-allocation-single-source-of-truth.md) | Resource allocations are the source of truth; the DB refuses overlaps; mirror removed | Accepted | `EXCLUDE` constraint (V44), `cycle_state` (V51), `HoldConcurrencyIT`, `MachineStartAuthorizationServiceTest` |
| [003](003-identity-and-branch-scope.md) | Identity from the token; four roles; Staff scoped by branch | Accepted | `SecurityConfig` deny-by-default, `SecurityMatrixIT` |
| [004](004-wallet-append-only-ledger.md) | Money in an append-only wallet ledger; wallet first; withdrawals with proof | Accepted | Trigger + `UNIQUE (wallet, type, reference)` (V45), V48, V49, `WalletServiceIT`, `WalletCheckoutIT`, `WithdrawalIT` |
| [005](005-business-rules-as-pure-policies.md) | Business rules as pure policies with an injected clock | Accepted | ArchUnit `domainIsPure`, JaCoCo gate |
| [006](006-one-error-model.md) | One error model: `ApiError` + `ErrorCode` → SRS MSGxx | Accepted | `GlobalExceptionHandler`, ArchUnit `errorsUseErrorCodes` |
| [007](007-side-effects-after-commit.md) | Device commands are published after commit | Accepted | `DeviceCommandDispatcher` |
| [008](008-cluster-safe-jobs.md) | Scheduled jobs are cluster-safe through PostgreSQL advisory locks | Accepted | `JobLock` |
| [009](009-tests-on-real-postgresql.md) | Integration tests run on real PostgreSQL | Accepted | Testcontainers, Failsafe in CI |
