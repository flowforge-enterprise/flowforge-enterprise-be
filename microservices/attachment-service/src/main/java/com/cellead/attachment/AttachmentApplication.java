package com.cellead.attachment;

import com.cellead.platform.security.AuthenticatedUser;
import com.cellead.platform.security.CorrelationIdFilter;
import com.cellead.platform.security.JwtAuthenticationFilter;
import com.cellead.platform.security.JwtService;
import com.cellead.platform.security.PlatformExceptionHandler;
import com.cellead.platform.security.SecurityJsonHandlers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
@Import(PlatformExceptionHandler.class)
public class AttachmentApplication {
  public static void main(String[] args) {
    SpringApplication.run(AttachmentApplication.class, args);
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
  RestClient workflowClient(@Value("${app.workflow-url}") String url) {
    return RestClient.builder().baseUrl(url).build();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, JwtService jwt, ObjectMapper mapper)
      throws Exception {
    return http.csrf(c -> c.ignoringRequestMatchers("/api/**"))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(h -> SecurityJsonHandlers.configure(h, mapper))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(
            new JwtAuthenticationFilter(jwt), UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}

@Entity
@Table(name = "attachments")
class AttachmentRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false)
  Long workflowId;

  @Column(nullable = false)
  Long uploaderId;

  @Column(nullable = false)
  String uploaderUsername;

  @Column(nullable = false)
  String originalName;

  @Column(nullable = false, unique = true)
  String storageKey;

  @Column(nullable = false)
  String contentType;

  @Column(nullable = false)
  long sizeBytes;

  @Column(nullable = false, length = 64)
  String sha256;

  @Column(nullable = false)
  Instant createdAt = Instant.now();

  protected AttachmentRecord() {}

  AttachmentRecord(
      Long workflowId,
      AuthenticatedUser user,
      String originalName,
      String storageKey,
      String contentType,
      long sizeBytes,
      String sha256) {
    this.workflowId = workflowId;
    this.uploaderId = user.id();
    this.uploaderUsername = user.username();
    this.originalName = originalName;
    this.storageKey = storageKey;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.sha256 = sha256;
  }
}

interface AttachmentRepository extends JpaRepository<AttachmentRecord, Long> {
  List<AttachmentRecord> findByWorkflowIdOrderByCreatedAtDesc(Long workflowId);
}

record AttachmentResponse(
    Long id,
    Long workflowId,
    String uploaderUsername,
    String originalName,
    String contentType,
    long sizeBytes,
    String sha256,
    Instant createdAt) {
  static AttachmentResponse from(AttachmentRecord a) {
    return new AttachmentResponse(
        a.id,
        a.workflowId,
        a.uploaderUsername,
        a.originalName,
        a.contentType,
        a.sizeBytes,
        a.sha256,
        a.createdAt);
  }
}

record AccessResponse(boolean allowed) {}

@Service
class AttachmentStorage {
  private static final Set<String> ALLOWED_TYPES =
      Set.of(
          "application/pdf",
          "image/png",
          "image/jpeg",
          "text/plain",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
  private final Path root;

  AttachmentStorage(@Value("${app.storage-path}") String storagePath) throws IOException {
    root = Path.of(storagePath).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  StoredFile save(MultipartFile file) {
    if (file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
    }
    String type =
        file.getContentType() == null
            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
            : file.getContentType();
    if (!ALLOWED_TYPES.contains(type)) {
      throw new ResponseStatusException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported attachment type");
    }
    String key = UUID.randomUUID().toString();
    Path target = resolve(key);
    try (InputStream input = file.getInputStream()) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (DigestInputStream hashed = new DigestInputStream(input, digest)) {
        Files.copy(hashed, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return new StoredFile(key, HexFormat.of().formatHex(digest.digest()));
    } catch (Exception ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Could not store attachment", ex);
    }
  }

  Resource load(String key) {
    try {
      Resource resource = new UrlResource(resolve(key).toUri());
      if (!resource.exists()) {
        throw new IOException("missing");
      }
      return resource;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment content not found");
    }
  }

  void delete(String key) {
    try {
      Files.deleteIfExists(resolve(key));
    } catch (IOException ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Could not delete attachment");
    }
  }

  private Path resolve(String key) {
    Path path = root.resolve(key).normalize();
    if (!path.startsWith(root)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid storage key");
    }
    return path;
  }
}

record StoredFile(String key, String sha256) {}

@RestController
@RequestMapping("/api/attachments")
class AttachmentController {
  private final AttachmentRepository attachments;
  private final AttachmentStorage storage;
  private final RestClient workflows;
  private final String key;

  AttachmentController(
      AttachmentRepository attachments,
      AttachmentStorage storage,
      RestClient workflows,
      @Value("${app.internal-key}") String key) {
    this.attachments = attachments;
    this.storage = storage;
    this.workflows = workflows;
    this.key = key;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  AttachmentResponse upload(
      @RequestParam Long workflowId,
      @RequestPart("file") MultipartFile file,
      @AuthenticationPrincipal AuthenticatedUser user) {
    authorize(workflowId, user);
    String name =
        StringUtils.cleanPath(
            file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename());
    if (name.contains("..")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
    }
    StoredFile saved = storage.save(file);
    try {
      return AttachmentResponse.from(
          attachments.save(
              new AttachmentRecord(
                  workflowId,
                  user,
                  name,
                  saved.key(),
                  file.getContentType() == null
                      ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                      : file.getContentType(),
                  file.getSize(),
                  saved.sha256())));
    } catch (RuntimeException ex) {
      storage.delete(saved.key());
      throw ex;
    }
  }

  @GetMapping("/workflow/{workflowId}")
  List<AttachmentResponse> list(
      @PathVariable Long workflowId, @AuthenticationPrincipal AuthenticatedUser user) {
    authorize(workflowId, user);
    return attachments.findByWorkflowIdOrderByCreatedAtDesc(workflowId).stream()
        .map(AttachmentResponse::from)
        .toList();
  }

  @GetMapping("/{id}/content")
  ResponseEntity<Resource> download(
      @PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
    AttachmentRecord a = get(id);
    authorize(a.workflowId, user);
    Resource resource = storage.load(a.storageKey);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(a.contentType))
        .contentLength(a.sizeBytes)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(a.originalName).build().toString())
        .body(resource);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  void delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
    AttachmentRecord a = get(id);
    authorize(a.workflowId, user);
    if (!"ADMIN".equals(user.role()) && !Objects.equals(a.uploaderId, user.id())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only uploader or admin can delete");
    }
    attachments.delete(a);
    storage.delete(a.storageKey);
  }

  private AttachmentRecord get(Long id) {
    return attachments
        .findById(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
  }

  private void authorize(Long workflowId, AuthenticatedUser user) {
    AccessResponse access =
        workflows
            .get()
            .uri(
                uri ->
                    uri.path("/internal/workflows/{id}/access")
                        .queryParam("userId", user.id())
                        .queryParam("role", user.role())
                        .build(workflowId))
            .header("X-Internal-Key", key)
            .retrieve()
            .body(AccessResponse.class);
    if (access == null || !access.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }
}
