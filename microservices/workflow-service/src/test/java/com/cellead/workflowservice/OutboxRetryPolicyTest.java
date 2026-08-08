package com.cellead.workflowservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxRetryPolicyTest {
  private final OutboxRetryPolicy policy = new OutboxRetryPolicy();

  @Test
  void delayUsesCappedExponentialBackoff() {
    assertThat(policy.nextDelay(0)).isEqualTo(Duration.ofSeconds(1));
    assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.nextDelay(5)).isEqualTo(Duration.ofSeconds(32));
    assertThat(policy.nextDelay(20)).isEqualTo(Duration.ofSeconds(256));
  }

  @Test
  void negativeAttemptCountIsSafelyHandled() {
    assertThat(policy.nextDelay(-10)).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void errorMessageIsAlwaysPresentAndDatabaseSafe() {
    assertThat(policy.safeError(new IllegalStateException())).isEqualTo("IllegalStateException");
    assertThat(policy.safeError(new RuntimeException("x".repeat(1500)))).hasSize(1000);
  }
}
