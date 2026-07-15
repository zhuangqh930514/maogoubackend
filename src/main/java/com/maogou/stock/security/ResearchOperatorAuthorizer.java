package com.maogou.stock.security;

import com.maogou.stock.domain.entity.UserAccount;
import com.maogou.stock.mapper.UserAccountMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ResearchOperatorAuthorizer {

    private static final Set<String> ALLOWED_ROLES = Set.of("OPERATOR", "ADMIN");

    private final UserAccountMapper userAccountMapper;

    public ResearchOperatorAuthorizer(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    public void requireOperator() {
        Long userId = AuthContext.currentUserId()
                .orElseThrow(() -> new AccessDeniedException("请先登录"));
        UserAccount current = userAccountMapper.selectById(userId);
        if (current == null || current.deleted != null && current.deleted != 0
                || !"ACTIVE".equals(current.status)
                || !ALLOWED_ROLES.contains(current.systemRole)) {
            throw new AccessDeniedException("需要研究运维权限");
        }
    }
}
