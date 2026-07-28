CREATE TABLE workflow_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    request_type VARCHAR(100) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    submitter_id BIGINT NOT NULL,
    submitter_username VARCHAR(100) NOT NULL,
    approver_id BIGINT NOT NULL,
    approver_username VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT
);
CREATE INDEX idx_workflow_submitter ON workflow_requests(submitter_id, created_at);
CREATE INDEX idx_workflow_approver_status ON workflow_requests(approver_id, status, created_at);

CREATE TABLE approval_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    approver_id BIGINT NOT NULL,
    approver_username VARCHAR(100) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    comment VARCHAR(2000),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_approval_workflow ON approval_records(workflow_id, created_at);

CREATE TABLE outbox_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    workflow_id BIGINT,
    actor_id BIGINT NOT NULL,
    actor_username VARCHAR(100) NOT NULL,
    recipient_id BIGINT,
    message VARCHAR(2000),
    occurred_at TIMESTAMP NOT NULL,
    correlation_id VARCHAR(100),
    event_id VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    last_error VARCHAR(1000)
);
CREATE INDEX idx_outbox_retry ON outbox_events(status, next_attempt_at);
