package com.cellead.attachment;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class AttachmentApplicationTest {
  @TempDir Path temp;
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void storedFileHasContentAndSha256() throws Exception {
    AttachmentStorage storage = new AttachmentStorage(temp.toString());
    StoredFile stored =
        storage.save(
            new MockMultipartFile("file", "proof.txt", "text/plain", "evidence".getBytes()));
    assertThat(stored.sha256()).hasSize(64);
    assertThat(storage.load(stored.key()).contentLength()).isEqualTo(8);
    storage.delete(stored.key());
    assertThat(Files.list(temp)).isEmpty();
  }

  @Test
  void applicationCreatesInfrastructureBeans() {
    AttachmentApplication application = new AttachmentApplication();

    assertThat(application.jwtService("test-only-jwt-secret-at-least-32-characters-long"))
        .isNotNull();
    assertThat(application.correlationIdFilter()).isNotNull();
    assertThat(application.workflowClient("http://localhost")).isNotNull();
  }

  @Test
  void storageRejectsInvalidFilesAndMissingContent() throws Exception {
    AttachmentStorage storage = new AttachmentStorage(temp.toString());

    assertThatThrownBy(
            () ->
                storage.save(
                    new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThatThrownBy(
            () ->
                storage.save(
                    new MockMultipartFile(
                        "file", "script.exe", "application/octet-stream", new byte[] {1})))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThatThrownBy(() -> storage.load("missing"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void controllerUploadsListsDownloadsAndDeletesAttachment() throws Exception {
    startAccessServer(true);
    AttachmentRepository repository = mock(AttachmentRepository.class);
    AttachmentStorage storage = new AttachmentStorage(temp.toString());
    AttachmentController controller = controller(repository, storage);
    AuthenticatedUser user = new AuthenticatedUser(7L, "requester", "REQUESTER");
    AtomicReference<AttachmentRecord> savedRecord = new AtomicReference<>();
    when(repository.save(any(AttachmentRecord.class)))
        .thenAnswer(
            invocation -> {
              AttachmentRecord record = invocation.getArgument(0);
              record.id = 11L;
              savedRecord.set(record);
              return record;
            });
    MockMultipartFile file =
        new MockMultipartFile("file", "proof.txt", "text/plain", "evidence".getBytes());

    AttachmentResponse uploaded = controller.upload(5L, file, user);
    assertThat(uploaded.id()).isEqualTo(11L);
    assertThat(uploaded.sha256()).hasSize(64);

    AttachmentRecord record = savedRecord.get();
    when(repository.findByWorkflowIdOrderByCreatedAtDesc(5L)).thenReturn(List.of(record));
    when(repository.findById(11L)).thenReturn(Optional.of(record));
    assertThat(controller.list(5L, user)).singleElement().extracting(AttachmentResponse::id)
        .isEqualTo(11L);
    assertThat(controller.download(11L, user).getBody().contentLength()).isEqualTo(8);

    controller.delete(11L, user);
    verify(repository).delete(record);
    assertThat(Files.list(temp)).isEmpty();
  }

  @Test
  void controllerEnforcesWorkflowAndDeletePermissions() throws Exception {
    AttachmentRepository repository = mock(AttachmentRepository.class);
    AttachmentStorage storage = new AttachmentStorage(temp.toString());
    AuthenticatedUser uploader = new AuthenticatedUser(7L, "uploader", "REQUESTER");

    startAccessServer(false);
    AttachmentController denied = controller(repository, storage);
    assertThatThrownBy(() -> denied.list(5L, uploader))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    stopServer();
    server = null;

    startAccessServer(true);
    AttachmentController allowed = controller(repository, storage);
    AttachmentResponse response =
        new AttachmentResponse(11L, 5L, "uploader", "proof.txt", "text/plain", 0, "hash", java.time.Instant.now());
    AttachmentRecord record = record(11L, 5L, uploader, response, storage);
    when(repository.findById(11L)).thenReturn(Optional.of(record));
    AuthenticatedUser other = new AuthenticatedUser(9L, "other", "REQUESTER");
    assertThatThrownBy(() -> allowed.delete(11L, other))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    record.uploaderId = null;
    assertThatThrownBy(() -> allowed.delete(11L, other))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    when(repository.findById(99L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> allowed.download(99L, other))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void upload_rejectsPathTraversalFilename() throws Exception {
    startAccessServer(true);
    AttachmentRepository repository = mock(AttachmentRepository.class);
    AttachmentStorage storage = new AttachmentStorage(temp.toString());
    AttachmentController controller = controller(repository, storage);
    AuthenticatedUser user = new AuthenticatedUser(7L, "requester", "REQUESTER");

    assertThatThrownBy(
            () ->
                controller.upload(
                    5L,
                    new MockMultipartFile("file", "../secret.txt", "text/plain", "x".getBytes()),
                    user))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void upload_usesDefaultNameForNullOriginalFilename() throws Exception {
    startAccessServer(true);
    AttachmentRepository repository = mock(AttachmentRepository.class);
    AttachmentStorage storage = new AttachmentStorage(temp.toString());
    AttachmentController controller = controller(repository, storage);
    AuthenticatedUser user = new AuthenticatedUser(7L, "requester", "REQUESTER");
    when(repository.save(any(AttachmentRecord.class)))
        .thenAnswer(
            inv -> {
              AttachmentRecord r = inv.getArgument(0);
              r.id = 1L;
              return r;
            });

    AttachmentResponse response =
        controller.upload(
            5L, new MockMultipartFile("file", null, "text/plain", "data".getBytes()), user);
    assertThat(response.originalName()).isEqualTo("attachment");
  }

  @Test
  void upload_deletesStoredFileWhenRepositoryThrows() throws Exception {
    startAccessServer(true);
    AttachmentRepository repository = mock(AttachmentRepository.class);
    AttachmentStorage storage = new AttachmentStorage(temp.toString());
    AttachmentController controller = controller(repository, storage);
    AuthenticatedUser user = new AuthenticatedUser(7L, "requester", "REQUESTER");
    when(repository.save(any())).thenThrow(new RuntimeException("DB down"));

    assertThatThrownBy(
            () ->
                controller.upload(
                    5L,
                    new MockMultipartFile("file", "proof.txt", "text/plain", "data".getBytes()),
                    user))
        .isInstanceOf(RuntimeException.class);
    assertThat(Files.list(temp)).isEmpty();
  }

  @Test
  void delete_adminCanDeleteAnothersAttachment() throws Exception {
    startAccessServer(true);
    AttachmentRepository repository = mock(AttachmentRepository.class);
    AttachmentStorage storage = new AttachmentStorage(temp.toString());
    AttachmentController controller = controller(repository, storage);
    AuthenticatedUser uploader = new AuthenticatedUser(7L, "uploader", "REQUESTER");
    AuthenticatedUser admin = new AuthenticatedUser(99L, "admin", "ADMIN");

    StoredFile stored =
        storage.save(
            new MockMultipartFile("file", "doc.txt", "text/plain", "hello".getBytes()));
    AttachmentRecord record =
        new AttachmentRecord(5L, uploader, "doc.txt", stored.key(), "text/plain", 5, stored.sha256());
    record.id = 22L;
    when(repository.findById(22L)).thenReturn(Optional.of(record));

    controller.delete(22L, admin);
    verify(repository).delete(record);
    assertThat(Files.list(temp)).isEmpty();
  }

  @Test
  void storageRejectsPathTraversalKeyOnDelete() throws Exception {
    AttachmentStorage storage = new AttachmentStorage(temp.toString());
    assertThatThrownBy(() -> storage.delete("../../../etc/shadow"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private AttachmentController controller(
      AttachmentRepository repository, AttachmentStorage storage) {
    RestClient workflows = RestClient.builder().baseUrl(serverUrl()).build();
    return new AttachmentController(repository, storage, workflows, "internal-test-key");
  }

  private AttachmentRecord record(
      Long id,
      Long workflowId,
      AuthenticatedUser user,
      AttachmentResponse response,
      AttachmentStorage storage) {
    StoredFile stored =
        storage.save(
            new MockMultipartFile(
                "file", response.originalName(), response.contentType(), "evidence".getBytes()));
    AttachmentRecord record =
        new AttachmentRecord(
            workflowId,
            user,
            response.originalName(),
            stored.key(),
            response.contentType(),
            8,
            stored.sha256());
    record.id = id;
    return record;
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
