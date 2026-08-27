package com.cellead.gateway;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class GatewayInfoController {
  @GetMapping("/")
  Map<String, String> info() {
    return Map.of("service", "api-gateway", "status", "UP");
  }
}
