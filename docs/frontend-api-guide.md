# 后端接口对接说明

本文档面向前端同事，用于 MVP 阶段接口联调。

## 基础信息

- Base URL: `http://localhost:8080`
- API 文档: `http://localhost:8080/swagger-ui.html`
- 鉴权方式: JWT Bearer Token
- WebSocket Endpoint: `/ws`
- 通知订阅地址: `/topic/notifications/{userId}`

默认测试账号密码均为 `password123`：

| 用户名 | 角色 |
| --- | --- |
| `requester` | `REQUESTER` |
| `approver` | `APPROVER` |
| `admin` | `ADMIN` |

## 通用约定

除登录接口外，所有接口都需要请求头：

```http
Authorization: Bearer <token>
```

常见状态码：

| 状态码 | 说明 |
| --- | --- |
| `200` | 请求成功 |
| `400` | 请求参数错误 |
| `401` | 用户名或密码错误 |
| `403` | 未登录、Token 无效或无权限 |
| `404` | 资源不存在 |

错误响应格式：

```json
{
  "error": "BAD_REQUEST",
  "message": "错误说明",
  "timestamp": "2026-05-13T00:00:00Z"
}
```

## 1. 登录

### `POST /api/auth/login`

请求：

```json
{
  "username": "requester",
  "password": "password123"
}
```

响应：

```json
{
  "token": "jwt-token",
  "user": {
    "id": 1,
    "username": "requester",
    "role": "REQUESTER"
  }
}
```

前端需要保存 `token`，后续接口放入 `Authorization` 请求头。

## 2. 当前用户

### `GET /api/auth/me`

响应：

```json
{
  "id": 1,
  "username": "requester",
  "role": "REQUESTER"
}
```

也可以使用：

### `GET /api/users/me`

返回结构相同。

## 3. 提交申请

仅 `REQUESTER` 可调用。

### `POST /api/workflows`

请求：

```json
{
  "title": "Laptop access request",
  "description": "Need laptop access for project work",
  "requestType": "General Request",
  "priority": "HIGH"
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `title` | 是 | 申请标题 |
| `description` | 是 | 申请描述 |
| `requestType` | 否 | 不传时默认为 `General Request` |
| `priority` | 是 | `LOW` / `MEDIUM` / `HIGH` |
| `approverId` | 否 | 不传时自动分配第一个 Approver |

响应重点字段：

```json
{
  "id": 1,
  "title": "Laptop access request",
  "requestType": "General Request",
  "priority": "HIGH",
  "status": "PENDING",
  "submitter": {
    "id": 1,
    "username": "requester",
    "role": "REQUESTER"
  },
  "approver": {
    "id": 2,
    "username": "approver",
    "role": "APPROVER"
  }
}
```

## 4. 我的申请

仅 `REQUESTER` 可调用。

### `GET /api/workflows/my`

响应：

```json
[
  {
    "id": 1,
    "title": "Laptop access request",
    "requestType": "General Request",
    "priority": "HIGH",
    "status": "PENDING",
    "createdAt": "2026-05-13T00:00:00Z",
    "updatedAt": "2026-05-13T00:00:00Z"
  }
]
```

状态值：

| 状态 | 说明 |
| --- | --- |
| `PENDING` | 待审批 |
| `APPROVED` | 已批准 |
| `REJECTED` | 已拒绝 |

## 5. 申请详情

Requester、分配的 Approver、Admin 可查看。

### `GET /api/workflows/{id}`

响应中包含申请详情和审批记录：

```json
{
  "id": 1,
  "title": "Laptop access request",
  "description": "Need laptop access for project work",
  "status": "APPROVED",
  "approvalRecords": [
    {
      "id": 1,
      "workflowId": 1,
      "decision": "APPROVED",
      "comment": "Approved for MVP demo",
      "createdAt": "2026-05-13T00:00:00Z"
    }
  ]
}
```

## 6. 待审批任务

仅 `APPROVER` 可调用。

### `GET /api/approvals/tasks`

返回当前审批人的 `PENDING` 任务列表。

```json
[
  {
    "id": 1,
    "title": "Laptop access request",
    "priority": "HIGH",
    "status": "PENDING",
    "submitter": {
      "username": "requester"
    }
  }
]
```

## 7. 批准申请

仅 `APPROVER` 可调用，且只能审批分配给自己的 `PENDING` 申请。

### `POST /api/approvals/{workflowId}/approve`

请求：

```json
{
  "comment": "Approved for MVP demo"
}
```

响应：

```json
{
  "id": 1,
  "workflowId": 1,
  "decision": "APPROVED",
  "comment": "Approved for MVP demo"
}
```

## 8. 拒绝申请

仅 `APPROVER` 可调用。

### `POST /api/approvals/{workflowId}/reject`

请求：

```json
{
  "comment": "Rejected due to incomplete information"
}
```

响应：

```json
{
  "id": 1,
  "workflowId": 1,
  "decision": "REJECTED",
  "comment": "Rejected due to incomplete information"
}
```

## 9. 通知记录

### `GET /api/notifications/my`

返回当前登录用户收到的通知。

```json
[
  {
    "id": 1,
    "workflowId": 1,
    "recipientId": 1,
    "channel": "WEBSOCKET",
    "message": "Workflow request Laptop access request was approved",
    "status": "SENT",
    "createdAt": "2026-05-13T00:00:00Z"
  }
]
```

### `GET /api/notifications`

仅 `ADMIN` 可调用，返回全部通知记录。

## 10. 审计日志

### `GET /api/audit-logs?workflowId={id}`

Requester、分配的 Approver、Admin 可查看某个申请的审计日志。

```json
[
  {
    "id": 1,
    "action": "WORKFLOW_SUBMITTED",
    "actorUsername": "requester",
    "workflowId": 1,
    "details": "Workflow request submitted",
    "createdAt": "2026-05-13T00:00:00Z"
  }
]
```

审计事件：

| 事件 | 说明 |
| --- | --- |
| `USER_LOGIN` | 用户登录 |
| `WORKFLOW_SUBMITTED` | 提交申请 |
| `WORKFLOW_APPROVED` | 批准申请 |
| `WORKFLOW_REJECTED` | 拒绝申请 |

### `GET /api/audit-logs`

仅 `ADMIN` 可调用，返回全部审计日志。

## 11. 推荐联调流程

1. `requester` 登录，保存 token。
2. `requester` 调用 `POST /api/workflows` 提交申请。
3. `requester` 调用 `GET /api/workflows/my` 确认状态为 `PENDING`。
4. `approver` 登录，保存新的 token。
5. `approver` 调用 `GET /api/approvals/tasks` 查看待审批任务。
6. `approver` 调用 approve 或 reject。
7. `requester` 再次查看申请详情，确认状态变为 `APPROVED` 或 `REJECTED`。
8. 查看 `GET /api/notifications/my` 和 `GET /api/audit-logs?workflowId={id}`。

## 12. 前端页面建议

| 页面 | 角色 | 主要接口 |
| --- | --- | --- |
| Login | 全部 | `POST /api/auth/login` |
| Dashboard | 全部 | `GET /api/auth/me` |
| Submit Request | Requester | `POST /api/workflows` |
| My Requests | Requester | `GET /api/workflows/my` |
| Request Detail | Requester / Approver / Admin | `GET /api/workflows/{id}` |
| Approval Tasks | Approver | `GET /api/approvals/tasks` |
| Notification Center | 全部 | `GET /api/notifications/my` |
