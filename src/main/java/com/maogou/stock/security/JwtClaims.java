package com.maogou.stock.security;

import java.time.Instant;

public record JwtClaims(Long userId, String username, String systemRole, Instant expiresAt) {
}
