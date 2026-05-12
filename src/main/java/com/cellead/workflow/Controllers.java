package com.cellead.workflow;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthService authService;
    private final CurrentUserService currentUserService;

    AuthController(AuthService authService, CurrentUserService currentUserService) {
        this.authService = authService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    UserSummary me(@AuthenticationPrincipal UserDetails principal) {
        return UserSummary.from(currentUserService.get(principal.getUsername()));
    }
}

@RestController
@RequestMapping("/api/users")
class UserController {
    private final UserRepository users;
    private final CurrentUserService currentUserService;

    UserController(UserRepository users, CurrentUserService currentUserService) {
        this.users = users;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    UserSummary me(@AuthenticationPrincipal UserDetails principal) {
        return UserSummary.from(currentUserService.get(principal.getUsername()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    List<UserSummary> list() {
        return users.findAll().stream().map(UserSummary::from).toList();
    }
}

@RestController
@RequestMapping("/api/workflows")
class WorkflowController {
    private final WorkflowService workflowService;
    private final CurrentUserService currentUserService;

    WorkflowController(WorkflowService workflowService, CurrentUserService currentUserService) {
        this.workflowService = workflowService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @PreAuthorize("hasRole('REQUESTER')")
    WorkflowResponse submit(@Valid @RequestBody CreateWorkflowRequestDto request, @AuthenticationPrincipal UserDetails principal) {
        AppUser user = currentUserService.get(principal.getUsername());
        return WorkflowResponse.summary(workflowService.create(request, user));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('REQUESTER')")
    List<WorkflowResponse> mine(@AuthenticationPrincipal UserDetails principal) {
        AppUser user = currentUserService.get(principal.getUsername());
        return workflowService.mine(user).stream().map(WorkflowResponse::summary).toList();
    }

    @GetMapping("/{id}")
    WorkflowResponse detail(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        AppUser user = currentUserService.get(principal.getUsername());
        WorkflowRequest workflow = workflowService.getForUser(id, user);
        return WorkflowResponse.from(workflow, workflowService.approvals(workflow));
    }
}

@RestController
@RequestMapping("/api/approvals")
class ApprovalController {
    private final WorkflowService workflowService;
    private final ApprovalService approvalService;
    private final CurrentUserService currentUserService;

    ApprovalController(WorkflowService workflowService, ApprovalService approvalService, CurrentUserService currentUserService) {
        this.workflowService = workflowService;
        this.approvalService = approvalService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasRole('APPROVER')")
    List<WorkflowResponse> tasks(@AuthenticationPrincipal UserDetails principal) {
        AppUser user = currentUserService.get(principal.getUsername());
        return workflowService.pendingTasks(user).stream().map(WorkflowResponse::summary).toList();
    }

    @PostMapping("/{workflowId}/approve")
    @PreAuthorize("hasRole('APPROVER')")
    ApprovalRecordResponse approve(
            @PathVariable Long workflowId,
            @RequestBody(required = false) ApprovalDecisionRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        AppUser user = currentUserService.get(principal.getUsername());
        String comment = request == null ? null : request.comment();
        return ApprovalRecordResponse.from(approvalService.approve(workflowId, user, comment));
    }

    @PostMapping("/{workflowId}/reject")
    @PreAuthorize("hasRole('APPROVER')")
    ApprovalRecordResponse reject(
            @PathVariable Long workflowId,
            @RequestBody(required = false) ApprovalDecisionRequest request,
            @AuthenticationPrincipal UserDetails principal
    ) {
        AppUser user = currentUserService.get(principal.getUsername());
        String comment = request == null ? null : request.comment();
        return ApprovalRecordResponse.from(approvalService.reject(workflowId, user, comment));
    }
}

@RestController
@RequestMapping("/api/audit-logs")
class AuditLogController {
    private final AuditService auditService;
    private final WorkflowService workflowService;
    private final CurrentUserService currentUserService;

    AuditLogController(AuditService auditService, WorkflowService workflowService, CurrentUserService currentUserService) {
        this.auditService = auditService;
        this.workflowService = workflowService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    List<AuditLogResponse> list(@RequestParam(required = false) Long workflowId, @AuthenticationPrincipal UserDetails principal) {
        AppUser user = currentUserService.get(principal.getUsername());
        WorkflowRequest workflow = null;
        if (workflowId != null) {
            workflow = workflowService.getForUser(workflowId, user);
        } else if (user.getRole() != Role.ADMIN) {
            throw new org.springframework.security.access.AccessDeniedException("Only Admin can list all audit logs");
        }
        return auditService.list(workflow).stream().map(AuditLogResponse::from).toList();
    }
}

@RestController
@RequestMapping("/api/notifications")
class NotificationController {
    private final NotificationService notificationService;
    private final CurrentUserService currentUserService;

    NotificationController(NotificationService notificationService, CurrentUserService currentUserService) {
        this.notificationService = notificationService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/my")
    List<NotificationRecordResponse> mine(@AuthenticationPrincipal UserDetails principal) {
        AppUser user = currentUserService.get(principal.getUsername());
        return notificationService.mine(user).stream().map(NotificationRecordResponse::from).toList();
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    List<NotificationRecordResponse> list() {
        return notificationService.all().stream().map(NotificationRecordResponse::from).toList();
    }
}
