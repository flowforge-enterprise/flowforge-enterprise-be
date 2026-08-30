package com.cellead.notification;

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
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@SpringBootApplication
@Import(PlatformExceptionHandler.class)
public class NotificationAuditApplication {
  public static void main(String[] args) {
    SpringApplication.run(NotificationAuditApplication.class, args);
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
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(h -> SecurityJsonHandlers.configure(h, mapper))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/internal/**", "/actuator/health", "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(
            new JwtAuthenticationFilter(jwt), UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}

@org.springframework.context.annotation.Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic");
    registry.setApplicationDestinationPrefixes("/app");
  }

  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
  }
}

@Entity
@Table(name = "notification_records")
class NotificationRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false)
  Long workflowId;

  @Column(nullable = false)
  Long recipientId;

  @Column(nullable = false, length = 2000)
  String message;

  @Column(nullable = false)
  String channel = "IN_APP";

  @Column(nullable = false)
  String status = "SENT";

  @Column(nullable = false)
  boolean readFlag = false;

  Instant readAt;

  @Column(nullable = false)
  Instant createdAt = Instant.now();

  protected NotificationRecord() {}

  NotificationRecord(DomainEvent e) {
    workflowId = e.workflowId();
    recipientId = e.recipientId();
    message = e.message();
  }
}

@Entity
@Table(name = "audit_logs")
class AuditLog {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false)
  String action;

  @Column(nullable = false)
  Long actorId;

  @Column(nullable = false)
  String actorUsername;

  Long workflowId;

  @Column(length = 2000)
  String details;

  String correlationId;

  @Column(nullable = false, unique = true)
  String eventId;

  @Column(nullable = false)
  Instant createdAt;

  protected AuditLog() {}

  AuditLog(DomainEvent e) {
    action = e.type();
    actorId = e.actorId();
    actorUsername = e.actorUsername();
    workflowId = e.workflowId();
    details = e.message();
    createdAt = e.occurredAt();
    correlationId = e.correlationId();
    eventId = e.eventId();
  }
}

interface NotificationRepository extends JpaRepository<NotificationRecord, Long> {
  List<NotificationRecord> findByRecipientIdOrderByCreatedAtDesc(Long id);

  Page<NotificationRecord> findByRecipientId(Long id, Pageable pageable);

  long countByRecipientIdAndReadFlagFalse(Long id);

  java.util.Optional<NotificationRecord> findByIdAndRecipientId(Long id, Long recipientId);
}

interface AuditRepository extends JpaRepository<AuditLog, Long> {
  List<AuditLog> findByWorkflowIdOrderByCreatedAtDesc(Long id);

  boolean existsByEventId(String eventId);
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

record NotificationResponse(
    Long id,
    Long workflowId,
    Long recipientId,
    String channel,
    String message,
    String status,
    boolean read,
    Instant readAt,
    Instant createdAt) {
  static NotificationResponse from(NotificationRecord n) {
    return new NotificationResponse(
        n.id,
        n.workflowId,
        n.recipientId,
        n.channel,
        n.message,
        n.status,
        n.readFlag,
        n.readAt,
        n.createdAt);
  }
}

record AuditResponse(
    Long id,
    String action,
    Long actorId,
    String actorUsername,
    Long workflowId,
    String details,
    Instant createdAt,
    String correlationId) {
  static AuditResponse from(AuditLog a) {
    return new AuditResponse(
        a.id,
        a.action,
        a.actorId,
        a.actorUsername,
        a.workflowId,
        a.details,
        a.createdAt,
        a.correlationId);
  }
}

record AccessResponse(boolean allowed) {}

record UnreadCount(long count) {}

@RestController
@Transactional
class NotificationAuditController {
  private final NotificationRepository notifications;
  private final AuditRepository audits;
  private final String key;
  private final RestClient workflows;
  private final SimpMessageSendingOperations messaging;

  NotificationAuditController(
      NotificationRepository notifications,
      AuditRepository audits,
      @Value("${app.internal-key}") String key,
      RestClient workflows,
      SimpMessageSendingOperations messaging) {
    this.notifications = notifications;
    this.audits = audits;
    this.key = key;
    this.workflows = workflows;
    this.messaging = messaging;
  }

  @PostMapping("/internal/events")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void event(@RequestHeader("X-Internal-Key") String supplied, @RequestBody DomainEvent event) {
    if (!key.equals(supplied)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    if (event.eventId() == null || event.eventId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventId is required");
    }
    if (audits.existsByEventId(event.eventId())) {
      return;
    }
    audits.save(new AuditLog(event));
    if (event.recipientId() != null && event.message() != null) {
      NotificationResponse created =
          NotificationResponse.from(notifications.save(new NotificationRecord(event)));
      messaging.convertAndSend("/topic/notifications/" + event.recipientId(), created);
    }
  }

  @GetMapping("/api/notifications/my")
  List<NotificationResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
    return notifications.findByRecipientIdOrderByCreatedAtDesc(user.id()).stream()
        .map(NotificationResponse::from)
        .toList();
  }

  @GetMapping("/api/notifications/my/page")
  Page<NotificationResponse> minePage(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal AuthenticatedUser user) {
    if (page < 0 || size < 1 || size > 100) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
    }
    return notifications
        .findByRecipientId(
            user.id(),
            org.springframework.data.domain.PageRequest.of(
                page,
                size,
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
        .map(NotificationResponse::from);
  }

  @GetMapping("/api/notifications/unread-count")
  UnreadCount unread(@AuthenticationPrincipal AuthenticatedUser user) {
    return new UnreadCount(notifications.countByRecipientIdAndReadFlagFalse(user.id()));
  }

  @GetMapping("/api/notifications")
  List<NotificationResponse> all(@AuthenticationPrincipal AuthenticatedUser user) {
    admin(user);
    return notifications.findAll().stream().map(NotificationResponse::from).toList();
  }

  @PatchMapping("/api/notifications/{id}/read")
  NotificationResponse markRead(
      @PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
    NotificationRecord record =
        notifications
            .findByIdAndRecipientId(id, user.id())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
    record.readFlag = true;
    record.readAt = Instant.now();
    return NotificationResponse.from(notifications.save(record));
  }

  @PatchMapping("/api/notifications/read-all")
  List<NotificationResponse> markAllRead(@AuthenticationPrincipal AuthenticatedUser user) {
    List<NotificationRecord> records =
        notifications.findByRecipientIdOrderByCreatedAtDesc(user.id());
    Instant now = Instant.now();
    records.forEach(
        record -> {
          record.readFlag = true;
          record.readAt = now;
        });
    return notifications.saveAll(records).stream().map(NotificationResponse::from).toList();
  }

  @GetMapping("/api/audit-logs")
  List<AuditResponse> audits(
      @RequestParam(required = false) Long workflowId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    if (workflowId == null) {
      admin(user);
      return audits.findAll().stream().map(AuditResponse::from).toList();
    }
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
    return audits.findByWorkflowIdOrderByCreatedAtDesc(workflowId).stream()
        .map(AuditResponse::from)
        .toList();
  }

  private void admin(AuthenticatedUser user) {
    if (user == null || !"ADMIN".equals(user.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }
}
