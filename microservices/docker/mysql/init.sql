CREATE DATABASE IF NOT EXISTS auth_db;
CREATE DATABASE IF NOT EXISTS workflow_db;
CREATE DATABASE IF NOT EXISTS notification_audit_db;
CREATE DATABASE IF NOT EXISTS attachment_db;

CREATE USER IF NOT EXISTS 'auth_user'@'%' IDENTIFIED BY 'auth_password';
CREATE USER IF NOT EXISTS 'workflow_user'@'%' IDENTIFIED BY 'workflow_password';
CREATE USER IF NOT EXISTS 'audit_user'@'%' IDENTIFIED BY 'audit_password';
CREATE USER IF NOT EXISTS 'attachment_user'@'%' IDENTIFIED BY 'attachment_password';

GRANT ALL PRIVILEGES ON auth_db.* TO 'auth_user'@'%';
GRANT ALL PRIVILEGES ON workflow_db.* TO 'workflow_user'@'%';
GRANT ALL PRIVILEGES ON notification_audit_db.* TO 'audit_user'@'%';
GRANT ALL PRIVILEGES ON attachment_db.* TO 'attachment_user'@'%';
FLUSH PRIVILEGES;
