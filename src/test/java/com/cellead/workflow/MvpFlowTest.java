package com.cellead.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

@SpringBootTest
class MvpFlowTest {
    @Autowired
    private UserRepository users;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private NotificationRecordRepository notificationRecords;

    @Test
    void requesterCanSubmitAndApproverCanApproveOnce() {
        AppUser requester = users.findByUsername("requester").orElseThrow();
        AppUser approver = users.findByUsername("approver").orElseThrow();

        WorkflowRequest workflow = workflowService.create(
                new CreateWorkflowRequestDto("Laptop access", "Need access for project work", "General Request", Priority.HIGH, approver.getId()),
                requester
        );

        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.PENDING);
        assertThat(workflowService.pendingTasks(approver)).extracting(WorkflowRequest::getId).contains(workflow.getId());

        ApprovalRecord approval = approvalService.approve(workflow.getId(), approver, "Approved for MVP test");

        assertThat(approval.getDecision()).isEqualTo(Decision.APPROVED);
        assertThat(workflowService.get(workflow.getId()).getStatus()).isEqualTo(WorkflowStatus.APPROVED);
        assertThat(auditLogs.findAll()).extracting(AuditLog::getAction)
                .contains(AuditAction.WORKFLOW_SUBMITTED, AuditAction.WORKFLOW_APPROVED);
        assertThat(notificationRecords.findAll()).hasSizeGreaterThanOrEqualTo(2);

        Long workflowId = workflow.getId();
        assertThatThrownBy(() -> approvalService.reject(workflowId, approver, "Too late"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requesterCannotApproveWorkflow() {
        AppUser requester = users.findByUsername("requester").orElseThrow();
        assertThatThrownBy(() -> approvalService.approve(1L, requester, "Not allowed"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
