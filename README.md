# Workflow MVP Backend

Spring Boot 3 backend for the MVP enterprise approval workflow.

## MVP Coverage

- JWT login and token validation
- Requester, Approver, and Admin roles
- RBAC-protected REST APIs
- Submit workflow requests with `Pending` status
- View own requests and request details
- Approver task list, approve, and reject
- Prevent repeated approval after `Approved` or `Rejected`
- Audit logs for login, submit, approve, and reject
- WebSocket in-app notification records for submit and approval results
- H2 local database by default, MySQL profile for Docker Compose

## Seed Accounts

All seed accounts use password `password123`.

- `requester` / `REQUESTER`
- `approver` / `APPROVER`
- `admin` / `ADMIN`

## Run Locally

```bash
mvn package -DskipTests
java -jar target/workflow-mvp-backend-0.0.1-SNAPSHOT.jar
```

The app starts on `http://localhost:8080`.

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- H2 console: `http://localhost:8080/h2-console`

On Windows, `mvn spring-boot:run` can fail when the project path contains non-ASCII characters because the Spring Boot Maven plugin uses a classpath argfile. Running the packaged jar avoids that issue.

## Run With Docker Compose

```bash
docker compose up --build
```

## Test And Coverage

```bash
mvn test
```


JaCoCo coverage report:

```text
target/site/jacoco/index.html
```

## API Summary

- `POST /api/auth/login`
- `GET /api/auth/me`
- `GET /api/users/me`
- `GET /api/users` admin only
- `POST /api/workflows` requester only
- `GET /api/workflows/my` requester only
- `GET /api/workflows/{id}`
- `GET /api/approvals/tasks` approver only
- `POST /api/approvals/{workflowId}/approve` approver only
- `POST /api/approvals/{workflowId}/reject` approver only
- `GET /api/audit-logs?workflowId={id}`
- `GET /api/notifications/my`
- `GET /api/notifications` admin only

WebSocket endpoint: `/ws`

Subscribe to: `/topic/notifications/{recipientId}`

## Example Login

```json
{
  "username": "requester",
  "password": "password123"
}
```


Use the returned token as:

```text
Authorization: Bearer <token>
```

