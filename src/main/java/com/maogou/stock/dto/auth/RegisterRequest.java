package com.maogou.stock.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "最多 64 个字符")
        String username,

        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "最多 128 个字符")
        String account,

        @NotBlank(message = "不能为空")
        @Size(min = 8, max = 64, message = "长度必须在 8 到 64 位之间")
        String password,

        @NotBlank(message = "不能为空")
        String confirmPassword,

        @Size(max = 16, message = "最多 16 个字符")
        String riskPreference
) {
}
