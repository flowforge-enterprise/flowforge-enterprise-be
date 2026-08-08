package com.cellead.gateway;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
final class GatewayRateLimitFilter implements GlobalFilter, Ordered {
  private record Window(long minute, AtomicInteger count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final int limit;

  GatewayRateLimitFilter(@Value("${app.rate-limit-per-minute:120}") int limit) {
    this.limit = limit;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String key =
        exchange.getRequest().getRemoteAddress() == null
            ? "unknown"
            : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    long minute = Instant.now().getEpochSecond() / 60;
    Window window =
        windows.compute(
            key,
            (ignored, current) ->
                current == null || current.minute() != minute
                    ? new Window(minute, new AtomicInteger(1))
                    : new Window(minute, new AtomicInteger(current.count().incrementAndGet())));
    exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(limit));
    if (window.count().get() > limit) {
      exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
      return exchange.getResponse().setComplete();
    }
    if (windows.size() > 10_000) {
      windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute - 1);
    }
    return chain.filter(exchange);
  }

  @Override
  public int getOrder() {
    return -80;
  }
}
