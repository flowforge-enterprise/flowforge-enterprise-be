ALTER TABLE workflow_requests ADD COLUMN due_at TIMESTAMP;
ALTER TABLE workflow_requests ADD COLUMN escalated_at TIMESTAMP;

CREATE TABLE approval_steps (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    step_order INT NOT NULL,
    approver_id BIGINT NOT NULL,
    approver_username VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    comment VARCHAR(2000),
    decided_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_approval_step UNIQUE(workflow_id, step_order)
);
CREATE INDEX idx_approval_step_current ON approval_steps(workflow_id, status, step_order);

CREATE TABLE workflow_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL UNIQUE,
    request_type VARCHAR(100) NOT NULL,
    default_priority VARCHAR(20) NOT NULL,
    default_title VARCHAR(200),
    description_hint VARCHAR(1000),
    sla_hours INT NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
