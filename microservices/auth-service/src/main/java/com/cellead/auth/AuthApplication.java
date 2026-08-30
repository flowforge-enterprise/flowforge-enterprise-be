package com.cellead.auth;

import com.cellead.platform.security.ApiError;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

@SpringBootApplication
@EnableMethodSecurity
@Import(PlatformExceptionHandler.class)
public class AuthApplication {
  public static void main(String[] args) {
    SpringApplication.run(AuthApplication.class, args);
  }

  @Bean
  JwtService jwtService(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.expiration-seconds}") long expiration) {
    return new JwtService(secret, expiration);
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  RestClient eventClient(@Value("${app.notification-url}") String url) {
    return RestClient.builder().baseUrl(url).build();
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
                a.requestMatchers(
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/internal/**",
                        "/actuator/health",
                        "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(
            new JwtAuthenticationFilter(jwt), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  CommandLineRunner seed(
      UserRepository users,
      PasswordEncoder encoder,
      @Value("${app.seed-password}") String seedPassword) {
    return args -> {
      if (users.count() == 0) {
        users.save(new AppUser("requester", encoder.encode(seedPassword), Role.REQUESTER));
        users.save(new AppUser("approver", encoder.encode(seedPassword), Role.APPROVER));
        users.save(new AppUser("admin", encoder.encode(seedPassword), Role.ADMIN));
      }
    };
  }
}

enum Role {
  REQUESTER,
  APPROVER,
  ADMIN
}

@Entity
@Table(name = "users")
class AppUser {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false, unique = true)
  String username;

  @Column(nullable = false)
  String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  Role role;

  @Column(nullable = false)
  Instant createdAt = Instant.now();

  @Column(nullable = false)
  boolean active = true;

  @Column(nullable = false)
  int failedLoginAttempts = 0;

  Instant lockedUntil;

  @Column(nullable = false)
  Instant passwordChangedAt = Instant.now();

  protected AppUser() {}

  AppUser(String username, String passwordHash, Role role) {
    this.username = username;
    this.passwordHash = passwordHash;
    this.role = role;
  }
}

interface UserRepository extends JpaRepository<AppUser, Long> {
  Optional<AppUser> findByUsername(String username);
}

record LoginRequest(@NotBlank String username, @NotBlank String password) {}

record UserResponse(Long id, String username, Role role, boolean active, Instant lockedUntil) {
  static UserResponse from(AppUser u) {
    return new UserResponse(u.id, u.username, u.role, u.active, u.lockedUntil);
  }
}

record LoginResponse(String token, String refreshToken, long expiresIn, UserResponse user) {}

record RefreshRequest(@NotBlank String refreshToken) {}

record ChangePasswordRequest(
    @NotBlank String currentPassword, @NotBlank @Size(min = 12, max = 128) String newPassword) {}

record UserStatusRequest(boolean active) {}

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

@RestController
class AuthController {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final JwtService jwt;
  private final RestClient events;
  private final String internalKey;
  private final long accessExpiration;
  private final long refreshExpiration;
  private final AccountSecurityPolicy accountPolicy;

  AuthController(
      UserRepository users,
      PasswordEncoder encoder,
      JwtService jwt,
      RestClient events,
      @Value("${app.internal-key}") String internalKey,
      @Value("${app.jwt.expiration-seconds}") long accessExpiration,
      @Value("${app.jwt.refresh-expiration-seconds}") long refreshExpiration,
      AccountSecurityPolicy accountPolicy) {
    this.users = users;
    this.encoder = encoder;
    this.jwt = jwt;
    this.events = events;
    this.internalKey = internalKey;
    this.accessExpiration = accessExpiration;
    this.refreshExpiration = refreshExpiration;
    this.accountPolicy = accountPolicy;
  }

  @PostMapping("/api/auth/login")
  LoginResponse login(@Valid @RequestBody LoginRequest body) {
    AppUser user =
        users
            .findByUsername(body.username())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
    LoginEligibility eligibility = accountPolicy.eligibility(user);
    if (!eligibility.allowed()) {
      audit("USER_LOGIN_FAILED", user, eligibility.reason() + " account login attempt");
      throw new BadCredentialsException("Invalid credentials");
    }
    if (!encoder.matches(body.password(), user.passwordHash)) {
      accountPolicy.recordFailure(user);
      users.save(user);
      audit("USER_LOGIN_FAILED", user, "Invalid password");
      throw new BadCredentialsException("Invalid credentials");
    }
    accountPolicy.recordSuccess(user);
    users.save(user);
    LoginResponse response = tokens(user);
    audit("USER_LOGIN", user, "User logged in");
    return response;
  }

  @PostMapping("/api/auth/refresh")
  LoginResponse refresh(@Valid @RequestBody RefreshRequest body) {
    AuthenticatedUser principal;
    try {
      principal = jwt.parseRefresh(body.refreshToken());
    } catch (RuntimeException ex) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
    }
    AppUser user =
        users
            .findById(principal.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    if (!user.active) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is disabled");
    }
    audit("TOKEN_REFRESHED", user, "Access token refreshed");
    return tokens(user);
  }

  @PostMapping("/api/auth/change-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void changePassword(
      @Valid @RequestBody ChangePasswordRequest body,
      @AuthenticationPrincipal AuthenticatedUser principal) {
    AppUser user =
        users
            .findById(principal.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    accountPolicy.validatePasswordChange(
        user,
        encoder.matches(body.currentPassword(), user.passwordHash),
        encoder.matches(body.newPassword(), user.passwordHash));
    user.passwordHash = encoder.encode(body.newPassword());
    user.passwordChangedAt = Instant.now();
    users.save(user);
    audit("PASSWORD_CHANGED", user, "Password changed");
  }

  @GetMapping({"/api/auth/me", "/api/users/me"})
  UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
    return users
        .findById(principal.id())
        .map(UserResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @GetMapping("/api/users")
  List<UserResponse> users(@AuthenticationPrincipal AuthenticatedUser principal) {
    if (!"ADMIN".equals(principal.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return users.findAll().stream().map(UserResponse::from).toList();
  }

  @PatchMapping("/api/users/{id}/status")
  UserResponse status(
      @PathVariable Long id,
      @RequestBody UserStatusRequest body,
      @AuthenticationPrincipal AuthenticatedUser principal) {
    if (!"ADMIN".equals(principal.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    AppUser user =
        users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (user.id.equals(principal.id()) && !body.active()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Admin cannot disable self");
    }
    user.active = body.active();
    if (!user.active) {
      user.lockedUntil = null;
    }
    UserResponse response = UserResponse.from(users.save(user));
    audit(
        "USER_STATUS_CHANGED",
        users.findById(principal.id()).orElse(user),
        "User " + id + " active=" + body.active());
    return response;
  }

  @GetMapping("/internal/users/{id}")
  UserResponse internalUser(
      @PathVariable Long id,
      @RequestHeader("X-Internal-Key") String key,
      @Value("${app.internal-key}") String expected) {
    if (!expected.equals(key)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return users
        .findById(id)
        .map(UserResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @GetMapping("/internal/users/first")
  UserResponse firstByRole(
      @RequestParam Role role,
      @RequestHeader("X-Internal-Key") String key,
      @Value("${app.internal-key}") String expected) {
    if (!expected.equals(key)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return users.findAll().stream()
        .filter(user -> user.role == role)
        .findFirst()
        .map(UserResponse::from)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No user for role " + role));
  }

  @ExceptionHandler(BadCredentialsException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  ApiError unauthorized() {
    return ApiError.of("UNAUTHORIZED", "Invalid username or password");
  }

  private LoginResponse tokens(AppUser user) {
    return new LoginResponse(
        jwt.generate(user.id, user.username, user.role.name()),
        jwt.generateRefresh(user.id, user.username, user.role.name(), refreshExpiration),
        accessExpiration,
        UserResponse.from(user));
  }

  private void audit(String type, AppUser user, String message) {
    try {
      events
          .post()
          .uri("/internal/events")
          .header("X-Internal-Key", internalKey)
          .body(
              new DomainEvent(
                  type,
                  null,
                  user.id,
                  user.username,
                  null,
                  message,
                  Instant.now(),
                  org.slf4j.MDC.get("correlationId"),
                  java.util.UUID.randomUUID().toString()))
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException ignored) {
    }
  }
}
