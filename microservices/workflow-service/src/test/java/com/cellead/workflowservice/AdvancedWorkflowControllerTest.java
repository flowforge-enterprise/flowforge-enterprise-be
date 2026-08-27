package com.cellead.workflowservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cellead.platform.security.AuthenticatedUser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class AdvancedWorkflowControllerTest {
  private static final AuthenticatedUser REQUESTER =
      new AuthenticatedUser(11L, "requester", "REQUESTER");
  private static final AuthenticatedUser OTHER_REQUESTER =
      new AuthenticatedUser(12L, "other", "REQUESTER");
  private static final AuthenticatedUser ADMIN = new AuthenticatedUser(99L, "admin", "ADMIN");
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void adminManagesTemplatesAndRequesterInstantiatesOne() throws Exception {
    startDirectoryServer();
    Fixture fixture = fixture();
    TemplateRequest createRequest =
        new TemplateRequest("Purchase", "PURCHASE", Priority.HIGH, "", "Attach quote", 24, true);
    when(fixture.templates.save(any(WorkflowTemplate.class)))
        .thenAnswer(
            invocation -> {
              WorkflowTemplate template = invocation.getArgument(0);
              template.id = 7L;
              return template;
            });

    TemplateResponse created = fixture.controller.createTemplate(createRequest, ADMIN);
    assertThat(created.id()).isEqualTo(7L);
    assertThat(created.name()).isEqualTo("Purchase");

    WorkflowTemplate template = new WorkflowTemplate(createRequest);
    template.id = 7L;
    when(fixture.templates.findByActiveTrueOrderByName()).thenReturn(List.of(template));
    when(fixture.templates.findById(7L)).thenReturn(Optional.of(template));
    assertThat(fixture.controller.active()).extracting(TemplateResponse::id).containsExactly(7L);

    TemplateRequest updateRequest =
        new TemplateRequest(
            "Purchase Updated", "PURCHASE", Priority.MEDIUM, "", "New hint", 48, true);
    TemplateResponse updated = fixture.controller.updateTemplate(7L, updateRequest, ADMIN);
    assertThat(updated.name()).isEqualTo("Purchase Updated");
    assertThat(updated.slaHours()).isEqualTo(48);

    when(fixture.workflows.save(any(WorkflowRequest.class)))
        .thenAnswer(
            invocation -> {
              WorkflowRequest workflow = invocation.getArgument(0);
              workflow.id = 100L;
              return workflow;
            });
    Instant before = Instant.now();
    WorkflowResponse instantiated =
        fixture.controller.instantiate(
            7L, new InstantiateTemplateRequest("Need new laptops", null), REQUESTER);

    assertThat(instantiated.id()).isEqualTo(100L);
    assertThat(instantiated.title()).isEqualTo("Purchase Updated");
    assertThat(instantiated.approver().username()).isEqualTo("approver");
    assertThat(instantiated.dueAt())
        .isBetween(before.plus(Duration.ofHours(47)), before.plus(Duration.ofHours(49)));
    verify(fixture.outbox).publish(any(DomainEvent.class));
  }

  @Test
  void workflowOwnerOrAdminConfiguresAndReadsApprovalChain() throws Exception {
    startDirectoryServer();
    Fixture fixture = fixture();
    WorkflowRequest workflow = workflow();
    when(fixture.workflows.findById(1L)).thenReturn(Optional.of(workflow));
    ApprovalStepResponse step =
        new ApprovalStepResponse(
            1L,
            1,
            new UserSummary(22L, "approver", "APPROVER"),
            ApprovalStepStatus.PENDING,
            null,
            null);
    when(fixture.chains.configure(workflow, List.of(22L))).thenReturn(List.of(step));
    when(fixture.chains.list(1L)).thenReturn(List.of(step));

    assertThat(
            fixture.controller.configure(
                1L, new ConfigureChainRequest(List.of(22L)), REQUESTER))
        .containsExactly(step);
    verify(fixture.workflows).save(workflow);
    assertThat(fixture.controller.chain(1L, ADMIN)).containsExactly(step);
  }

  @Test
  void advancedEndpointsRejectInvalidStateIdentityAndResources() throws Exception {
    startDirectoryServer();
    Fixture fixture = fixture();
    WorkflowRequest workflow = workflow();
    when(fixture.workflows.findById(1L)).thenReturn(Optional.of(workflow));

    assertThatThrownBy(
            () ->
                fixture.controller.configure(
                    1L, new ConfigureChainRequest(List.of(22L)), OTHER_REQUESTER))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode.value")
        .isEqualTo(403);

    workflow.status = WorkflowStatus.APPROVED;
    assertThatThrownBy(
            () ->
                fixture.controller.configure(
                    1L, new ConfigureChainRequest(List.of(22L)), ADMIN))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode.value")
        .isEqualTo(409);

    when(fixture.templates.findById(8L)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                fixture.controller.instantiate(
                    8L, new InstantiateTemplateRequest("details", 22L), REQUESTER))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                fixture.controller.updateTemplate(
                    8L,
                    new TemplateRequest("X", "GENERAL", Priority.LOW, null, null, 1, true),
                    ADMIN))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> fixture.controller.chain(404L, ADMIN))
        .isInstanceOf(ResponseStatusException.class);

    WorkflowTemplate active =
        new WorkflowTemplate(
            new TemplateRequest("General", "GENERAL", Priority.LOW, "Title", null, 1, true));
    active.id = 9L;
    when(fixture.templates.findById(9L)).thenReturn(Optional.of(active));
    assertThatThrownBy(
            () ->
                fixture.controller.instantiate(
                    9L, new InstantiateTemplateRequest("details", 404L), REQUESTER))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode.value")
        .isEqualTo(400);
  }

  @Test
  void slaJobEscalatesEveryOverduePendingWorkflow() {
    WorkflowRepository workflows = mock(WorkflowRepository.class);
    OutboxDispatcher outbox = mock(OutboxDispatcher.class);
    WorkflowRequest first = workflow();
    WorkflowRequest second = workflow();
    second.id = 2L;
    when(
            workflows.findByStatusAndDueAtBeforeAndEscalatedAtIsNull(
                org.mockito.ArgumentMatchers.eq(WorkflowStatus.PENDING), any(Instant.class)))
        .thenReturn(List.of(first, second));

    new SlaEscalationJob(workflows, outbox).escalate();

    assertThat(first.escalatedAt).isNotNull();
    assertThat(second.escalatedAt).isNotNull();
    org.mockito.Mockito.verify(outbox, org.mockito.Mockito.times(2))
        .publish(any(DomainEvent.class));
  }

  private Fixture fixture() {
    WorkflowRepository workflows = mock(WorkflowRepository.class);
    ApprovalChainService chains = mock(ApprovalChainService.class);
    WorkflowTemplateRepository templates = mock(WorkflowTemplateRepository.class);
    OutboxDispatcher outbox = mock(OutboxDispatcher.class);
    RestClient auth = RestClient.builder().baseUrl(serverUrl()).build();
    AdvancedWorkflowController controller =
        new AdvancedWorkflowController(
            workflows,
            new WorkflowPolicy(),
            chains,
            templates,
            auth,
            "test-key",
            outbox);
    return new Fixture(controller, workflows, chains, templates, outbox);
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
        exchange -> respond(exchange, 200, approverJson()));
    server.createContext(
        "/internal/users/22",
        exchange -> respond(exchange, 200, approverJson()));
    server.createContext(
        "/internal/users/404",
        exchange ->
            respond(
                exchange,
                200,
                "{\"id\":404,\"username\":\"requester\",\"role\":\"REQUESTER\"}"));
    server.start();
  }

  private String approverJson() {
    return "{\"id\":22,\"username\":\"approver\",\"role\":\"APPROVER\"}";
  }

  private String serverUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private record Fixture(
      AdvancedWorkflowController controller,
      WorkflowRepository workflows,
      ApprovalChainService chains,
      WorkflowTemplateRepository templates,
      OutboxDispatcher outbox) {}
}
