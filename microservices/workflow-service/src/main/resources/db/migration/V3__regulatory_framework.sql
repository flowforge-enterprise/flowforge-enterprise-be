CREATE TABLE regulatory_frameworks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE regulatory_controls (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    framework_id BIGINT NOT NULL,
    control_code VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    status VARCHAR(30) NOT NULL,
    evidence_note VARCHAR(2000),
    updated_by VARCHAR(100),
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_regulatory_control_framework
        FOREIGN KEY (framework_id) REFERENCES regulatory_frameworks(id),
    CONSTRAINT uk_regulatory_control UNIQUE (framework_id, control_code)
);

CREATE INDEX idx_regulatory_control_framework ON regulatory_controls(framework_id, control_code);

INSERT INTO regulatory_frameworks (code, name, description, created_at)
VALUES ('BASIC-SECURITY', 'Basic Security Framework',
        'A lightweight baseline for tracking the platform security controls.', CURRENT_TIMESTAMP);

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'AC-01', 'Access control', 'JWT authentication and role-based authorization are enabled.',
       'IN_PROGRESS', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'BASIC-SECURITY';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'AS-01', 'Application security scan', 'OWASP application security checks are tracked.',
       'NOT_STARTED', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'BASIC-SECURITY';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'IA-01', 'Infrastructure scan', 'Checkov IaC scan results are tracked.',
       'NOT_STARTED', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'BASIC-SECURITY';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'AU-01', 'Audit trail', 'Security and workflow events are retained for review.',
       'IN_PROGRESS', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'BASIC-SECURITY';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'DP-01', 'Deployment isolation', 'Test and production workloads use isolated nodes.',
       'IN_PROGRESS', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'BASIC-SECURITY';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'CI-01', 'Continuous integration', 'Build, unit test, and quality gate results are tracked.',
       'NOT_STARTED', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'BASIC-SECURITY';
