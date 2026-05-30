package com.cellead.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
class ServiceBranchTest {
    @Autowired
    private UserRepository users;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ApprovalRecordRepository approvals;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void rejectFlowRecordsRejectedStateAuditAndNotification() {
        AppUser requester = users.findByUsername("requester").orElseThrow();
        AppUser approver = users.findByUsername("approver").orElseThrow();
        WorkflowRequest workflow = workflowService.create(new CreateWorkflowRequestDto(
                "Reject branch",
                "Exercise rejection branch",
                "General Request",
                Priority.MEDIUM,
                approver.getId()
        ), requester);

        ApprovalRecord rejection = approvalService.reject(workflow.getId(), approver, "Insufficient details");

        assertThat(rejection.getDecision()).isEqualTo(Decision.REJECTED);
        assertThat(workflowService.get(workflow.getId()).getStatus()).isEqualTo(WorkflowStatus.REJECTED);
        assertThat(approvals.findByWorkflowOrderByCreatedAtDesc(workflow)).hasSize(1);
        assertThat(auditService.list(workflow)).extracting(AuditLog::getAction)
                .contains(AuditAction.WORKFLOW_SUBMITTED, AuditAction.WORKFLOW_REJECTED);
        assertThat(notificationService.mine(requester)).extracting(NotificationRecord::getWorkflow)
                .extracting(WorkflowRequest::getId)
                .contains(workflow.getId());
    }

    @Test
    void workflowServiceRejectsInvalidSubmitterApproverAndMissingResources() {
        AppUser requester = users.findByUsername("requester").orElseThrow();
        AppUser approver = users.findByUsername("approver").orElseThrow();
        AppUser admin = users.findByUsername("admin").orElseThrow();

        assertThatThrownBy(() -> workflowService.create(new CreateWorkflowRequestDto(
                "Invalid submitter",
                "Approver cannot submit",
                null,
                Priority.LOW,
                approver.getId()
        ), approver)).isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> workflowService.create(new CreateWorkflowRequestDto(
                "Invalid approver",
                "Admin is not an approver",
                null,
                Priority.LOW,
                admin.getId()
        ), requester)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> workflowService.create(new CreateWorkflowRequestDto(
                "Missing approver",
                "Approver id does not exist",
                null,
                Priority.LOW,
                999_999L
        ), requester)).isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> workflowService.get(999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void workflowAccessAndTaskGuardsAreEnforced() {
        AppUser requester = users.findByUsername("requester").orElseThrow();
        AppUser approver = users.findByUsername("approver").orElseThrow();
        AppUser outsider = users.save(new AppUser(unique("outsider"), passwordEncoder.encode(Constants.DEFAULT_PASSWORD), Role.REQUESTER));

        WorkflowRequest workflow = workflowService.create(new CreateWorkflowRequestDto(
                "Access guard",
                "Only submitter, approver, or admin can read",
                "General Request",
                Priority.HIGH,
                approver.getId()
        ), requester);

        assertThat(workflowService.getForUser(workflow.getId(), requester).getId()).isEqualTo(workflow.getId());
        assertThat(workflowService.getForUser(workflow.getId(), approver).getId()).isEqualTo(workflow.getId());
        assertThatThrownBy(() -> workflowService.getForUser(workflow.getId(), outsider))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> workflowService.pendingTasks(requester))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approvalServiceRejectsWrongAssigneeNonPendingAndMissingWorkflow() {
        AppUser requester = users.findByUsername("requester").orElseThrow();
        AppUser approver = users.findByUsername("approver").orElseThrow();
        AppUser otherApprover = users.save(new AppUser(unique("approver"), passwordEncoder.encode(Constants.DEFAULT_PASSWORD), Role.APPROVER));

        WorkflowRequest workflow = workflowService.create(new CreateWorkflowRequestDto(
                "Approval guard",
                "Only assigned approver can decide",
                "General Request",
                Priority.HIGH,
                approver.getId()
        ), requester);

        assertThatThrownBy(() -> approvalService.approve(workflow.getId(), otherApprover, "Not assigned"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> approvalService.approve(999_999L, approver, "Missing"))
                .isInstanceOf(ResourceNotFoundException.class);

        approvalService.approve(workflow.getId(), approver, "Approved once");

        assertThatThrownBy(() -> approvalService.approve(workflow.getId(), approver, "Approved twice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void auditAndNotificationListMethodsReturnExpectedScopes() {
        AppUser requester = users.findByUsername("requester").orElseThrow();
        AppUser approver = users.findByUsername("approver").orElseThrow();
        WorkflowRequest workflow = workflowService.create(new CreateWorkflowRequestDto(
                "List branch",
                "Exercise list methods",
                null,
                Priority.LOW,
                null
        ), requester);

        List<AuditLog> allLogs = auditService.list(null);
        List<NotificationRecord> allNotifications = notificationService.all();

        assertThat(workflow.getApprover().getId()).isEqualTo(approver.getId());
        assertThat(allLogs).isNotEmpty();
        assertThat(allNotifications).isNotEmpty();
    }

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime();
    }
}
