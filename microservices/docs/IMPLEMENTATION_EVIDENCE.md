# Backend Implementation Evidence

This document maps the final backend implementation to demonstrable engineering evidence. It is intended for the final report, architecture assessment and live presentation.

## 1. Service boundaries

| Service | Owned responsibility | Owned persistence |
|---|---|---|
| API Gateway | Routing, JWT pre-validation, correlation IDs, rate limiting, timeouts and circuit breakers | None |
| Auth Service | Accounts, roles, passwords, lockout, access tokens and refresh tokens | `auth_db` |
| Workflow Service | Requests, approval state machine, cancellation, timeline, statistics and transactional outbox | `workflow_db` |
| Notification & Audit Service | In-app notifications, unread state, WebSocket publication and immutable audit trail | `notification_audit_db` |
| AI Assistant Service | Structured form completion and read-only on-call diagnosis | None; actions are recorded through Audit Service |
| Attachment Service | File metadata, content integrity and authorized download | `attachment_db` plus isolated file volume |

Services do not share JPA entities or database credentials. Cross-service communication uses authenticated HTTP contracts and idempotent domain events.

## 2. Business scenarios to demonstrate

### Scenario A — secure workflow lifecycle

1. A requester logs in and receives separate access and refresh tokens.
2. The requester submits a validated workflow to a real approver obtained from Auth Service.
3. Workflow Service persists the request and an outbox event in the same transaction.
4. Notification Service consumes the event exactly once and pushes it over WebSocket.
5. The assigned approver approves or rejects the request.
6. The requester sees the final state, chronological timeline, notification and audit evidence.
7. For multi-level workflows, each approval activates exactly one subsequent approval step.

### Scenario B — failure recovery

1. Stop Notification Service.
2. Submit a workflow successfully; the core transaction remains available.
3. Observe a pending outbox event and retry metadata.
4. Restart Notification Service.
5. Observe eventual delivery without duplicated audit events.

### Scenario C — account protection

1. Repeated invalid passwords increment the failure counter.
2. The configured threshold locks the account for a bounded period.
3. Refresh tokens cannot call protected business endpoints.
4. Disabled users cannot refresh tokens or change passwords.
5. Admin users can change account status but cannot disable themselves.

### Scenario D — AI assistance

1. Free-form user text is converted into validated workflow fields.
2. Invalid or unavailable provider output falls back to deterministic local assistance.
3. On-call diagnosis collects live service health and selects an applicable runbook.
4. AI actions and failures create correlation-aware audit events.

### Scenario E — templates, SLA and attachments

1. An administrator creates a reusable template with request defaults and an SLA duration.
2. A requester instantiates the template and receives a concrete deadline.
3. The requester uploads evidence; Attachment Service records type, size and SHA-256.
4. Only workflow participants and administrators can list or download the file.
5. An overdue pending request produces one SLA escalation event and notification.

## 3. Reliability controls

| Risk | Implemented control | Verification evidence |
|---|---|---|
| Duplicate event delivery | Globally unique event ID and idempotent consumer | Replaying an event leaves one audit record |
| Lost notification event | Transactional outbox | Workflow commits while notification service is offline |
| Double approval | Optimistic locking and final-state policy | Second transition returns `409 Conflict` |
| Downstream outage | Gateway circuit breakers and bounded timeouts | Fallback returns structured `503` response |
| Brute-force login | Configurable lock threshold and duration | Account policy unit tests |
| Token confusion | Access/refresh `token_type` claim validation | Refresh-token isolation integration test |
| Untraceable requests | `X-Correlation-ID` propagation and structured logs | Same identifier appears in gateway, services and audit events |

## 4. Verification commands

```powershell
mvn verify
docker compose config --quiet
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
```

`mvn verify` boots all Spring applications on random ports, runs Flyway migrations against isolated H2 databases, validates security contracts and executes domain-policy tests. The smoke test validates the complete route through the Gateway.

## 5. Presentation evidence to capture

- Swagger UI showing the five service API groups.
- MySQL schemas and restricted database users.
- Successful and rejected API calls with correlation IDs.
- Pending and delivered outbox rows during fault injection.
- WebSocket notification arriving without refreshing the page.
- Prometheus metrics from `/actuator/prometheus`.
- CI run containing compilation, tests, packaging and Compose validation.
- Test report counts from each module under `target/surefire-reports`.

## 6. Why the implementation is not measured only by line count

The principal engineering effort is visible in independently testable boundaries, database migrations, failure recovery, security invariants, deployment automation and observable runtime behaviour. Generated code and duplicated boilerplate are intentionally avoided. The codebase is split into policies and services where those boundaries create testability or ownership value.
