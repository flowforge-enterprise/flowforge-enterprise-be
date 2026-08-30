package com.cellead.workflowservice;

import com.cellead.platform.security.AuthenticatedUser;
import com.cellead.platform.security.CorrelationIdFilter;
import com.cellead.platform.security.JwtAuthenticationFilter;
import com.cellead.platform.security.JwtService;
import com.cellead.platform.security.PlatformExceptionHandler;
import com.cellead.platform.security.SecurityJsonHandlers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
@EnableMethodSecurity
@EnableScheduling
@Import(PlatformExceptionHandler.class)
public class WorkflowServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorkflowServiceApplication.class, args);
  }

  @Bean
  JwtService jwtService(@Value("${app.jwt.secret}") String secret) {
    return new JwtService(secret, 86400);
  }

  @Bean
  CorrelationIdFilter correlationIdFilter() {
    return new CorrelationIdFilter();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, JwtService jwt, ObjectMapper mapper)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(h -> SecurityJsonHandlers.configure(h, mapper))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/internal/**", "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(
            new JwtAuthenticationFilter(jwt), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  RestClient notificationClient(@Value("${app.notification-url}") String url) {
    return RestClient.builder().baseUrl(url).build();
  }

  @Bean
  RestClient authClient(@Value("${app.auth-url}") String url) {
    return RestClient.builder().baseUrl(url).build();
  }
}

enum Priority {
  LOW,
  MEDIUM,
  HIGH
}

enum WorkflowStatus {
  PENDING,
  APPROVED,
  REJECTED,
  CANCELLED
}

enum Decision {
  APPROVED,
  REJECTED
}

@Entity
@Table(name = "workflow_requests")
class WorkflowRequest {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false)
  String title;

  @Column(nullable = false, length = 2000)
  String description;

  @Column(nullable = false)
  String requestType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  Priority priority;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  WorkflowStatus status = WorkflowStatus.PENDING;

  @Column(nullable = false)
  Long submitterId;

  @Column(nullable = false)
  String submitterUsername;

  @Column(nullable = false)
  Long approverId;

  @Column(nullable = false)
  String approverUsername;

  @Column(nullable = false)
  Instant createdAt = Instant.now();

  @Column(nullable = false)
  Instant updatedAt = Instant.now();

  Instant dueAt = Instant.now().plusSeconds(48 * 3600);
  Instant escalatedAt;
  @Version Long version;

  protected WorkflowRequest() {}

  WorkflowRequest(CreateRequest r, AuthenticatedUser user) {
    title = r.title().trim();
    description = r.description().trim();
    requestType =
        r.requestType() == null || r.requestType().isBlank()
            ? "General Request"
            : r.requestType().trim();
    priority = r.priority();
    submitterId = user.id();
    submitterUsername = user.username();
    approverId = r.approverId() == null ? 2L : r.approverId();
    approverUsername =
        r.approverUsername() == null || r.approverUsername().isBlank()
            ? "approver"
            : r.approverUsername();
  }
}

@Entity
@Table(name = "approval_records")
class ApprovalRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false)
  Long workflowId;

  @Column(nullable = false)
  Long approverId;

  @Column(nullable = false)
  String approverUsername;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  Decision decision;

  @Column(length = 2000)
  String comment;

  @Column(nullable = false)
  Instant createdAt = Instant.now();

  protected ApprovalRecord() {}

  ApprovalRecord(WorkflowRequest w, AuthenticatedUser user, Decision d, String comment) {
    workflowId = w.id;
    approverId = user.id();
    approverUsername = user.username();
    decision = d;
    this.comment = comment;
  }
}

interface WorkflowRepository
    extends JpaRepository<WorkflowRequest, Long>, JpaSpecificationExecutor<WorkflowRequest> {
  List<WorkflowRequest> findBySubmitterIdOrderByCreatedAtDesc(Long id);

  List<WorkflowRequest> findByApproverIdAndStatusOrderByCreatedAtDesc(
      Long id, WorkflowStatus status);

  long countByStatus(WorkflowStatus status);

  List<WorkflowRequest> findByStatusAndDueAtBeforeAndEscalatedAtIsNull(
      WorkflowStatus status, Instant dueAt);
}

interface ApprovalRepository extends JpaRepository<ApprovalRecord, Long> {
  List<ApprovalRecord> findByWorkflowIdOrderByCreatedAtDesc(Long id);
}

record CreateRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 2000) String description,
    @Size(max = 100) String requestType,
    @NotNull Priority priority,
    Long approverId,
    String approverUsername) {}

record DecisionRequest(@Size(max = 2000) String comment) {}

record UserSummary(Long id, String username, String role) {}

record ApprovalResponse(
    Long id,
    Long workflowId,
    UserSummary approver,
    Decision decision,
    String comment,
    Instant createdAt) {
  static ApprovalResponse from(ApprovalRecord a) {
    return new ApprovalResponse(
        a.id,
        a.workflowId,
        new UserSummary(a.approverId, a.approverUsername, "APPROVER"),
        a.decision,
        a.comment,
        a.createdAt);
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
    Instant dueAt,
    Instant escalatedAt,
    List<ApprovalResponse> approvalRecords) {
  static WorkflowResponse from(WorkflowRequest w, List<ApprovalRecord> a) {
    return new WorkflowResponse(
        w.id,
        w.title,
        w.description,
        w.requestType,
        w.priority,
        w.status,
        new UserSummary(w.submitterId, w.submitterUsername, "REQUESTER"),
        new UserSummary(w.approverId, w.approverUsername, "APPROVER"),
        w.createdAt,
        w.updatedAt,
        w.dueAt,
        w.escalatedAt,
        a.stream().map(ApprovalResponse::from).toList());
  }
}

record DomainEvent(
    String type,
    Long workflowId,
    Long actorId,
    String actorUsername,
    Long recipientId,
    String message,
    Instant occurredAt,
    String correlationId,
    String eventId) {}

record DirectoryUser(Long id, String username, String role) {}

record AccessResponse(boolean allowed) {}

record TimelineEvent(String type, String actor, String detail, Instant occurredAt) {}

record WorkflowStats(long total, long pending, long approved, long rejected, long cancelled) {}

enum OutboxStatus {
  PENDING,
  SENT
}

@Entity
@Table(name = "outbox_events")
class OutboxEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false)
  String eventType;

  Long workflowId;

  @Column(nullable = false)
  Long actorId;

  @Column(nullable = false)
  String actorUsername;

  Long recipientId;

  @Column(length = 2000)
  String message;

  @Column(nullable = false)
  Instant occurredAt;

  String correlationId;

  @Column(nullable = false, unique = true)
  String eventId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  OutboxStatus status = OutboxStatus.PENDING;

  @Column(nullable = false)
  int attempts = 0;

  @Column(nullable = false)
  Instant nextAttemptAt = Instant.now();

  @Column(length = 1000)
  String lastError;

  protected OutboxEvent() {}

  OutboxEvent(DomainEvent e) {
    eventType = e.type();
    workflowId = e.workflowId();
    actorId = e.actorId();
    actorUsername = e.actorUsername();
    recipientId = e.recipientId();
    message = e.message();
    occurredAt = e.occurredAt();
    correlationId = e.correlationId();
    eventId = e.eventId() == null ? java.util.UUID.randomUUID().toString() : e.eventId();
  }

  DomainEvent event() {
    return new DomainEvent(
        eventType,
        workflowId,
        actorId,
        actorUsername,
        recipientId,
        message,
        occurredAt,
        correlationId,
        eventId);
  }
}

interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
  List<OutboxEvent> findTop50ByStatusAndNextAttemptAtBeforeOrderById(
      OutboxStatus status, Instant before);
}

@Service
class OutboxDispatcher {
  private final OutboxRepository outbox;
  private final RestClient events;
  private final String internalKey;
  private final OutboxRetryPolicy retryPolicy;

  OutboxDispatcher(
      OutboxRepository outbox,
      @Qualifier("notificationClient") RestClient events,
      @Value("${app.internal-key}") String internalKey,
      OutboxRetryPolicy retryPolicy) {
    this.outbox = outbox;
    this.events = events;
    this.internalKey = internalKey;
    this.retryPolicy = retryPolicy;
  }

  @Transactional
  void publish(DomainEvent event) {
    outbox.save(new OutboxEvent(event));
  }

  @Scheduled(fixedDelayString = "${app.outbox.retry-delay-ms:5000}")
  @Transactional
  public void retry() {
    outbox
        .findTop50ByStatusAndNextAttemptAtBeforeOrderById(OutboxStatus.PENDING, Instant.now())
        .forEach(this::deliver);
  }

  private void deliver(OutboxEvent item) {
    try {
      events
          .post()
          .uri("/internal/events")
          .header("X-Internal-Key", internalKey)
          .body(item.event())
          .retrieve()
          .toBodilessEntity();
      item.status = OutboxStatus.SENT;
      item.lastError = null;
    } catch (RuntimeException ex) {
      item.attempts++;
      item.lastError = retryPolicy.safeError(ex);
      item.nextAttemptAt = Instant.now().plus(retryPolicy.nextDelay(item.attempts));
    }
    outbox.save(item);
  }
}

@RestController
@Transactional
class WorkflowController {
  private final WorkflowRepository workflows;
  private final ApprovalRepository approvals;
  private final OutboxDispatcher outbox;
  private final RestClient auth;
  private final String internalKey;
  private final WorkflowPolicy policy;
  private final WorkflowQueryService queries;
  private final ApprovalChainService chains;

  WorkflowController(
      WorkflowRepository workflows,
      ApprovalRepository approvals,
      OutboxDispatcher outbox,
      @Qualifier("authClient") RestClient authClient,
      @Value("${app.internal-key}") String internalKey,
      WorkflowPolicy policy,
      WorkflowQueryService queries,
      ApprovalChainService chains) {
    this.workflows = workflows;
    this.approvals = approvals;
    this.outbox = outbox;
    this.auth = authClient;
    this.internalKey = internalKey;
    this.policy = policy;
    this.queries = queries;
    this.chains = chains;
  }

  @PostMapping("/api/workflows")
  WorkflowResponse create(
      @Valid @RequestBody CreateRequest body, @AuthenticationPrincipal AuthenticatedUser user) {
    policy.requireRole(user, "REQUESTER");
    DirectoryUser approver = resolveApprover(body.approverId());
    CreateRequest normalized =
        new CreateRequest(
            body.title(),
            body.description(),
            body.requestType(),
            body.priority(),
            approver.id(),
            approver.username());
    WorkflowRequest saved = workflows.save(new WorkflowRequest(normalized, user));
    outbox.publish(
        new DomainEvent(
            "WORKFLOW_SUBMITTED",
            saved.id,
            user.id(),
            user.username(),
            saved.approverId,
            "New workflow request pending approval: " + saved.title,
            Instant.now(),
            org.slf4j.MDC.get("correlationId"),
            null));
    return WorkflowResponse.from(saved, List.of());
  }

  @GetMapping("/api/workflows/my")
  List<WorkflowResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
    policy.requireRole(user, "REQUESTER");
    return workflows.findBySubmitterIdOrderByCreatedAtDesc(user.id()).stream()
        .map(w -> WorkflowResponse.from(w, List.of()))
        .toList();
  }

  @GetMapping("/api/workflows")
  @Transactional(readOnly = true)
  Page<WorkflowResponse> search(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) WorkflowStatus status,
      @RequestParam(required = false) String q,
      @AuthenticationPrincipal AuthenticatedUser user) {
    if (size < 1 || size > 100 || page < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
    }
    Specification<WorkflowRequest> spec = (root, query, cb) -> cb.conjunction();
    if ("REQUESTER".equals(user.role())) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("submitterId"), user.id()));
    } else if ("APPROVER".equals(user.role())) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("approverId"), user.id()));
    } else if (!"ADMIN".equals(user.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    if (status != null) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
    }
    if (q != null && !q.isBlank()) {
      String pattern = "%" + q.trim().toLowerCase(java.util.Locale.ROOT) + "%";
      spec =
          spec.and(
              (root, query, cb) ->
                  cb.or(
                      cb.like(cb.lower(root.get("title")), cb.literal(pattern)),
                      cb.like(cb.lower(root.get("description")), cb.literal(pattern))));
    }
    return workflows
        .findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
        .map(w -> WorkflowResponse.from(w, List.of()));
  }

  @GetMapping("/api/workflows/{id}")
  @Transactional(readOnly = true)
  WorkflowResponse detail(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
    WorkflowRequest w = get(id);
    policy.authorizeView(w, user);
    return WorkflowResponse.from(w, approvals.findByWorkflowIdOrderByCreatedAtDesc(id));
  }

  @GetMapping("/api/workflows/{id}/timeline")
  @Transactional(readOnly = true)
  List<TimelineEvent> timeline(
      @PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
    WorkflowRequest w = get(id);
    policy.authorizeView(w, user);
    return queries.timeline(w);
  }

  @PostMapping("/api/workflows/{id}/cancel")
  WorkflowResponse cancel(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
    WorkflowRequest w = get(id);
    policy.authorizeCancellation(w, user);
    w.status = WorkflowStatus.CANCELLED;
    w.updatedAt = Instant.now();
    chains.cancel(w.id);
    outbox.publish(
        new DomainEvent(
            "WORKFLOW_CANCELLED",
            w.id,
            user.id(),
            user.username(),
            w.approverId,
            "Workflow request " + w.title + " was cancelled",
            Instant.now(),
            org.slf4j.MDC.get("correlationId"),
            null));
    return WorkflowResponse.from(w, approvals.findByWorkflowIdOrderByCreatedAtDesc(id));
  }

  @GetMapping("/api/workflows/stats")
  @Transactional(readOnly = true)
  WorkflowStats stats(@AuthenticationPrincipal AuthenticatedUser user) {
    policy.requireRole(user, "ADMIN");
    return queries.statistics();
  }

  @GetMapping("/internal/workflows/{id}/access")
  AccessResponse access(
      @PathVariable Long id,
      @RequestParam Long userId,
      @RequestParam String role,
      @RequestHeader("X-Internal-Key") String supplied) {
    if (!internalKey.equals(supplied)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    WorkflowRequest w = get(id);
    return new AccessResponse(
        "ADMIN".equals(role) || w.submitterId.equals(userId) || w.approverId.equals(userId));
  }

  @GetMapping("/api/approvals/tasks")
  List<WorkflowResponse> tasks(@AuthenticationPrincipal AuthenticatedUser user) {
    policy.requireRole(user, "APPROVER");
    return workflows
        .findByApproverIdAndStatusOrderByCreatedAtDesc(user.id(), WorkflowStatus.PENDING)
        .stream()
        .map(w -> WorkflowResponse.from(w, List.of()))
        .toList();
  }

  @PostMapping("/api/approvals/{id}/approve")
  ApprovalResponse approve(
      @PathVariable Long id,
      @Valid @RequestBody(required = false) DecisionRequest body,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return decide(id, body, user, Decision.APPROVED);
  }

  @PostMapping("/api/approvals/{id}/reject")
  ApprovalResponse reject(
      @PathVariable Long id,
      @Valid @RequestBody(required = false) DecisionRequest body,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return decide(id, body, user, Decision.REJECTED);
  }

  private ApprovalResponse decide(
      Long id, DecisionRequest body, AuthenticatedUser user, Decision decision) {
    WorkflowRequest w = get(id);
    policy.authorizeDecision(w, user);
    boolean hasNext = chains.recordDecision(w, decision, body == null ? null : body.comment());
    if (decision == Decision.REJECTED) {
      w.status = WorkflowStatus.REJECTED;
    } else if (hasNext) {
      w.status = WorkflowStatus.PENDING;
    } else {
      w.status = WorkflowStatus.APPROVED;
    }
    w.updatedAt = Instant.now();
    ApprovalRecord a =
        approvals.save(new ApprovalRecord(w, user, decision, body == null ? null : body.comment()));
    Long recipient = hasNext ? w.approverId : w.submitterId;
    String eventType = hasNext ? "WORKFLOW_STEP_APPROVED" : "WORKFLOW_" + decision.name();
    outbox.publish(
        new DomainEvent(
            eventType,
            w.id,
            user.id(),
            user.username(),
            recipient,
            hasNext
                ? "Workflow requires your approval: " + w.title
                : "Workflow request " + w.title + " was " + decision.name().toLowerCase(),
            Instant.now(),
            org.slf4j.MDC.get("correlationId"),
            null));
    return ApprovalResponse.from(a);
  }

  private WorkflowRequest get(Long id) {
    return workflows
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private DirectoryUser resolveApprover(Long id) {
    String uri = id == null ? "/internal/users/first?role=APPROVER" : "/internal/users/" + id;
    DirectoryUser user =
        auth.get()
            .uri(uri)
            .header("X-Internal-Key", internalKey)
            .retrieve()
            .body(DirectoryUser.class);
    if (user == null || !"APPROVER".equals(user.role())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected user is not an approver");
    }
    return user;
  }
}
