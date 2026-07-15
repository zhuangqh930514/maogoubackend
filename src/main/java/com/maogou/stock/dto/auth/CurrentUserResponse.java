package com.maogou.stock.dto.auth;

import com.maogou.stock.domain.entity.UserAccount;

import java.time.LocalDateTime;

public record CurrentUserResponse(
        Long id,
        String username,
        String displayName,
        String email,
        String phone,
        String status,
        String systemRole,
        String riskPreference,
        LocalDateTime lastLoginAt
) {
    public static CurrentUserResponse from(UserAccount user) {
        return new CurrentUserResponse(
                user.id,
                user.username,
                user.displayName,
                user.email,
                user.phone,
                user.status,
                user.systemRole == null || user.systemRole.isBlank() ? "USER" : user.systemRole,
                user.riskPreference,
                user.lastLoginAt
        );
    }
}
