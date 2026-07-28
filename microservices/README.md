# Workflow Platform Microservices

This is the final-version backend architecture. The original monolith remains in the parent directory as an MVP regression baseline.

## Components

| Component | Port | Responsibility |
|---|---:|---|
| API Gateway | 8080 | JWT pre-validation, rate limiting, circuit breakers, CORS and routing |
| Auth & User Service | 8081 | Login, access/refresh tokens, lockout, passwords, users and roles |
| Workflow & Approval Service | 8082 | Requests, tasks, decisions, cancellation, timeline and statistics |
| Notification & Audit Service | 8083 | Paginated/realtime notifications and immutable audit events |
| AI Assistant Service | 8084 | Form assistance and health/runbook-based on-call assistance |
| Attachment Service | 8085 | Secure workflow file storage, metadata and integrity hashes |

Each stateful service owns its own MySQL schema and database account. Services share API contracts and JWT claims, not JPA entities or database tables. Flyway owns all schema changes.

## Reliability and security

- JWT validation and role enforcement in every protected service
- Uniform JSON errors with proper `401`, `403`, `404`, `409` responses
- `X-Correlation-ID` generated at the Gateway and propagated into audit events
- Optimistic locking prevents double approval
- Transactional Outbox with exponential retry prevents workflow events from being lost
- Event IDs make notification/audit ingestion idempotent
- Internal APIs require `X-Internal-Key`
- Workflow access is rechecked before audit logs are returned
- Notification read/read-all support
- WebSocket notification delivery, unread count and pagination
- Search, status filtering and pagination
- Ordered multi-level approval chains with persisted step state
- Reusable workflow templates and template-specific SLA deadlines
- Scheduled SLA breach detection and escalation notifications
- Attachment authorization, MIME allow-listing and SHA-256 integrity evidence
- Account lockout, password change, account status and refresh-token rotation
- Gateway timeouts, circuit breakers and per-IP rate limiting
- Prometheus metrics on `/actuator/prometheus`
- Flyway migrations and Hibernate schema validation

## Build

```bash
mvn test
mvn package
```

## Run locally

Package first, then start the five executable jars in separate terminals:

```bash
java -jar auth-service/target/auth-service-1.0.0-SNAPSHOT.jar
java -jar notification-audit-service/target/notification-audit-service-1.0.0-SNAPSHOT.jar
java -jar workflow-service/target/workflow-service-1.0.0-SNAPSHOT.jar
java -jar ai-assistant-service/target/ai-assistant-service-1.0.0-SNAPSHOT.jar
java -jar attachment-service/target/attachment-service-1.0.0-SNAPSHOT.jar
java -jar api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar
```

Or run the final MySQL-based stack:

```bash
docker compose up --build
```

The frontend uses only `http://localhost:8080`.

After the stack is healthy, run the repeatable end-to-end check:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
```

Aggregated Swagger UI is available at `http://localhost:8080/swagger-ui.html`.

Final-report scenarios and the implementation-to-evidence mapping are documented in
[`docs/IMPLEMENTATION_EVIDENCE.md`](docs/IMPLEMENTATION_EVIDENCE.md).

Docker creates three schemas and three restricted database users:

- `auth_db` / `auth_user`
- `workflow_db` / `workflow_user`
- `notification_audit_db` / `audit_user`
- `attachment_db` / `attachment_user`

## Seed accounts

Seed account passwords are read from the required `DEFAULT_PASSWORD` environment variable.

- `requester` / `REQUESTER`
- `approver` / `APPROVER`
- `admin` / `ADMIN`

## Main routes

- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/change-password`
- `GET /api/auth/me`
- `GET /api/users`
- `POST /api/workflows`
- `GET /api/workflows/my`
- `GET /api/workflows/{id}`
- `GET /api/workflows/{id}/timeline`
- `POST /api/workflows/{id}/cancel`
- `POST /api/workflows/{id}/approval-chain`
- `GET /api/workflows/{id}/approval-chain`
- `GET /api/workflows/stats`
- `GET /api/workflow-templates`
- `POST /api/workflow-templates`
- `PUT /api/workflow-templates/{id}`
- `POST /api/workflow-templates/{id}/instantiate`
- `GET /api/workflows?page=0&size=20&status=PENDING&q=keyword`
- `GET /api/approvals/tasks`
- `POST /api/approvals/{id}/approve`
- `POST /api/approvals/{id}/reject`
- `GET /api/notifications/my`
- `GET /api/notifications/my/page?page=0&size=20`
- `GET /api/notifications/unread-count`
- WebSocket/STOMP endpoint `/ws`, topic `/topic/notifications/{userId}`
- `PATCH /api/notifications/{id}/read`
- `PATCH /api/notifications/read-all`
- `GET /api/audit-logs?workflowId={id}`
- `POST /api/ai/form-assistant`
- `POST /api/ai/on-call`
- `GET /api/ai/runbooks`
- `POST /api/attachments?workflowId={id}` (multipart field `file`)
- `GET /api/attachments/workflow/{workflowId}`
- `GET /api/attachments/{id}/content`
- `DELETE /api/attachments/{id}`

## AI provider

The AI service supports an OpenAI-compatible provider and validates its structured output. Configure:

```text
AI_PROVIDER_ENABLED=true
AI_PROVIDER_BASE_URL=https://api.openai.com/v1
AI_PROVIDER_API_KEY=...
AI_MODEL=gpt-4.1-mini
```

If the provider is disabled, unavailable or returns invalid output, the form assistant safely falls back to the local rule engine and reports `source: LOCAL_FALLBACK`. The on-call endpoint always reads live health data from Auth, Workflow and Notification/Audit services and remains read-only.

## Verification

```bash
mvn test
docker compose config --quiet
```

The automated suite starts every Spring application on a random port, validates Flyway migrations, authentication/error contracts, durable outbox persistence and AI role/fallback behavior.
