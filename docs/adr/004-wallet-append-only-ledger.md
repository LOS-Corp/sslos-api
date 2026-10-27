# ADR-004 · Money in an append-only wallet ledger

**Status:** Accepted (27/10/2026). The legal status of the wallet is still to be confirmed with the supervisor (SRS V-04).

## Context
Refunds had no destination: there was no wallet, and the cap was checked per request rather than cumulatively (BR-14, BR-15, NFR-17).

## Decision
- `wallets` stores one row per customer. `balance_vnd BIGINT` is a cached value with `CHECK (balance_vnd >= 0)`.
- `wallet_entries` is the ledger: signed integer VND, a trigger refuses UPDATE and DELETE, and `UNIQUE (wallet_id, type, reference)` stops the same business event from moving money twice.
- `WalletService.credit/debit` use `Propagation.MANDATORY`, so a refund and its credit always share one transaction (FR-WAL-02).
- Refund rules are a pure `RefundPolicy` (BR-10…13). Cancellation, no-show, late payment and exception refunds all credit through it.
- A daily job compares every balance with its ledger sum and logs any difference as an error. It never auto-corrects.

- **Wallet first (FR-PAY-04):** checkout debits the wallet once per order and records a WALLET payment. payOS collects only the remainder. When the payOS part fails, is cancelled, or the hold lapses, the wallet part is reversed (V48, SC-31). A late payOS success credits only what was paid outside the wallet, so no order of events pays the customer twice.
- **Withdrawals (UC-13):** the amount is held in the ledger at once. The Owner completes the request only with a transfer proof (a DB check enforces it); a rejection releases the hold (V49, SC-30).

## Consequences
- Money is integer VND in new tables. Legacy `NUMERIC` amounts are converted with `Money.of`, which refuses fractional đồng.
- Proof upload is a text reference (bank transfer id or link) until file storage exists.
