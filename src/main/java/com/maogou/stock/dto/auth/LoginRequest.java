package com.maogou.stock.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "不能为空")
        String account,

        @NotBlank(message = "不能为空")
        String password
) {
}
