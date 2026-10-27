# ADR-003 · Identity from the token; four roles; Staff scoped by branch

**Status:** Accepted (27/10/2026)

## Context
- Refund approval read `managerId`/`managerBranchId` from the request body.
- Refund creation read `X-User-Id` from a header.
- `/api/mobile/**`, `/api/kiosk/**` and `/api/payments/callback/**` were open to anonymous writes; with the mock gateway, anyone could mark a payment PAID.
- Any endpoint not listed in `SecurityConfig` was allowed to every authenticated user.
- A MANAGER role existed that the SRS (BR-01) does not have.

## Decision
- The only roles are ADMIN, OWNER, STAFF and CUSTOMER (V41 converts MANAGER accounts to OWNER).
- The caller is a `CurrentUser` resolved from the JWT. No request DTO carries an actor id.
- Two layers of authorisation:
  1. Role rules per URL, with `anyRequest().denyAll()` for anything unlisted.
  2. Data scope in the service: the Owner manages every branch; Staff only the branches in `branch_assignments`; customers only their own rows.
- Sensitive actions require a reason where the SRS says so (BR-25) and are written to the append-only `audit_logs` (V42).

## Consequences
- The permission matrix in `task/09_PHAN_QUYEN_4_VAI_TRO.md` is the single reference; `SecurityMatrixIT` checks it through the real filter chain.
- STOMP frames are authenticated as well (`StompAuthChannelInterceptor`).
