package com.cellead.auth;

import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class AccountSecurityPolicy {
    private final int maxFailedAttempts;
    private final long lockSeconds;
    private final Clock clock;

    @Autowired
    AccountSecurityPolicy(@Value("${app.login.max-failed-attempts}") int maxFailedAttempts,
                          @Value("${app.login.lock-seconds}") long lockSeconds) {
        this(maxFailedAttempts, lockSeconds, Clock.systemUTC());
    }

    AccountSecurityPolicy(int maxFailedAttempts, long lockSeconds, Clock clock) {
        if (maxFailedAttempts < 1) throw new IllegalArgumentException("maxFailedAttempts must be positive");
        if (lockSeconds < 1) throw new IllegalArgumentException("lockSeconds must be positive");
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockSeconds = lockSeconds;
        this.clock = clock;
    }

    LoginEligibility eligibility(AppUser user) {
        if (!user.active) return new LoginEligibility(false, "DISABLED");
        if (user.lockedUntil != null && user.lockedUntil.isAfter(now())) return new LoginEligibility(false, "LOCKED");
        return new LoginEligibility(true, "ALLOWED");
    }

    void recordFailure(AppUser user) {
        user.failedLoginAttempts++;
        if (user.failedLoginAttempts >= maxFailedAttempts) user.lockedUntil = now().plusSeconds(lockSeconds);
    }

    void recordSuccess(AppUser user) {
        user.failedLoginAttempts = 0;
        user.lockedUntil = null;
    }

    void validatePasswordChange(AppUser user, boolean currentMatches, boolean newMatchesCurrent) {
        if (!currentMatches) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        if (newMatchesCurrent) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different");
        if (!user.active) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Disabled account cannot change password");
    }

    private Instant now() { return clock.instant(); }
}

record LoginEligibility(boolean allowed, String reason) {}
