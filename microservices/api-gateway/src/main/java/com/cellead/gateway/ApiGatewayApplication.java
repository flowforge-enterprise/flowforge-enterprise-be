package com.cellead.gateway;

import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.WebFilter;


@SpringBootApplication
public class ApiGatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(ApiGatewayApplication.class, args);
  }
  @Bean
  GlobalFilter correlationIdFilter() {
    return (exchange, chain) -> {
      String incoming = exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
      String id = incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;
      var request = exchange.getRequest().mutate().header("X-Correlation-ID", id).build();
      exchange.getResponse().getHeaders().set("X-Correlation-ID", id);
      return chain.filter(exchange.mutate().request(request).build());
    };
  }

  @Bean
  WebFilter securityResponseHeaders() {
    return (exchange, chain) -> {
      HttpHeaders headers = exchange.getResponse().getHeaders();
      headers.setCacheControl("no-store, no-cache, must-revalidate");
      headers.setPragma("no-cache");
      headers.setExpires(0);
      headers.set(
          "Content-Security-Policy",
          "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
              + "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
              + "frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'");
      headers.set(
          "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
      headers.set("X-Content-Type-Options", "nosniff");
      headers.set("Cross-Origin-Resource-Policy", "same-origin");
      return chain.filter(exchange);
    };
  }
}
