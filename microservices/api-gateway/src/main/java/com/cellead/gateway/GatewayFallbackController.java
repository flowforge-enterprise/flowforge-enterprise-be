package com.cellead.gateway;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fallback")
final class GatewayFallbackController {
  @RequestMapping(
      path = "/{service:auth|workflow|notification|ai|attachment}",
      method = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.PATCH,
        RequestMethod.DELETE
      })
  ResponseEntity<Map<String, Object>> fallback(@PathVariable String service) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            Map.of(
                "error",
                "SERVICE_UNAVAILABLE",
                "message",
                service + " service is temporarily unavailable",
                "timestamp",
                Instant.now().toString()));
  }
}
