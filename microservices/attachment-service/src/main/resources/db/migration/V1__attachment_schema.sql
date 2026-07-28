CREATE TABLE attachments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    uploader_id BIGINT NOT NULL,
    uploader_username VARCHAR(100) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(100) NOT NULL UNIQUE,
    content_type VARCHAR(150) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_attachment_workflow ON attachments(workflow_id, created_at);
