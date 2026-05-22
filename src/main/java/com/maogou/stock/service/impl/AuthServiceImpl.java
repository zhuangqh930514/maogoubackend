package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.UserAccount;
import com.maogou.stock.dto.auth.AuthResponse;
import com.maogou.stock.dto.auth.CurrentUserResponse;
import com.maogou.stock.dto.auth.LoginRequest;
import com.maogou.stock.dto.auth.RegisterRequest;
import com.maogou.stock.mapper.UserAccountMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.security.JwtService;
import com.maogou.stock.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String ACTIVE_STATUS = "ACTIVE";

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthServiceImpl(UserAccountMapper userAccountMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = normalize(request.username());
        String account = normalize(request.account());
        if (!request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }
        if (exists("username", username)) {
            throw new IllegalArgumentException("用户名已存在");
        }

        boolean emailAccount = isEmail(account);
        String email = emailAccount ? account : null;
        String phone = emailAccount ? null : account;
        if (email != null && exists("email", email)) {
            throw new IllegalArgumentException("邮箱已注册");
        }
        if (phone != null && exists("phone", phone)) {
            throw new IllegalArgumentException("手机号已注册");
        }

        LocalDateTime now = LocalDateTime.now();
        UserAccount user = new UserAccount();
        user.username = username;
        user.displayName = username;
        user.email = email;
        user.phone = phone;
        user.passwordHash = passwordEncoder.encode(request.password());
        user.status = ACTIVE_STATUS;
        user.riskPreference = request.riskPreference() == null || request.riskPreference().isBlank()
                ? "均衡"
                : request.riskPreference();
        user.lastLoginAt = now;
        user.deleted = 0;
        user.createdAt = now;
        user.updatedAt = now;
        userAccountMapper.insert(user);
        return buildAuthResponse(user);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        UserAccount user = findByAccount(normalize(request.account()));
        if (user == null || user.passwordHash == null || !passwordEncoder.matches(request.password(), user.passwordHash)) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        if (!ACTIVE_STATUS.equals(user.status)) {
            throw new IllegalArgumentException("账户状态不可用");
        }
        user.lastLoginAt = LocalDateTime.now();
        user.updatedAt = user.lastLoginAt;
        userAccountMapper.updateById(user);
        return buildAuthResponse(user);
    }

    @Override
    public CurrentUserResponse currentUser() {
        Long userId = AuthContext.currentUserId()
                .orElseThrow(() -> new IllegalArgumentException("请先登录"));
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return CurrentUserResponse.from(user);
    }

    private AuthResponse buildAuthResponse(UserAccount user) {
        return new AuthResponse(
                jwtService.createToken(user.id, user.username),
                "Bearer",
                jwtService.expiresAt(),
                CurrentUserResponse.from(user)
        );
    }

    private UserAccount findByAccount(String account) {
        return userAccountMapper.selectOne(new QueryWrapper<UserAccount>()
                .nested(wrapper -> wrapper
                        .eq("username", account)
                        .or()
                        .eq("email", account)
                        .or()
                        .eq("phone", account))
                .last("limit 1"));
    }

    private boolean exists(String column, String value) {
        return userAccountMapper.selectCount(new QueryWrapper<UserAccount>().eq(column, value)) > 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isEmail(String account) {
        return account.contains("@");
    }
}
