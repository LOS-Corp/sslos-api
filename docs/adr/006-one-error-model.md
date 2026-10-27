# ADR-006 · One error model: ApiError + ErrorCode → SRS MSGxx

**Status:** Accepted, migration complete (28/10/2026)

## Context
- Two `@RestControllerAdvice` classes existed.
- `RefundException`, `PaymentException` and `QrTokenException` had no handler and answered 500.
- `IllegalStateException` became 400.
- Access-denied messages leaked internals.
- Clients had to parse English text.

## Decision
- `GlobalExceptionHandler` is the only handler. Every error leaves as SRS 5.2 `ApiError {timestamp, status, message, fields}`, plus three additive fields:
  - `code`: a stable `ErrorCode`;
  - `messageCode`: the SRS MSGxx when one exists;
  - `traceId`: the same value as the `X-Trace-Id` header.
- New code throws `BusinessException(ErrorCode, message)`. The old domain exceptions now extend it.
- Old `ResponseStatusException` throws are still mapped to the same body.
- The security filter chain writes 401/403 in the same shape (`SecurityErrorResponder`).

## Migration
All 84 `new ResponseStatusException` call sites were converted to `BusinessException(ErrorCode)` on 28/10/2026, and controllers that parsed the token by hand now take a `CurrentUser`. ArchUnit forbids `ResponseStatusException` in every class except `GlobalExceptionHandler` (it translates the type) and `ProvisioningRejectedException` (the device protocol keeps its status).
