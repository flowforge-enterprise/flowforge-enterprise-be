package com.cellead.workflowservice;

import com.cellead.platform.security.AuthenticatedUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

enum ApprovalStepStatus {
  WAITING,
  PENDING,
  APPROVED,
  REJECTED,
  CANCELLED
}

@Entity
@Table(
    name = "approval_steps",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_approval_step",
            columnNames = {"workflow_id", "step_order"}))
class ApprovalStep {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false)
  Long workflowId;

  @Column(nullable = false)
  int stepOrder;

  @Column(nullable = false)
  Long approverId;

  @Column(nullable = false)
  String approverUsername;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  ApprovalStepStatus status;

  @Column(length = 2000)
  String comment;

  Instant decidedAt;

  @Column(nullable = false)
  Instant createdAt = Instant.now();

  protected ApprovalStep() {}

  ApprovalStep(Long workflowId, int order, DirectoryUser user, ApprovalStepStatus status) {
    this.workflowId = workflowId;
    this.stepOrder = order;
    this.approverId = user.id();
    this.approverUsername = user.username();
    this.status = status;
  }
}

interface ApprovalStepRepository extends JpaRepository<ApprovalStep, Long> {
  List<ApprovalStep> findByWorkflowIdOrderByStepOrder(Long workflowId);

  java.util.Optional<ApprovalStep> findFirstByWorkflowIdAndStatusOrderByStepOrder(
      Long workflowId, ApprovalStepStatus status);

  boolean existsByWorkflowId(Long workflowId);
}

record ApprovalStepResponse(
    Long id,
    int order,
    UserSummary approver,
    ApprovalStepStatus status,
    String comment,
    Instant decidedAt) {
  static ApprovalStepResponse from(ApprovalStep s) {
    return new ApprovalStepResponse(
        s.id,
        s.stepOrder,
        new UserSummary(s.approverId, s.approverUsername, "APPROVER"),
        s.status,
        s.comment,
        s.decidedAt);
  }
}

record ConfigureChainRequest(@NotEmpty @Size(max = 10) List<@NotNull Long> approverIds) {}

@Entity
@Table(name = "workflow_templates")
class WorkflowTemplate {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false, unique = true)
  String name;

  @Column(nullable = false)
  String requestType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  Priority defaultPriority;

  String defaultTitle;

  @Column(length = 1000)
  String descriptionHint;

  @Column(nullable = false)
  int slaHours;

  @Column(nullable = false)
  boolean active = true;

  @Column(nullable = false)
  Instant createdAt = Instant.now();

  @Column(nullable = false)
  Instant updatedAt = Instant.now();

  protected WorkflowTemplate() {}

  WorkflowTemplate(TemplateRequest r) {
    update(r);
  }

  void update(TemplateRequest r) {
    name = r.name().trim();
    requestType = r.requestType().trim();
    defaultPriority = r.defaultPriority();
    defaultTitle = r.defaultTitle();
    descriptionHint = r.descriptionHint();
    slaHours = r.slaHours();
    active = r.active();
    updatedAt = Instant.now();
  }
}

interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, Long> {
  List<WorkflowTemplate> findByActiveTrueOrderByName();
}

record TemplateRequest(
    @NotBlank @Size(max = 150) String name,
    @NotBlank @Size(max = 100) String requestType,
    @NotNull Priority defaultPriority,
    @Size(max = 200) String defaultTitle,
    @Size(max = 1000) String descriptionHint,
    @Min(1) @Max(720) int slaHours,
    boolean active) {}

record TemplateResponse(
    Long id,
    String name,
    String requestType,
    Priority defaultPriority,
    String defaultTitle,
    String descriptionHint,
    int slaHours,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {
  static TemplateResponse from(WorkflowTemplate t) {
    return new TemplateResponse(
        t.id,
        t.name,
        t.requestType,
        t.defaultPriority,
        t.defaultTitle,
        t.descriptionHint,
        t.slaHours,
        t.active,
        t.createdAt,
        t.updatedAt);
  }
}

record InstantiateTemplateRequest(
    @NotBlank @Size(max = 2000) String description, Long approverId) {}

@Service
class ApprovalChainService {
  private final ApprovalStepRepository steps;
  private final RestClient auth;
  private final String key;

  ApprovalChainService(
      ApprovalStepRepository steps,
      @Qualifier("authClient") RestClient auth,
      @Value("${app.internal-key}") String key) {
    this.steps = steps;
    this.auth = auth;
    this.key = key;
  }

  @Transactional
  List<ApprovalStepResponse> configure(WorkflowRequest workflow, List<Long> ids) {
    if (steps.existsByWorkflowId(workflow.id)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval chain already configured");
    }
    java.util.ArrayList<ApprovalStep> chain = new java.util.ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      chain.add(
          new ApprovalStep(
              workflow.id,
              i + 1,
              resolve(ids.get(i)),
              i == 0 ? ApprovalStepStatus.PENDING : ApprovalStepStatus.WAITING));
    }
    ApprovalStep first = chain.get(0);
    workflow.approverId = first.approverId;
    workflow.approverUsername = first.approverUsername;
    return steps.saveAll(chain).stream().map(ApprovalStepResponse::from).toList();
  }

  List<ApprovalStepResponse> list(Long workflowId) {
    return steps.findByWorkflowIdOrderByStepOrder(workflowId).stream()
        .map(ApprovalStepResponse::from)
        .toList();
  }

  @Transactional
  boolean recordDecision(WorkflowRequest workflow, Decision decision, String comment) {
    var current =
        steps.findFirstByWorkflowIdAndStatusOrderByStepOrder(
            workflow.id, ApprovalStepStatus.PENDING);
    if (current.isEmpty()) {
      return false;
    }
    ApprovalStep step = current.get();
    step.status =
        decision == Decision.APPROVED ? ApprovalStepStatus.APPROVED : ApprovalStepStatus.REJECTED;
    step.comment = comment;
    step.decidedAt = Instant.now();
    steps.save(step);
    if (decision == Decision.REJECTED) {
      return false;
    }
    var next =
        steps.findByWorkflowIdOrderByStepOrder(workflow.id).stream()
            .filter(s -> s.status == ApprovalStepStatus.WAITING)
            .findFirst();
    if (next.isPresent()) {
      ApprovalStep n = next.get();
      n.status = ApprovalStepStatus.PENDING;
      steps.save(n);
      workflow.approverId = n.approverId;
      workflow.approverUsername = n.approverUsername;
      return true;
    }
    return false;
  }

  @Transactional
  void cancel(Long workflowId) {
    steps.findByWorkflowIdOrderByStepOrder(workflowId).stream()
        .filter(
            s -> s.status == ApprovalStepStatus.PENDING || s.status == ApprovalStepStatus.WAITING)
        .forEach(s -> s.status = ApprovalStepStatus.CANCELLED);
  }

  private DirectoryUser resolve(Long id) {
    DirectoryUser user =
        auth.get()
            .uri("/internal/users/{id}", id)
            .header("X-Internal-Key", key)
            .retrieve()
            .body(DirectoryUser.class);
    if (user == null || !"APPROVER".equals(user.role())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "User " + id + " is not an approver");
    }
    return user;
  }
}

@RestController
class AdvancedWorkflowController {
  private final WorkflowRepository workflows;
  private final WorkflowPolicy policy;
  private final ApprovalChainService chains;
  private final WorkflowTemplateRepository templates;
  private final RestClient auth;
  private final String key;
  private final OutboxDispatcher outbox;

  AdvancedWorkflowController(
      WorkflowRepository workflows,
      WorkflowPolicy policy,
      ApprovalChainService chains,
      WorkflowTemplateRepository templates,
      @Qualifier("authClient") RestClient auth,
      @Value("${app.internal-key}") String key,
      OutboxDispatcher outbox) {
    this.workflows = workflows;
    this.policy = policy;
    this.chains = chains;
    this.templates = templates;
    this.auth = auth;
    this.key = key;
    this.outbox = outbox;
  }

  @PostMapping("/api/workflows/{id}/approval-chain")
  List<ApprovalStepResponse> configure(
      @PathVariable Long id,
      @Valid @RequestBody ConfigureChainRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    WorkflowRequest w = get(id);
    if (!w.submitterId.equals(user.id()) && !"ADMIN".equals(user.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    if (w.status != WorkflowStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow is no longer pending");
    }
    List<ApprovalStepResponse> result = chains.configure(w, request.approverIds());
    workflows.save(w);
    return result;
  }

  @GetMapping("/api/workflows/{id}/approval-chain")
  List<ApprovalStepResponse> chain(
      @PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
    WorkflowRequest w = get(id);
    policy.authorizeView(w, user);
    return chains.list(id);
  }

  @GetMapping("/api/workflow-templates")
  List<TemplateResponse> active() {
    return templates.findByActiveTrueOrderByName().stream().map(TemplateResponse::from).toList();
  }

  @PostMapping("/api/workflow-templates")
  @ResponseStatus(HttpStatus.CREATED)
  TemplateResponse createTemplate(
      @Valid @RequestBody TemplateRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    policy.requireRole(user, "ADMIN");
    return TemplateResponse.from(templates.save(new WorkflowTemplate(request)));
  }

  @PutMapping("/api/workflow-templates/{id}")
  TemplateResponse updateTemplate(
      @PathVariable Long id,
      @Valid @RequestBody TemplateRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    policy.requireRole(user, "ADMIN");
    WorkflowTemplate t =
        templates.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    t.update(request);
    return TemplateResponse.from(templates.save(t));
  }

  @PostMapping("/api/workflow-templates/{id}/instantiate")
  @ResponseStatus(HttpStatus.CREATED)
  WorkflowResponse instantiate(
      @PathVariable Long id,
      @Valid @RequestBody InstantiateTemplateRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    policy.requireRole(user, "REQUESTER");
    WorkflowTemplate t =
        templates
            .findById(id)
            .filter(item -> item.active)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Active template not found"));
    DirectoryUser approver = resolveApprover(request.approverId());
    CreateRequest create =
        new CreateRequest(
            t.defaultTitle == null || t.defaultTitle.isBlank() ? t.name : t.defaultTitle,
            request.description(),
            t.requestType,
            t.defaultPriority,
            approver.id(),
            approver.username());
    WorkflowRequest workflow = new WorkflowRequest(create, user);
    workflow.dueAt = Instant.now().plusSeconds(t.slaHours * 3600L);
    WorkflowRequest saved = workflows.save(workflow);
    outbox.publish(
        new DomainEvent(
            "WORKFLOW_SUBMITTED",
            saved.id,
            user.id(),
            user.username(),
            saved.approverId,
            "New templated workflow pending approval: " + saved.title,
            Instant.now(),
            org.slf4j.MDC.get("correlationId"),
            null));
    return WorkflowResponse.from(saved, List.of());
  }

  private DirectoryUser resolveApprover(Long id) {
    String uri = id == null ? "/internal/users/first?role=APPROVER" : "/internal/users/" + id;
    DirectoryUser user =
        auth.get().uri(uri).header("X-Internal-Key", key).retrieve().body(DirectoryUser.class);
    if (user == null || !"APPROVER".equals(user.role())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approver is invalid");
    }
    return user;
  }

  private WorkflowRequest get(Long id) {
    return workflows
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}

@Service
class SlaEscalationJob {
  private final WorkflowRepository workflows;
  private final OutboxDispatcher outbox;

  SlaEscalationJob(WorkflowRepository workflows, OutboxDispatcher outbox) {
    this.workflows = workflows;
    this.outbox = outbox;
  }

  @Scheduled(fixedDelayString = "${app.sla.scan-delay-ms:60000}")
  @Transactional
  public void escalate() {
    workflows
        .findByStatusAndDueAtBeforeAndEscalatedAtIsNull(WorkflowStatus.PENDING, Instant.now())
        .forEach(
            w -> {
              w.escalatedAt = Instant.now();
              outbox.publish(
                  new DomainEvent(
                      "WORKFLOW_SLA_BREACHED",
                      w.id,
                      w.submitterId,
                      w.submitterUsername,
                      w.approverId,
                      "SLA breached for workflow: " + w.title,
                      Instant.now(),
                      org.slf4j.MDC.get("correlationId"),
                      null));
            });
  }
}
