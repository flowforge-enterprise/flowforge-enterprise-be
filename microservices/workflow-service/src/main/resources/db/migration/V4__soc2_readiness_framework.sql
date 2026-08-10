UPDATE regulatory_frameworks
SET code = 'SOC2',
    name = 'SOC 2 Readiness Framework',
    description = 'Operational evidence mapped to SOC 2 Trust Services Criteria for readiness tracking; this does not represent an independent SOC 2 attestation.'
WHERE code = 'BASIC-SECURITY';

UPDATE regulatory_controls
SET control_code = 'CC6.1',
    title = 'Logical access controls',
    description = 'Logical access is authorized, authenticated, and restricted according to role.'
WHERE framework_id = (SELECT id FROM regulatory_frameworks WHERE code = 'SOC2')
  AND control_code = 'AC-01';

UPDATE regulatory_controls
SET control_code = 'CC7.1',
    title = 'Security monitoring and vulnerability detection',
    description = 'Application security scans provide evidence of vulnerability detection and monitoring.'
WHERE framework_id = (SELECT id FROM regulatory_frameworks WHERE code = 'SOC2')
  AND control_code = 'AS-01';

UPDATE regulatory_controls
SET control_code = 'CC7.2',
    title = 'Infrastructure anomaly monitoring',
    description = 'Infrastructure-as-code scans provide evidence of configuration risk monitoring.'
WHERE framework_id = (SELECT id FROM regulatory_frameworks WHERE code = 'SOC2')
  AND control_code = 'IA-01';

UPDATE regulatory_controls
SET control_code = 'CC4.1',
    title = 'Control monitoring',
    description = 'Audit and workflow events are retained to support ongoing control monitoring.'
WHERE framework_id = (SELECT id FROM regulatory_frameworks WHERE code = 'SOC2')
  AND control_code = 'AU-01';

UPDATE regulatory_controls
SET control_code = 'CC6.6',
    title = 'System boundary protection',
    description = 'Environment and workload isolation restricts access across system boundaries.'
WHERE framework_id = (SELECT id FROM regulatory_frameworks WHERE code = 'SOC2')
  AND control_code = 'DP-01';

UPDATE regulatory_controls
SET control_code = 'CC8.1',
    title = 'Change management',
    description = 'Build, test, review, security scan, and deployment evidence is retained for system changes.'
WHERE framework_id = (SELECT id FROM regulatory_frameworks WHERE code = 'SOC2')
  AND control_code = 'CI-01';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'CC1.1', 'Control environment',
       'Management responsibilities and security control ownership are documented.',
       'NOT_STARTED', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'SOC2';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'CC2.1', 'Information and communication',
       'Security responsibilities and relevant control information are communicated.',
       'NOT_STARTED', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'SOC2';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'CC3.1', 'Risk assessment',
       'Security, availability, and confidentiality risks are identified and assessed.',
       'NOT_STARTED', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'SOC2';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'CC5.1', 'Control activities',
       'Policies and procedures translate risk responses into repeatable control activities.',
       'NOT_STARTED', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'SOC2';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'CC7.4', 'Incident response',
       'Security incidents are assessed, contained, remediated, and reviewed.',
       'NOT_STARTED', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'SOC2';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'A1.1', 'Availability commitments',
       'Availability commitments, monitoring, capacity, backup, and recovery objectives are tracked.',
       'NOT_STARTED', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'SOC2';

INSERT INTO regulatory_controls
    (framework_id, control_code, title, description, status, updated_at)
SELECT id, 'C1.1', 'Confidentiality commitments',
       'Confidential information is identified, protected, retained, and disposed of appropriately.',
       'NOT_STARTED', CURRENT_TIMESTAMP
FROM regulatory_frameworks WHERE code = 'SOC2';
