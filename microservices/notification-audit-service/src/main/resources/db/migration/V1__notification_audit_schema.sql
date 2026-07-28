CREATE TABLE notification_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    message VARCHAR(2000) NOT NULL,
    channel VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    read_flag BOOLEAN NOT NULL,
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_notification_recipient ON notification_records(recipient_id, created_at);

CREATE TABLE audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    action VARCHAR(100) NOT NULL,
    actor_id BIGINT NOT NULL,
    actor_username VARCHAR(100) NOT NULL,
    workflow_id BIGINT,
    details VARCHAR(2000),
    correlation_id VARCHAR(100),
    event_id VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_audit_workflow ON audit_logs(workflow_id, created_at);
