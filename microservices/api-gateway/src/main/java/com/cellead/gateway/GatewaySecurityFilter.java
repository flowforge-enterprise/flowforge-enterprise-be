package com.cellead.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
final class GatewaySecurityFilter implements GlobalFilter, Ordered {
  private final SecretKey key;
  private final ObjectMapper mapper;

  GatewaySecurityFilter(@Value("${app.jwt-secret}") String secret, ObjectMapper mapper) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.mapper = mapper;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    if (isPublic(path)) {
      return chain.filter(exchange);
    }
    String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith("Bearer ")) {
      return unauthorized(exchange, "Authentication is required");
    }
    try {
      var claims =
          Jwts.parser().verifyWith(key).build().parseSignedClaims(header.substring(7)).getPayload();
      if (!"access".equals(claims.get("token_type", String.class))) {
        return unauthorized(exchange, "Access token required");
      }
      var request =
          exchange
              .getRequest()
              .mutate()
              .header("X-Authenticated-User", claims.getSubject())
              .header("X-Authenticated-Role", claims.get("role", String.class))
              .build();
      return chain.filter(exchange.mutate().request(request).build());
    } catch (RuntimeException ex) {
      return unauthorized(exchange, "Token is invalid or expired");
    }
  }

  private boolean isPublic(String path) {
    return path.equals("/api/auth/login")
        || path.equals("/api/auth/refresh")
        || path.startsWith("/internal/regulatory/")
        || path.startsWith("/actuator/")
        || path.startsWith("/docs/")
        || path.startsWith("/swagger-ui")
        || path.startsWith("/webjars/")
        || path.equals("/v3/api-docs/swagger-config");
  }

  private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("error", "UNAUTHORIZED");
      body.put("message", message);
      body.put("timestamp", Instant.now().toString());
      body.put(
          "correlationId", exchange.getResponse().getHeaders().getFirst("X-Correlation-ID"));
      byte[] bytes = mapper.writeValueAsBytes(body);
      DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
      return exchange.getResponse().writeWith(Mono.just(buffer));
    } catch (Exception ex) {
      return exchange.getResponse().setComplete();
    }
  }

  @Override
  public int getOrder() {
    return -90;
  }
}
