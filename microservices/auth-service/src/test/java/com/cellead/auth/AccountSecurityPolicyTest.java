package com.cellead.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AccountSecurityPolicyTest {
    private final Instant now = Instant.parse("2026-07-28T12:00:00Z");
    private final AccountSecurityPolicy policy = new AccountSecurityPolicy(3, 900, Clock.fixed(now, ZoneOffset.UTC));
    private AppUser user;

    @BeforeEach
    void setUp() { user = new AppUser("requester", "hash", Role.REQUESTER); }

    @Test
    void activeUnlockedAccountCanLogin() {
        assertThat(policy.eligibility(user)).isEqualTo(new LoginEligibility(true, "ALLOWED"));
    }

    @Test
    void disabledAndLockedAccountsCannotLogin() {
        user.active = false;
        assertThat(policy.eligibility(user)).isEqualTo(new LoginEligibility(false, "DISABLED"));
        user.active = true;
        user.lockedUntil = now.plusSeconds(10);
        assertThat(policy.eligibility(user)).isEqualTo(new LoginEligibility(false, "LOCKED"));
    }

    @Test
    void expiredLockDoesNotBlockLogin() {
        user.lockedUntil = now.minusSeconds(1);
        assertThat(policy.eligibility(user).allowed()).isTrue();
    }

    @Test
    void repeatedFailuresLockAccountForConfiguredDuration() {
        policy.recordFailure(user);
        policy.recordFailure(user);
        assertThat(user.lockedUntil).isNull();
        policy.recordFailure(user);
        assertThat(user.failedLoginAttempts).isEqualTo(3);
        assertThat(user.lockedUntil).isEqualTo(now.plusSeconds(900));
    }

    @Test
    void successfulLoginClearsFailureState() {
        user.failedLoginAttempts = 4;
        user.lockedUntil = now.plusSeconds(100);
        policy.recordSuccess(user);
        assertThat(user.failedLoginAttempts).isZero();
        assertThat(user.lockedUntil).isNull();
    }

    @Test
    void passwordChangeRejectsWrongCurrentAndReusedPassword() {
        assertThatThrownBy(() -> policy.validatePasswordChange(user, false, false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Current password");
        assertThatThrownBy(() -> policy.validatePasswordChange(user, true, true))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("different");
    }

    @Test
    void disabledAccountCannotChangePassword() {
        user.active = false;
        assertThatThrownBy(() -> policy.validatePasswordChange(user, true, false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Disabled");
    }
}
