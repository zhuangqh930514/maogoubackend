package com.maogou.stock.dto.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PromptTemplateRequest(
        @NotBlank @Size(max = 128) String title,
        @NotBlank String content
) {
}
