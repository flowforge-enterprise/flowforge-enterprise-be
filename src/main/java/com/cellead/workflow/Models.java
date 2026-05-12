package com.cellead.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

enum Role {
    REQUESTER,
    APPROVER,
    ADMIN
}

enum WorkflowStatus {
    PENDING,
    APPROVED,
    REJECTED
}

enum Priority {
    LOW,
    MEDIUM,
    HIGH
}

enum Decision {
    APPROVED,
    REJECTED
}

enum AuditAction {
    USER_LOGIN,
    WORKFLOW_SUBMITTED,
    WORKFLOW_APPROVED,
    WORKFLOW_REJECTED
}

enum NotificationChannel {
    EMAIL,
    SMS,
    WEBSOCKET
}

enum NotificationStatus {
    SENT,
    FAILED
}

@Entity
@Table(name = "users")
class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {
    }

    AppUser(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    Long getId() {
        return id;
    }

    String getUsername() {
        return username;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    Role getRole() {
        return role;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

@Entity
@Table(name = "workflow_requests")
class WorkflowRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "request_type", nullable = false)
    private String requestType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkflowStatus status = WorkflowStatus.PENDING;

    @ManyToOne(optional = false)
    @JoinColumn(name = "submitter_id")
    private AppUser submitter;

    @ManyToOne(optional = false)
    @JoinColumn(name = "approver_id")
    private AppUser approver;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WorkflowRequest() {
    }

    WorkflowRequest(String title, String description, String requestType, Priority priority, AppUser submitter, AppUser approver) {
        this.title = title;
        this.description = description;
        this.requestType = requestType;
        this.priority = priority;
        this.submitter = submitter;
        this.approver = approver;
    }

    void markApproved() {
        this.status = WorkflowStatus.APPROVED;
        this.updatedAt = Instant.now();
    }

    void markRejected() {
        this.status = WorkflowStatus.REJECTED;
        this.updatedAt = Instant.now();
    }

    Long getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    String getDescription() {
        return description;
    }

    String getRequestType() {
        return requestType;
    }

    Priority getPriority() {
        return priority;
    }

    WorkflowStatus getStatus() {
        return status;
    }

    AppUser getSubmitter() {
        return submitter;
    }

    AppUser getApprover() {
        return approver;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}

@Entity
@Table(name = "approval_records")
class ApprovalRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "workflow_id")
    private WorkflowRequest workflow;

    @ManyToOne(optional = false)
    @JoinColumn(name = "approver_id")
    private AppUser approver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Column(length = 2000)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ApprovalRecord() {
    }

    ApprovalRecord(WorkflowRequest workflow, AppUser approver, Decision decision, String comment) {
        this.workflow = workflow;
        this.approver = approver;
        this.decision = decision;
        this.comment = comment;
    }

    Long getId() {
        return id;
    }

    WorkflowRequest getWorkflow() {
        return workflow;
    }

    AppUser getApprover() {
        return approver;
    }

    Decision getDecision() {
        return decision;
    }

    String getComment() {
        return comment;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

@Entity
@Table(name = "audit_logs")
class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @ManyToOne(optional = false)
    @JoinColumn(name = "actor_id")
    private AppUser actor;

    @ManyToOne
    @JoinColumn(name = "workflow_id")
    private WorkflowRequest workflow;

    @Column(length = 2000)
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AuditLog() {
    }

    AuditLog(AuditAction action, AppUser actor, WorkflowRequest workflow, String details) {
        this.action = action;
        this.actor = actor;
        this.workflow = workflow;
        this.details = details;
    }

    Long getId() {
        return id;
    }

    AuditAction getAction() {
        return action;
    }

    AppUser getActor() {
        return actor;
    }

    WorkflowRequest getWorkflow() {
        return workflow;
    }

    String getDetails() {
        return details;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

@Entity
@Table(name = "notification_records")
class NotificationRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "workflow_id")
    private WorkflowRequest workflow;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recipient_id")
    private AppUser recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(nullable = false, length = 2000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected NotificationRecord() {
    }

    NotificationRecord(WorkflowRequest workflow, AppUser recipient, NotificationChannel channel, String message, NotificationStatus status) {
        this.workflow = workflow;
        this.recipient = recipient;
        this.channel = channel;
        this.message = message;
        this.status = status;
    }

    Long getId() {
        return id;
    }

    WorkflowRequest getWorkflow() {
        return workflow;
    }

    AppUser getRecipient() {
        return recipient;
    }

    NotificationChannel getChannel() {
        return channel;
    }

    String getMessage() {
        return message;
    }

    NotificationStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}

interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findFirstByRole(Role role);
}

interface WorkflowRequestRepository extends JpaRepository<WorkflowRequest, Long> {
    List<WorkflowRequest> findBySubmitterOrderByCreatedAtDesc(AppUser submitter);

    List<WorkflowRequest> findByApproverAndStatusOrderByCreatedAtDesc(AppUser approver, WorkflowStatus status);
}

interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, Long> {
    List<ApprovalRecord> findByWorkflowOrderByCreatedAtDesc(WorkflowRequest workflow);
}

interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByWorkflowOrderByCreatedAtDesc(WorkflowRequest workflow);
}

interface NotificationRecordRepository extends JpaRepository<NotificationRecord, Long> {
    List<NotificationRecord> findByRecipientOrderByCreatedAtDesc(AppUser recipient);
}
