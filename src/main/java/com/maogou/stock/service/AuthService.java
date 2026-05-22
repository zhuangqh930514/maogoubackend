package com.maogou.stock.service;

import com.maogou.stock.dto.auth.AuthResponse;
import com.maogou.stock.dto.auth.CurrentUserResponse;
import com.maogou.stock.dto.auth.LoginRequest;
import com.maogou.stock.dto.auth.RegisterRequest;

public interface AuthService {
    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    CurrentUserResponse currentUser();
}
