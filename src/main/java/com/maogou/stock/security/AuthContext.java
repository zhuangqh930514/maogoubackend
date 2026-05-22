package com.maogou.stock.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class AuthContext {

    private static final long DEFAULT_USER_ID = 1L;

    private AuthContext() {
    }

    public static Optional<Long> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.ofNullable(principal.userId());
    }

    public static long currentUserIdOrDefault() {
        return currentUserId().orElse(DEFAULT_USER_ID);
    }
}
