package com.cellead.gateway;

import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
// API gateway entry point for CI smoke validation.
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
}
