package com.cellead.ai;

import com.cellead.platform.security.AuthenticatedUser;
import com.cellead.platform.security.CorrelationIdFilter;
import com.cellead.platform.security.JwtAuthenticationFilter;
import com.cellead.platform.security.JwtService;
import com.cellead.platform.security.PlatformExceptionHandler;
import com.cellead.platform.security.SecurityJsonHandlers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
@Import(PlatformExceptionHandler.class)
public class AiAssistantApplication {
  public static void main(String[] args) {
    SpringApplication.run(AiAssistantApplication.class, args);
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
  RestClient.Builder restClientBuilder() {
    return RestClient.builder();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, JwtService jwt, ObjectMapper mapper)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
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

record FormAssistRequest(@NotBlank @Size(max = 4000) String input) {}

record FormAssistResponse(
    String title,
    String description,
    String requestType,
    String priority,
    List<String> missingInformation,
    double confidence,
    String source) {}

record OnCallRequest(
    @NotBlank @Size(max = 2000) String question, @Size(max = 128) String correlationId) {}

record OnCallResponse(
    String severity,
    String summary,
    List<String> evidence,
    List<String> recommendedActions,
    double confidence,
    String source) {}

record Runbook(String id, String title, List<String> signals, List<String> actions) {}

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

@Service
class AiProvider {
  private final boolean enabled;
  private final String apiKey;
  private final String model;
  private final RestClient client;
  private final ObjectMapper mapper;

  AiProvider(
      @Value("${app.ai.enabled:false}") boolean enabled,
      @Value("${app.ai.base-url}") String baseUrl,
      @Value("${app.ai.api-key:}") String apiKey,
      @Value("${app.ai.model}") String model,
      RestClient.Builder builder,
      ObjectMapper mapper) {
    this.enabled = enabled;
    this.apiKey = apiKey;
    this.model = model;
    this.client = builder.baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  boolean available() {
    return enabled && !apiKey.isBlank();
  }

  FormAssistResponse form(String input) {
    String prompt =
        "Return only JSON with fields"
            + " title,description,requestType,priority,missingInformation,confidence. Allowed"
            + " requestType: GENERAL, TRAVEL, PURCHASE. Allowed priority: LOW, MEDIUM, HIGH. User"
            + " input: "
            + input;
    String content = complete(prompt);
    try {
      var node = mapper.readTree(content);
      String type = node.path("requestType").asText("GENERAL").toUpperCase(Locale.ROOT);
      String priority = node.path("priority").asText("MEDIUM").toUpperCase(Locale.ROOT);
      if (!List.of("GENERAL", "TRAVEL", "PURCHASE").contains(type)) {
        type = "GENERAL";
      }
      if (!List.of("LOW", "MEDIUM", "HIGH").contains(priority)) {
        priority = "MEDIUM";
      }
      List<String> missing = new java.util.ArrayList<>();
      node.path("missingInformation").forEach(n -> missing.add(n.asText()));
      return new FormAssistResponse(
          node.path("title").asText(input),
          node.path("description").asText(input),
          type,
          priority,
          missing,
          Math.max(0, Math.min(1, node.path("confidence").asDouble(.5))),
          "LLM");
    } catch (Exception ex) {
      throw new IllegalStateException("AI provider returned invalid structured output", ex);
    }
  }

  private String complete(String prompt) {
    if (!available()) {
      throw new IllegalStateException("AI provider is not configured");
    }
    Map<?, ?> response =
        client
            .post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .body(
                Map.of(
                    "model",
                    model,
                    "temperature",
                    0,
                    "messages",
                    List.of(
                        Map.of(
                            "role",
                            "system",
                            "content",
                            "You are a secure enterprise workflow assistant."),
                        Map.of("role", "user", "content", prompt))))
            .retrieve()
            .body(Map.class);
    try {
      return (String)
          ((Map<?, ?>) ((Map<?, ?>) ((List<?>) response.get("choices")).get(0)).get("message"))
              .get("content");
    } catch (Exception ex) {
      throw new IllegalStateException("AI provider response is incomplete", ex);
    }
  }
}

@Service
class HealthCollector {
  private final Map<String, String> endpoints;
  private final RestClient.Builder builder;

  HealthCollector(
      @Value("${app.services.auth-health}") String auth,
      @Value("${app.services.workflow-health}") String workflow,
      @Value("${app.services.notification-health}") String notification,
      RestClient.Builder builder) {
    endpoints =
        Map.of(
            "auth-service",
            auth,
            "workflow-service",
            workflow,
            "notification-audit-service",
            notification);
    this.builder = builder;
  }

  List<String> collect() {
    List<String> evidence = new java.util.ArrayList<>();
    endpoints.forEach(
        (name, url) -> {
          try {
            Map<?, ?> body = builder.build().get().uri(url).retrieve().body(Map.class);
            evidence.add(name + " health=" + (body == null ? "UNKNOWN" : body.get("status")));
          } catch (RuntimeException ex) {
            evidence.add(name + " health=DOWN (" + ex.getClass().getSimpleName() + ")");
          }
        });
    return evidence;
  }
}

@Service
class AiAuditClient {
  private final RestClient client;
  private final String key;

  AiAuditClient(
      @Value("${app.notification-url}") String url,
      @Value("${app.internal-key}") String key,
      RestClient.Builder builder) {
    this.client = builder.baseUrl(url).build();
    this.key = key;
  }

  void log(String type, AuthenticatedUser user, String details) {
    try {
      client
          .post()
          .uri("/internal/events")
          .header("X-Internal-Key", key)
          .body(
              new DomainEvent(
                  type,
                  null,
                  user.id(),
                  user.username(),
                  null,
                  details,
                  Instant.now(),
                  org.slf4j.MDC.get("correlationId"),
                  java.util.UUID.randomUUID().toString()))
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException ignored) {
    }
  }
}

@RestController
@RequestMapping("/api/ai")
class AiController {
  private final AiProvider provider;
  private final HealthCollector health;
  private final AiAuditClient audit;

  AiController(AiProvider provider, HealthCollector health, AiAuditClient audit) {
    this.provider = provider;
    this.health = health;
    this.audit = audit;
  }

  private static final List<Runbook> RUNBOOKS =
      List.of(
          new Runbook(
              "service-down",
              "Backend service unavailable",
              List.of("health=DOWN", "connection refused"),
              List.of(
                  "Check the service process and recent logs",
                  "Verify database connectivity",
                  "Restart only after identifying the failure cause")),
          new Runbook(
              "high-latency",
              "Slow API responses",
              List.of("timeout", "latency", "slow"),
              List.of(
                  "Use the correlation ID to trace the request",
                  "Check downstream latency and connection pools",
                  "Review recent deployments")),
          new Runbook(
              "event-backlog",
              "Notification delivery delay",
              List.of("outbox", "notification", "backlog"),
              List.of(
                  "Check pending outbox events",
                  "Confirm notification service health",
                  "Inspect retry errors before replay")));

  @GetMapping("/runbooks")
  List<Runbook> runbooks(@AuthenticationPrincipal AuthenticatedUser user) {
    if (!"ADMIN".equals(user.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return RUNBOOKS;
  }

  @PostMapping("/form-assistant")
  FormAssistResponse form(
      @Valid @RequestBody FormAssistRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    if (!"REQUESTER".equals(user.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    String input = request.input().trim();
    String lower = input.toLowerCase(Locale.ROOT);
    if (provider.available()) {
      try {
        FormAssistResponse response = provider.form(input);
        audit.log("AI_FORM_ASSISTED", user, "AI form suggestion generated");
        return response;
      } catch (RuntimeException ex) {
        audit.log("AI_FORM_FAILED", user, ex.getMessage());
      }
    }
    String type =
        lower.contains("travel") || input.contains("出差")
            ? "TRAVEL"
            : lower.contains("purchase") || input.contains("采购") ? "PURCHASE" : "GENERAL";
    String priority = lower.contains("urgent") || input.contains("紧急") ? "HIGH" : "MEDIUM";
    FormAssistResponse response =
        new FormAssistResponse(
            input.length() > 60 ? input.substring(0, 60) : input,
            input,
            type,
            priority,
            List.of("Exact required completion date", "Business justification"),
            0.72,
            "LOCAL_FALLBACK");
    audit.log("AI_FORM_ASSISTED", user, "Local fallback form suggestion generated");
    return response;
  }

  @PostMapping("/on-call")
  OnCallResponse onCall(
      @Valid @RequestBody OnCallRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
    if (!"ADMIN".equals(user.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    List<String> evidence = new java.util.ArrayList<>(health.collect());
    if (request.correlationId() != null && !request.correlationId().isBlank()) {
      evidence.add("Correlation ID: " + request.correlationId());
    }
    long down = evidence.stream().filter(item -> item.contains("DOWN")).count();
    String question = request.question().toLowerCase(Locale.ROOT);
    Runbook selected =
        RUNBOOKS.stream()
            .filter(r -> r.signals().stream().anyMatch(question::contains))
            .findFirst()
            .orElse(down > 0 ? RUNBOOKS.get(0) : RUNBOOKS.get(1));
    OnCallResponse response =
        new OnCallResponse(
            down > 0 ? "HIGH" : "INFO",
            down > 0
                ? down + " backend service(s) are unavailable."
                : "All monitored backend services report UP.",
            evidence,
            selected.actions(),
            down > 0 ? .9 : .85,
            "LIVE_HEALTH_AND_RUNBOOK:" + selected.id());
    audit.log(
        "AI_ONCALL_DIAGNOSED",
        user,
        "On-call diagnosis generated; severity="
            + response.severity()
            + "; runbook="
            + selected.id());
    return response;
  }
}
