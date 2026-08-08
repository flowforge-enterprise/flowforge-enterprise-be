package com.cellead.notification;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationAuditApplicationTest {
  private static final AuthenticatedUser USER =
      new AuthenticatedUser(7L, "requester", "REQUESTER");
  private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin", "ADMIN");
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void contextLoads() {}

  @Test
  void eventValidatesKeyAndCreatesAuditAndNotification() {
    NotificationRepository notifications = mock(NotificationRepository.class);
    AuditRepository audits = mock(AuditRepository.class);
    SimpMessageSendingOperations messaging = mock(SimpMessageSendingOperations.class);
    NotificationAuditController controller = controller(notifications, audits, messaging, null);
    DomainEvent event = event("event-1", 7L);
    when(audits.existsByEventId("event-1")).thenReturn(false);
    when(audits.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(notifications.save(any(NotificationRecord.class)))
        .thenAnswer(
            invocation -> {
              NotificationRecord record = invocation.getArgument(0);
              record.id = 10L;
              return record;
            });

    controller.event("test-key", event);

    verify(audits).save(any(AuditLog.class));
    verify(notifications).save(any(NotificationRecord.class));
    verify(messaging).convertAndSend(eq("/topic/notifications/7"), any(NotificationResponse.class));
    assertThatThrownBy(() -> controller.event("wrong", event))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");
    assertThatThrownBy(() -> controller.event("test-key", event(" ", 7L)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("eventId is required");

    when(audits.existsByEventId("event-1")).thenReturn(true);
    controller.event("test-key", event);
  }

  @Test
  void notificationQueriesAndReadOperationsReturnMappedData() {
    NotificationRepository notifications = mock(NotificationRepository.class);
    AuditRepository audits = mock(AuditRepository.class);
    NotificationAuditController controller =
        controller(notifications, audits, mock(SimpMessageSendingOperations.class), null);
    NotificationRecord record = new NotificationRecord(event("event-2", 7L));
    record.id = 12L;
    when(notifications.findByRecipientIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(record));
    when(notifications.findByRecipientId(eq(7L), any())).thenReturn(new PageImpl<>(List.of(record)));
    when(notifications.countByRecipientIdAndReadFlagFalse(7L)).thenReturn(1L);
    when(notifications.findByIdAndRecipientId(12L, 7L)).thenReturn(Optional.of(record));
    when(notifications.save(record)).thenReturn(record);
    when(notifications.saveAll(any())).thenReturn(List.of(record));
    when(notifications.findAll()).thenReturn(List.of(record));

    assertThat(controller.mine(USER)).singleElement().extracting(NotificationResponse::id)
        .isEqualTo(12L);
    assertThat(controller.minePage(0, 20, USER).getContent()).hasSize(1);
    assertThat(controller.unread(USER).count()).isEqualTo(1);
    assertThat(controller.markRead(12L, USER).read()).isTrue();
    assertThat(controller.markAllRead(USER)).singleElement().extracting(NotificationResponse::read)
        .isEqualTo(true);
    assertThat(controller.all(ADMIN)).hasSize(1);
    assertThatThrownBy(() -> controller.all(USER)).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> controller.minePage(-1, 20, USER))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Invalid page or size");
    assertThatThrownBy(() -> controller.markRead(99L, USER))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Notification not found");
  }

  @Test
  void auditQueriesEnforceAdminAndWorkflowAccess() throws Exception {
    startAccessServer(true);
    NotificationRepository notifications = mock(NotificationRepository.class);
    AuditRepository audits = mock(AuditRepository.class);
    NotificationAuditController controller =
        controller(notifications, audits, mock(SimpMessageSendingOperations.class), serverUrl());
    AuditLog audit = new AuditLog(event("event-3", null));
    audit.id = 20L;
    when(audits.findAll()).thenReturn(List.of(audit));
    when(audits.findByWorkflowIdOrderByCreatedAtDesc(5L)).thenReturn(List.of(audit));

    assertThat(controller.audits(null, ADMIN)).singleElement().extracting(AuditResponse::id)
        .isEqualTo(20L);
    assertThatThrownBy(() -> controller.audits(null, USER))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(controller.audits(5L, USER)).hasSize(1);
  }

  private NotificationAuditController controller(
      NotificationRepository notifications,
      AuditRepository audits,
      SimpMessageSendingOperations messaging,
      String workflowUrl) {
    String url = workflowUrl == null ? "http://localhost:1" : workflowUrl;
    return new NotificationAuditController(
        notifications, audits, "test-key", RestClient.builder().baseUrl(url).build(), messaging);
  }

  private DomainEvent event(String eventId, Long recipientId) {
    return new DomainEvent(
        "WORKFLOW_UPDATED",
        5L,
        1L,
        "admin",
        recipientId,
        "Workflow updated",
        Instant.now(),
        "trace-1",
        eventId);
  }

  private void startAccessServer(boolean allowed) throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/workflows/5/access",
        exchange -> {
          byte[] body = ("{\"allowed\":" + allowed + "}").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  private String serverUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }
}
