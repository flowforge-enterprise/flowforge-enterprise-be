package com.cellead.workflowservice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkflowQueryService {
    private final WorkflowRepository workflows;
    private final ApprovalRepository approvals;

    WorkflowQueryService(WorkflowRepository workflows, ApprovalRepository approvals) {
        this.workflows = workflows;
        this.approvals = approvals;
    }

    @Transactional(readOnly = true)
    List<TimelineEvent> timeline(WorkflowRequest workflow) {
        ArrayList<TimelineEvent> events = new ArrayList<>();
        events.add(new TimelineEvent("SUBMITTED", workflow.submitterUsername,
                "Workflow submitted", workflow.createdAt));
        approvals.findByWorkflowIdOrderByCreatedAtDesc(workflow.id).forEach(approval ->
                events.add(new TimelineEvent(approval.decision.name(), approval.approverUsername,
                        approval.comment, approval.createdAt)));
        if (workflow.status == WorkflowStatus.CANCELLED) {
            events.add(new TimelineEvent("CANCELLED", workflow.submitterUsername,
                    "Workflow cancelled", workflow.updatedAt));
        }
        events.sort(Comparator.comparing(TimelineEvent::occurredAt));
        return List.copyOf(events);
    }

    @Transactional(readOnly = true)
    WorkflowStats statistics() {
        return new WorkflowStats(workflows.count(), workflows.countByStatus(WorkflowStatus.PENDING),
                workflows.countByStatus(WorkflowStatus.APPROVED), workflows.countByStatus(WorkflowStatus.REJECTED),
                workflows.countByStatus(WorkflowStatus.CANCELLED));
    }
}
