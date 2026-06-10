package com.maogou.stock.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.function.Supplier;

public final class AuthContext {

    private static final long DEFAULT_USER_ID = 1L;
    private static final ThreadLocal<Long> USER_OVERRIDE = new ThreadLocal<>();

    private AuthContext() {
    }

    public static Optional<Long> currentUserId() {
        Long override = USER_OVERRIDE.get();
        if (override != null) {
            return Optional.of(override);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.ofNullable(principal.userId());
    }

    public static long currentUserIdOrDefault() {
        return currentUserId().orElse(DEFAULT_USER_ID);
    }

    public static void runAs(Long userId, Runnable action) {
        callAs(userId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAs(Long userId, Supplier<T> action) {
        Long previous = USER_OVERRIDE.get();
        if (userId == null) {
            USER_OVERRIDE.remove();
        } else {
            USER_OVERRIDE.set(userId);
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                USER_OVERRIDE.remove();
            } else {
                USER_OVERRIDE.set(previous);
            }
        }
    }
}
