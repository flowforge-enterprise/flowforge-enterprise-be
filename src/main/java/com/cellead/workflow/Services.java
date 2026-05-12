package com.cellead.workflow;

import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CurrentUserService {
    private final UserRepository users;

    CurrentUserService(UserRepository users) {
        this.users = users;
    }

    AppUser get(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}

@Service
@Transactional
class AuthService {
    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final JwtService jwtService;
    private final AuditService auditService;

    AuthService(AuthenticationManager authenticationManager, UserRepository users, JwtService jwtService, AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    LoginResponse login(LoginRequest request) {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        AppUser user = users.findByUsername(request.username())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        auditService.log(AuditAction.USER_LOGIN, user, null, "User logged in");
        return new LoginResponse(jwtService.generateToken(user), UserSummary.from(user));
    }
}

@Service
@Transactional
class AuditService {
    private final AuditLogRepository auditLogs;

    AuditService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    AuditLog log(AuditAction action, AppUser actor, WorkflowRequest workflow, String details) {
        return auditLogs.save(new AuditLog(action, actor, workflow, details));
    }

    @Transactional(readOnly = true)
    List<AuditLog> list(WorkflowRequest workflow) {
        if (workflow != null) {
            return auditLogs.findByWorkflowOrderByCreatedAtDesc(workflow);
        }
        return auditLogs.findAll();
    }
}

@Service
@Transactional
class NotificationService {
    private final NotificationRecordRepository notifications;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    NotificationService(NotificationRecordRepository notifications, org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate) {
        this.notifications = notifications;
        this.messagingTemplate = messagingTemplate;
    }

    NotificationRecord notify(AppUser recipient, WorkflowRequest workflow, String message) {
        NotificationStatus status = NotificationStatus.SENT;
        try {
            messagingTemplate.convertAndSend("/topic/notifications/" + recipient.getId(),
                    new NotificationRecordResponse(null, workflow.getId(), recipient.getId(), NotificationChannel.WEBSOCKET, message, status, null));
        } catch (RuntimeException ex) {
            status = NotificationStatus.FAILED;
        }
        return notifications.save(new NotificationRecord(workflow, recipient, NotificationChannel.WEBSOCKET, message, status));
    }

    @Transactional(readOnly = true)
    List<NotificationRecord> mine(AppUser recipient) {
        return notifications.findByRecipientOrderByCreatedAtDesc(recipient);
    }

    @Transactional(readOnly = true)
    List<NotificationRecord> all() {
        return notifications.findAll();
    }
}

@Service
@Transactional
class WorkflowService {
    private final WorkflowRequestRepository workflows;
    private final ApprovalRecordRepository approvals;
    private final UserRepository users;
    private final AuditService auditService;
    private final NotificationService notificationService;

    WorkflowService(
            WorkflowRequestRepository workflows,
            ApprovalRecordRepository approvals,
            UserRepository users,
            AuditService auditService,
            NotificationService notificationService
    ) {
        this.workflows = workflows;
        this.approvals = approvals;
        this.users = users;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    WorkflowRequest create(CreateWorkflowRequestDto dto, AppUser submitter) {
        if (submitter.getRole() != Role.REQUESTER) {
            throw new AccessDeniedException("Only Requester can submit workflow requests");
        }
        AppUser approver = resolveApprover(dto.approverId());
        WorkflowRequest workflow = workflows.save(new WorkflowRequest(
                dto.title().trim(),
                dto.description().trim(),
                dto.normalizedRequestType(),
                dto.priority(),
                submitter,
                approver
        ));
        auditService.log(AuditAction.WORKFLOW_SUBMITTED, submitter, workflow, "Workflow request submitted");
        notificationService.notify(approver, workflow, "New workflow request pending approval: " + workflow.getTitle());
        return workflow;
    }

    @Transactional(readOnly = true)
    List<WorkflowRequest> mine(AppUser submitter) {
        return workflows.findBySubmitterOrderByCreatedAtDesc(submitter);
    }

    @Transactional(readOnly = true)
    WorkflowRequest getForUser(Long id, AppUser user) {
        WorkflowRequest workflow = get(id);
        if (user.getRole() == Role.ADMIN
                || workflow.getSubmitter().getId().equals(user.getId())
                || workflow.getApprover().getId().equals(user.getId())) {
            return workflow;
        }
        throw new AccessDeniedException("You cannot access this workflow request");
    }

    @Transactional(readOnly = true)
    WorkflowRequest get(Long id) {
        return workflows.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow request not found"));
    }

    @Transactional(readOnly = true)
    List<ApprovalRecord> approvals(WorkflowRequest workflow) {
        return approvals.findByWorkflowOrderByCreatedAtDesc(workflow);
    }

    @Transactional(readOnly = true)
    List<WorkflowRequest> pendingTasks(AppUser approver) {
        if (approver.getRole() != Role.APPROVER) {
            throw new AccessDeniedException("Only Approver can view approval tasks");
        }
        return workflows.findByApproverAndStatusOrderByCreatedAtDesc(approver, WorkflowStatus.PENDING);
    }

    private AppUser resolveApprover(Long approverId) {
        if (approverId != null) {
            AppUser approver = users.findById(approverId)
                    .orElseThrow(() -> new ResourceNotFoundException("Approver not found"));
            if (approver.getRole() != Role.APPROVER) {
                throw new IllegalArgumentException("Selected user is not an approver");
            }
            return approver;
        }
        return users.findFirstByRole(Role.APPROVER)
                .orElseThrow(() -> new IllegalStateException("No approver is configured"));
    }
}

@Service
@Transactional
class ApprovalService {
    private final WorkflowRequestRepository workflows;
    private final ApprovalRecordRepository approvals;
    private final AuditService auditService;
    private final NotificationService notificationService;

    ApprovalService(
            WorkflowRequestRepository workflows,
            ApprovalRecordRepository approvals,
            AuditService auditService,
            NotificationService notificationService
    ) {
        this.workflows = workflows;
        this.approvals = approvals;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    ApprovalRecord approve(Long workflowId, AppUser approver, String comment) {
        return decide(workflowId, approver, Decision.APPROVED, comment);
    }

    ApprovalRecord reject(Long workflowId, AppUser approver, String comment) {
        return decide(workflowId, approver, Decision.REJECTED, comment);
    }

    private ApprovalRecord decide(Long workflowId, AppUser approver, Decision decision, String comment) {
        if (approver.getRole() != Role.APPROVER) {
            throw new AccessDeniedException("Only Approver can approve or reject workflow requests");
        }
        WorkflowRequest workflow = workflows.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow request not found"));
        if (!workflow.getApprover().getId().equals(approver.getId())) {
            throw new AccessDeniedException("This task is not assigned to you");
        }
        if (workflow.getStatus() != WorkflowStatus.PENDING) {
            throw new IllegalStateException("Only Pending workflow requests can be approved or rejected");
        }

        if (decision == Decision.APPROVED) {
            workflow.markApproved();
        } else {
            workflow.markRejected();
        }
        workflows.save(workflow);

        ApprovalRecord record = approvals.save(new ApprovalRecord(workflow, approver, decision, comment));
        auditService.log(
                decision == Decision.APPROVED ? AuditAction.WORKFLOW_APPROVED : AuditAction.WORKFLOW_REJECTED,
                approver,
                workflow,
                decision == Decision.APPROVED ? "Workflow request approved" : "Workflow request rejected"
        );
        notificationService.notify(workflow.getSubmitter(), workflow,
                "Workflow request " + workflow.getTitle() + " was " + decision.name().toLowerCase());
        return record;
    }
}
