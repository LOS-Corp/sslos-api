# ADR-007 · Device commands are published after commit

**Status:** Accepted (27/10/2026)

## Context
`DeviceCommandService` published MQTT inside its database transaction. If the transaction rolled back, the device had already received the command. RUNNING must only ever come from the device (BR-08).

## Decision
`issueCommand` stores the command as PENDING and writes the audit entry, then raises `DeviceCommandRequested`. `DeviceCommandDispatcher` listens with `@TransactionalEventListener(AFTER_COMMIT)`, publishes, and records SENT or FAILED in its own transaction. An ACK that never arrives becomes TIMEOUT through the existing job (BR-31).

## Rejected
A transactional outbox table with a poller. It is more robust across crashes, but a lost publish here is already visible as FAILED/TIMEOUT and the operator can retry. The outbox can be introduced later for notifications.

## Consequences
The API answers 202 with the PENDING command; clients poll the command history for the ACK.
