package com.cellead.workflowservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

class OutboxDispatcherTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void publishPersistsACompleteOutboxEnvelope() {
    OutboxRepository repository = mock(OutboxRepository.class);
    OutboxDispatcher dispatcher =
        new OutboxDispatcher(
            repository, RestClient.builder().baseUrl("http://localhost").build(), "key",
            new OutboxRetryPolicy());
    DomainEvent event = event("event-1");

    dispatcher.publish(event);

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().event()).isEqualTo(event);
    assertThat(captor.getValue().status).isEqualTo(OutboxStatus.PENDING);
  }

  @Test
  void retryMarksSuccessfulDeliveryAsSent() throws Exception {
    AtomicInteger requests = startEventServer(204);
    OutboxRepository repository = mock(OutboxRepository.class);
    OutboxEvent item = new OutboxEvent(event(null));
    item.lastError = "old error";
    when(repository.findTop50ByStatusAndNextAttemptAtBeforeOrderById(
            eq(OutboxStatus.PENDING), any(Instant.class)))
        .thenReturn(List.of(item));
    OutboxDispatcher dispatcher = dispatcher(repository);

    dispatcher.retry();

    assertThat(requests).hasValue(1);
    assertThat(item.status).isEqualTo(OutboxStatus.SENT);
    assertThat(item.lastError).isNull();
    assertThat(item.eventId).isNotBlank();
    verify(repository).save(item);
  }

  @Test
  void retryRecordsFailureAndSchedulesExponentialBackoff() throws Exception {
    AtomicInteger requests = startEventServer(503);
    OutboxRepository repository = mock(OutboxRepository.class);
    OutboxEvent item = new OutboxEvent(event("event-2"));
    Instant before = Instant.now();
    when(repository.findTop50ByStatusAndNextAttemptAtBeforeOrderById(
            eq(OutboxStatus.PENDING), any(Instant.class)))
        .thenReturn(List.of(item));

    dispatcher(repository).retry();

    assertThat(requests).hasValue(1);
    assertThat(item.status).isEqualTo(OutboxStatus.PENDING);
    assertThat(item.attempts).isOne();
    assertThat(item.lastError).isNotBlank();
    assertThat(item.nextAttemptAt).isAfter(before.plusSeconds(1));
    verify(repository).save(item);
  }

  private OutboxDispatcher dispatcher(OutboxRepository repository) {
    return new OutboxDispatcher(
        repository,
        RestClient.builder().baseUrl("http://localhost:" + server.getAddress().getPort()).build(),
        "test-key",
        new OutboxRetryPolicy());
  }

  private AtomicInteger startEventServer(int status) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/internal/events",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return requests;
  }

  private DomainEvent event(String eventId) {
    return new DomainEvent(
        "WORKFLOW_SUBMITTED",
        1L,
        11L,
        "requester",
        22L,
        "pending",
        Instant.parse("2026-08-27T01:00:00Z"),
        "correlation-1",
        eventId);
  }
}
