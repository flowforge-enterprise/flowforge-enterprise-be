package com.cellead.workflowservice;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class OutboxRetryPolicy {
  private static final int MAX_EXPONENT = 8;
  private static final long MAX_DELAY_SECONDS = 300;

  Duration nextDelay(int attempts) {
    int safeAttempts = Math.max(0, attempts);
    long seconds = Math.min(MAX_DELAY_SECONDS, 1L << Math.min(safeAttempts, MAX_EXPONENT));
    return Duration.ofSeconds(seconds);
  }

  String safeError(Throwable error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      message = error.getClass().getSimpleName();
    }
    return message.length() <= 1000 ? message : message.substring(0, 1000);
  }
}
