package com.cellead.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTest {
  private static final String SECRET = "test-only-jwt-secret-at-least-32-characters-long";

  @Test
  void contextLoads() {}

  @Test
  void correlationFilterPreservesOrCreatesCorrelationId() {
    var filter = new ApiGatewayApplication().correlationIdFilter();
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    GatewayFilterChain chain = exchange -> {
      forwarded.set(exchange);
      return Mono.empty();
    };

    var supplied = exchange(MockServerHttpRequest.get("/api/workflows").header("X-Correlation-ID", "trace-1"));
    filter.filter(supplied, chain).block();
    assertEquals("trace-1", forwarded.get().getRequest().getHeaders().getFirst("X-Correlation-ID"));
    assertEquals("trace-1", supplied.getResponse().getHeaders().getFirst("X-Correlation-ID"));

    var generated = exchange(MockServerHttpRequest.get("/api/workflows"));
    filter.filter(generated, chain).block();
    String generatedId = generated.getResponse().getHeaders().getFirst("X-Correlation-ID");
    assertNotNull(generatedId);
    assertFalse(generatedId.isBlank());
  }

  @Test
  void securityResponseHeadersPreventSensitiveResponsesFromBeingCached() {
    var filter = new ApiGatewayApplication().securityResponseHeaders();
    var current = exchange(MockServerHttpRequest.get("/missing"));

    filter.filter(current, ignored -> Mono.empty()).block();

    assertEquals(
        "no-store, no-cache, must-revalidate",
        current.getResponse().getHeaders().getCacheControl());
    assertEquals("no-cache", current.getResponse().getHeaders().getPragma());
    assertEquals(0, current.getResponse().getHeaders().getExpires());
    assertTrue(
        current
            .getResponse()
            .getHeaders()
            .getFirst("Content-Security-Policy")
            .contains("frame-ancestors 'none'"));
    assertEquals(
        "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
        current.getResponse().getHeaders().getFirst("Permissions-Policy"));
    assertEquals(
        "nosniff", current.getResponse().getHeaders().getFirst("X-Content-Type-Options"));
    assertEquals(
        "same-origin",
        current.getResponse().getHeaders().getFirst("Cross-Origin-Resource-Policy"));
  }

  @Test
  void securityFilterAllowsPublicAndValidAccessTokens() {
    var filter = new GatewaySecurityFilter(SECRET, new ObjectMapper());
    AtomicBoolean called = new AtomicBoolean();
    GatewayFilterChain chain = exchange -> {
      called.set(true);
      return Mono.empty();
    };

    filter.filter(exchange(MockServerHttpRequest.post("/api/auth/login")), chain).block();
    assertTrue(called.get());

    called.set(false);
    String token = token("access", "ADMIN");
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    filter
        .filter(
            exchange(
                MockServerHttpRequest.get("/api/workflows")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
            value -> {
              called.set(true);
              forwarded.set(value);
              return Mono.empty();
            })
        .block();
    assertTrue(called.get());
    assertEquals("user-1", forwarded.get().getRequest().getHeaders().getFirst("X-Authenticated-User"));
    assertEquals("ADMIN", forwarded.get().getRequest().getHeaders().getFirst("X-Authenticated-Role"));
    assertEquals(-90, filter.getOrder());
  }

  @Test
  void securityFilterRejectsMissingInvalidAndRefreshTokens() {
    var filter = new GatewaySecurityFilter(SECRET, new ObjectMapper());
    GatewayFilterChain chain = exchange -> Mono.error(new AssertionError("must not forward"));

    var missing = exchange(MockServerHttpRequest.get("/api/workflows"));
    filter.filter(missing, chain).block();
    assertEquals(HttpStatus.UNAUTHORIZED, missing.getResponse().getStatusCode());
    assertTrue(missing.getResponse().getBodyAsString().block().contains("Authentication is required"));

    var refresh = authorizedExchange(token("refresh", "ADMIN"));
    filter.filter(refresh, chain).block();
    assertTrue(refresh.getResponse().getBodyAsString().block().contains("Access token required"));

    var invalid = authorizedExchange("not-a-jwt");
    filter.filter(invalid, chain).block();
    assertTrue(invalid.getResponse().getBodyAsString().block().contains("invalid or expired"));
  }

  @Test
  void rateLimitFilterAllowsThenThrottlesRequests() {
    var filter = new GatewayRateLimitFilter(1);
    AtomicBoolean called = new AtomicBoolean();
    GatewayFilterChain chain = exchange -> {
      called.set(true);
      return Mono.empty();
    };
    var request =
        MockServerHttpRequest.get("/api/workflows")
            .remoteAddress(new InetSocketAddress("127.0.0.1", 12345));

    var first = exchange(request);
    filter.filter(first, chain).block();
    assertTrue(called.get());
    assertEquals("1", first.getResponse().getHeaders().getFirst("X-RateLimit-Limit"));

    called.set(false);
    var second = exchange(request);
    filter.filter(second, chain).block();
    assertFalse(called.get());
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());
    assertEquals(-80, filter.getOrder());
  }

  @Test
  void fallbackReturnsServiceUnavailableDetails() {
    var response = new GatewayFallbackController().fallback("workflow");

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertEquals("SERVICE_UNAVAILABLE", response.getBody().get("error"));
    assertEquals("workflow service is temporarily unavailable", response.getBody().get("message"));
  }

  private MockServerWebExchange authorizedExchange(String token) {
    return exchange(
        MockServerHttpRequest.get("/api/workflows")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
  }

  private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
    return MockServerWebExchange.from(request);
  }

  private String token(String type, String role) {
    var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now();
    return Jwts.builder()
        .subject("user-1")
        .claim("role", role)
        .claim("token_type", type)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(300)))
        .signWith(key)
        .compact();
  }
}
