package com.cellead.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}

record LoginResponse(
        String token,
        UserSummary user
) {
}

record UserSummary(
        Long id,
        String username,
        Role role
) {
    static UserSummary from(AppUser user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getRole());
    }
}

record CreateWorkflowRequestDto(
        @NotBlank String title,
        @NotBlank String description,
        String requestType,
        @NotNull Priority priority,
        Long approverId
) {
    String normalizedRequestType() {
        return requestType == null || requestType.isBlank() ? "General Request" : requestType.trim();
    }
}

record WorkflowResponse(
        Long id,
        String title,
        String description,
        String requestType,
        Priority priority,
        WorkflowStatus status,
        UserSummary submitter,
        UserSummary approver,
        Instant createdAt,
        Instant updatedAt,
        List<ApprovalRecordResponse> approvalRecords
) {
    static WorkflowResponse from(WorkflowRequest workflow, List<ApprovalRecord> approvals) {
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getTitle(),
                workflow.getDescription(),
                workflow.getRequestType(),
                workflow.getPriority(),
                workflow.getStatus(),
                UserSummary.from(workflow.getSubmitter()),
                UserSummary.from(workflow.getApprover()),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt(),
                approvals.stream().map(ApprovalRecordResponse::from).toList()
        );
    }

    static WorkflowResponse summary(WorkflowRequest workflow) {
        return from(workflow, List.of());
    }
}

record ApprovalDecisionRequest(
        String comment
) {
}

record ApprovalRecordResponse(
        Long id,
        Long workflowId,
        UserSummary approver,
        Decision decision,
        String comment,
        Instant createdAt
) {
    static ApprovalRecordResponse from(ApprovalRecord approvalRecord) {
        return new ApprovalRecordResponse(
                approvalRecord.getId(),
                approvalRecord.getWorkflow().getId(),
                UserSummary.from(approvalRecord.getApprover()),
                approvalRecord.getDecision(),
                approvalRecord.getComment(),
                approvalRecord.getCreatedAt()
        );
    }
}

record AuditLogResponse(
        Long id,
        AuditAction action,
        Long actorId,
        String actorUsername,
        Long workflowId,
        String details,
        Instant createdAt
) {
    static AuditLogResponse from(AuditLog log) {
        WorkflowRequest workflow = log.getWorkflow();
        return new AuditLogResponse(
                log.getId(),
                log.getAction(),
                log.getActor().getId(),
                log.getActor().getUsername(),
                workflow == null ? null : workflow.getId(),
                log.getDetails(),
                log.getCreatedAt()
        );
    }
}

record NotificationRecordResponse(
        Long id,
        Long workflowId,
        Long recipientId,
        NotificationChannel channel,
        String message,
        NotificationStatus status,
        Instant createdAt
) {
    static NotificationRecordResponse from(NotificationRecord notificationRecord) {
        return new NotificationRecordResponse(
                notificationRecord.getId(),
                notificationRecord.getWorkflow().getId(),
                notificationRecord.getRecipient().getId(),
                notificationRecord.getChannel(),
                notificationRecord.getMessage(),
                notificationRecord.getStatus(),
                notificationRecord.getCreatedAt()
        );
    }
}

record ErrorResponse(
        String error,
        String message,
        Instant timestamp
) {
    static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, Instant.now());
    }
}
