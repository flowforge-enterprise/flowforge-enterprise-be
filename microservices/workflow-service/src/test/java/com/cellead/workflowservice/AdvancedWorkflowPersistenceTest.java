package com.cellead.workflowservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.cellead.platform.security.AuthenticatedUser;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AdvancedWorkflowPersistenceTest {
  @Autowired WorkflowRepository workflows;
  @Autowired ApprovalStepRepository steps;
  @Autowired WorkflowTemplateRepository templates;

  @Test
  void templatePersistsSlaAndDefaults() {
    WorkflowTemplate template =
        templates.save(
            new WorkflowTemplate(
                new TemplateRequest(
                    "Purchase Request",
                    "PURCHASE",
                    Priority.HIGH,
                    "Purchase approval",
                    "Include vendor quotation",
                    24,
                    true)));
    assertThat(templates.findByActiveTrueOrderByName()).extracting(t -> t.id).contains(template.id);
    assertThat(template.slaHours).isEqualTo(24);
  }

  @Test
  void orderedApprovalStepsCanRepresentAThreeLevelChain() {
    WorkflowRequest workflow = workflows.save(workflow());
    steps.save(
        new ApprovalStep(
            workflow.id,
            1,
            new DirectoryUser(2L, "team-lead", "APPROVER"),
            ApprovalStepStatus.APPROVED));
    steps.save(
        new ApprovalStep(
            workflow.id,
            2,
            new DirectoryUser(3L, "manager", "APPROVER"),
            ApprovalStepStatus.PENDING));
    steps.save(
        new ApprovalStep(
            workflow.id,
            3,
            new DirectoryUser(4L, "finance", "APPROVER"),
            ApprovalStepStatus.WAITING));
    assertThat(steps.findByWorkflowIdOrderByStepOrder(workflow.id))
        .extracting(s -> s.stepOrder)
        .containsExactly(1, 2, 3);
    assertThat(
            steps.findFirstByWorkflowIdAndStatusOrderByStepOrder(
                workflow.id, ApprovalStepStatus.PENDING))
        .isPresent();
  }

  @Test
  void overdueQueryOnlyReturnsPendingUnescalatedWorkflows() {
    WorkflowRequest overdue = workflow();
    overdue.dueAt = Instant.now().minusSeconds(60);
    workflows.save(overdue);
    WorkflowRequest future = workflow();
    future.title = "Future";
    future.dueAt = Instant.now().plusSeconds(3600);
    workflows.save(future);
    assertThat(
            workflows.findByStatusAndDueAtBeforeAndEscalatedAtIsNull(
                WorkflowStatus.PENDING, Instant.now()))
        .extracting(w -> w.title)
        .contains(overdue.title)
        .doesNotContain("Future");
  }

  private WorkflowRequest workflow() {
    return new WorkflowRequest(
        new CreateRequest(
            "Advanced request", "Details", "GENERAL", Priority.MEDIUM, 2L, "approver"),
        new AuthenticatedUser(1L, "requester", "REQUESTER"));
  }
}
