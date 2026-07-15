package com.maogou.stock.security;

public record AuthPrincipal(Long userId, String username, String systemRole) {
}
