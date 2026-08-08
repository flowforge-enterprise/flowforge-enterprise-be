package com.cellead.workflowservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cellead.platform.security.AuthenticatedUser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class WorkflowControllerTest {
  private static final AuthenticatedUser REQUESTER =
      new AuthenticatedUser(11L, "requester", "REQUESTER");
  private static final AuthenticatedUser APPROVER =
      new AuthenticatedUser(22L, "approver", "APPROVER");
  private static final AuthenticatedUser ADMIN = new AuthenticatedUser(99L, "admin", "ADMIN");
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void createAndQueryWorkflowPaths() throws Exception {
    startDirectoryServer();
    Fixture fixture = fixture();
    WorkflowRequest workflow = workflow();
    when(fixture.workflows.save(any(WorkflowRequest.class)))
        .thenAnswer(
            invocation -> {
              WorkflowRequest saved = invocation.getArgument(0);
              saved.id = 1L;
              return saved;
            });
    when(fixture.workflows.findBySubmitterIdOrderByCreatedAtDesc(11L))
        .thenReturn(List.of(workflow));
    when(
            fixture.workflows.findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(workflow)));
    when(fixture.workflows.findById(1L)).thenReturn(Optional.of(workflow));
    when(fixture.approvals.findByWorkflowIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
    when(fixture.queries.timeline(workflow))
        .thenReturn(List.of(new TimelineEvent("SUBMITTED", "requester", "created", Instant.now())));

    WorkflowResponse created =
        fixture.controller.create(
            new CreateRequest("Title", "Details", null, Priority.HIGH, null, null), REQUESTER);
    assertThat(created.id()).isEqualTo(1L);
    verify(fixture.outbox).publish(any(DomainEvent.class));
    assertThat(fixture.controller.mine(REQUESTER)).hasSize(1);
    assertThat(fixture.controller.search(0, 20, WorkflowStatus.PENDING, "title", REQUESTER))
        .hasSize(1);
    assertThat(fixture.controller.search(0, 20, null, null, APPROVER)).hasSize(1);
    assertThat(fixture.controller.search(0, 20, null, null, ADMIN)).hasSize(1);
    assertThat(fixture.controller.detail(1L, REQUESTER).id()).isEqualTo(1L);
    assertThat(fixture.controller.timeline(1L, REQUESTER)).hasSize(1);
    assertThatThrownBy(() -> fixture.controller.search(-1, 20, null, null, ADMIN))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void cancellationTasksStatisticsAndInternalAccessWork() throws Exception {
    startDirectoryServer();
    Fixture fixture = fixture();
    WorkflowRequest workflow = workflow();
    when(fixture.workflows.findById(1L)).thenReturn(Optional.of(workflow));
    when(fixture.approvals.findByWorkflowIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
    when(fixture.workflows.findByApproverIdAndStatusOrderByCreatedAtDesc(
            22L, WorkflowStatus.PENDING))
        .thenReturn(List.of(workflow));
    WorkflowStats stats = new WorkflowStats(5, 2, 1, 1, 1);
    when(fixture.queries.statistics()).thenReturn(stats);

    assertThat(fixture.controller.tasks(APPROVER)).hasSize(1);
    assertThat(fixture.controller.stats(ADMIN)).isEqualTo(stats);
    assertThat(fixture.controller.access(1L, 11L, "REQUESTER", "test-key").allowed()).isTrue();
    assertThat(fixture.controller.access(1L, 50L, "REQUESTER", "test-key").allowed()).isFalse();
    assertThatThrownBy(() -> fixture.controller.access(1L, 11L, "REQUESTER", "wrong"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(fixture.controller.cancel(1L, REQUESTER).status())
        .isEqualTo(WorkflowStatus.CANCELLED);
    verify(fixture.chains).cancel(1L);
    assertThatThrownBy(() -> fixture.controller.detail(404L, ADMIN))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void approvalDecisionsCreateRecordsAndEvents() throws Exception {
    startDirectoryServer();
    Fixture fixture = fixture();
    WorkflowRequest approvedWorkflow = workflow();
    WorkflowRequest rejectedWorkflow = workflow();
    rejectedWorkflow.id = 2L;
    when(fixture.workflows.findById(1L)).thenReturn(Optional.of(approvedWorkflow));
    when(fixture.workflows.findById(2L)).thenReturn(Optional.of(rejectedWorkflow));
    when(fixture.chains.recordDecision(eq(approvedWorkflow), eq(Decision.APPROVED), any()))
        .thenReturn(false);
    when(fixture.chains.recordDecision(eq(rejectedWorkflow), eq(Decision.REJECTED), any()))
        .thenReturn(false);
    when(fixture.approvals.save(any(ApprovalRecord.class)))
        .thenAnswer(
            invocation -> {
              ApprovalRecord record = invocation.getArgument(0);
              record.id = 30L;
              return record;
            });

    ApprovalResponse approved =
        fixture.controller.approve(1L, new DecisionRequest("looks good"), APPROVER);
    assertThat(approved.decision()).isEqualTo(Decision.APPROVED);
    assertThat(approvedWorkflow.status).isEqualTo(WorkflowStatus.APPROVED);
    ApprovalResponse rejected = fixture.controller.reject(2L, null, APPROVER);
    assertThat(rejected.decision()).isEqualTo(Decision.REJECTED);
    assertThat(rejectedWorkflow.status).isEqualTo(WorkflowStatus.REJECTED);
  }

  @Test
  void queryServiceBuildsTimelineAndStatistics() {
    WorkflowRepository workflows = mock(WorkflowRepository.class);
    ApprovalRepository approvals = mock(ApprovalRepository.class);
    WorkflowQueryService queries = new WorkflowQueryService(workflows, approvals);
    WorkflowRequest workflow = workflow();
    workflow.status = WorkflowStatus.CANCELLED;
    ApprovalRecord approval = new ApprovalRecord(workflow, APPROVER, Decision.APPROVED, "ok");
    when(approvals.findByWorkflowIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(approval));
    when(workflows.count()).thenReturn(5L);
    when(workflows.countByStatus(WorkflowStatus.PENDING)).thenReturn(1L);
    when(workflows.countByStatus(WorkflowStatus.APPROVED)).thenReturn(2L);
    when(workflows.countByStatus(WorkflowStatus.REJECTED)).thenReturn(1L);
    when(workflows.countByStatus(WorkflowStatus.CANCELLED)).thenReturn(1L);

    assertThat(queries.timeline(workflow)).extracting(TimelineEvent::type)
        .containsExactly("SUBMITTED", "CANCELLED", "APPROVED");
    assertThat(queries.statistics()).isEqualTo(new WorkflowStats(5, 1, 2, 1, 1));
  }

  @Test
  void approvalChainConfiguresAdvancesRejectsAndCancelsSteps() throws Exception {
    startDirectoryServer();
    ApprovalStepRepository steps = mock(ApprovalStepRepository.class);
    ApprovalChainService chains =
        new ApprovalChainService(
            steps, RestClient.builder().baseUrl(serverUrl()).build(), "test-key");
    WorkflowRequest workflow = workflow();
    when(steps.existsByWorkflowId(1L)).thenReturn(false);
    when(steps.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    List<ApprovalStepResponse> configured = chains.configure(workflow, List.of(22L, 23L));
    assertThat(configured).extracting(ApprovalStepResponse::status)
        .containsExactly(ApprovalStepStatus.PENDING, ApprovalStepStatus.WAITING);
    assertThat(workflow.approverId).isEqualTo(22L);

    ApprovalStep current =
        new ApprovalStep(1L, 1, new DirectoryUser(22L, "approver", "APPROVER"),
            ApprovalStepStatus.PENDING);
    ApprovalStep next =
        new ApprovalStep(1L, 2, new DirectoryUser(23L, "manager", "APPROVER"),
            ApprovalStepStatus.WAITING);
    when(steps.findFirstByWorkflowIdAndStatusOrderByStepOrder(1L, ApprovalStepStatus.PENDING))
        .thenReturn(Optional.of(current));
    when(steps.findByWorkflowIdOrderByStepOrder(1L)).thenReturn(List.of(current, next));
    assertThat(chains.recordDecision(workflow, Decision.APPROVED, "ok")).isTrue();
    assertThat(next.status).isEqualTo(ApprovalStepStatus.PENDING);
    assertThat(workflow.approverId).isEqualTo(23L);

    current.status = ApprovalStepStatus.PENDING;
    next.status = ApprovalStepStatus.WAITING;
    assertThat(chains.recordDecision(workflow, Decision.REJECTED, "no")).isFalse();
    assertThat(current.status).isEqualTo(ApprovalStepStatus.REJECTED);

    current.status = ApprovalStepStatus.PENDING;
    chains.cancel(1L);
    assertThat(current.status).isEqualTo(ApprovalStepStatus.CANCELLED);
    assertThat(next.status).isEqualTo(ApprovalStepStatus.CANCELLED);
    assertThat(chains.list(1L)).hasSize(2);
  }

  private Fixture fixture() {
    WorkflowRepository workflows = mock(WorkflowRepository.class);
    ApprovalRepository approvals = mock(ApprovalRepository.class);
    OutboxDispatcher outbox = mock(OutboxDispatcher.class);
    WorkflowPolicy policy = new WorkflowPolicy();
    WorkflowQueryService queries = mock(WorkflowQueryService.class);
    ApprovalChainService chains = mock(ApprovalChainService.class);
    RestClient auth = RestClient.builder().baseUrl(serverUrl()).build();
    WorkflowController controller =
        new WorkflowController(
            workflows, approvals, outbox, auth, "test-key", policy, queries, chains);
    return new Fixture(controller, workflows, approvals, outbox, queries, chains);
  }

  private WorkflowRequest workflow() {
    WorkflowRequest workflow =
        new WorkflowRequest(
            new CreateRequest(
                "Title", "Details", "GENERAL", Priority.MEDIUM, 22L, "approver"),
            REQUESTER);
    workflow.id = 1L;
    return workflow;
  }

  private void startDirectoryServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/users/first",
        exchange -> respond(exchange, "{\"id\":22,\"username\":\"approver\",\"role\":\"APPROVER\"}"));
    server.createContext(
        "/internal/users/22",
        exchange -> respond(exchange, "{\"id\":22,\"username\":\"approver\",\"role\":\"APPROVER\"}"));
    server.createContext(
        "/internal/users/23",
        exchange -> respond(exchange, "{\"id\":23,\"username\":\"manager\",\"role\":\"APPROVER\"}"));
    server.start();
  }

  private void respond(com.sun.net.httpserver.HttpExchange exchange, String response)
      throws java.io.IOException {
    byte[] body = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private String serverUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  private record Fixture(
      WorkflowController controller,
      WorkflowRepository workflows,
      ApprovalRepository approvals,
      OutboxDispatcher outbox,
      WorkflowQueryService queries,
      ApprovalChainService chains) {}
}
