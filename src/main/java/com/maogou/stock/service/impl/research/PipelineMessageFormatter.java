package com.maogou.stock.service.impl.research;

import java.util.ArrayList;
import java.util.List;

final class PipelineMessageFormatter {

    static final int MAX_SUMMARY_LENGTH = 4096;
    static final int MAX_SUMMARY_ITEMS = 12;
    static final int MAX_DETAIL_LENGTH = 4_000_000;

    private PipelineMessageFormatter() {
    }

    static String summary(List<String> messages) {
        List<String> normalized = normalized(messages);
        if (normalized.isEmpty()) {
            return null;
        }
        int visible = Math.min(MAX_SUMMARY_ITEMS, normalized.size());
        String value = String.join("；", normalized.subList(0, visible));
        if (normalized.size() > visible) {
            String suffix = "；其余 " + (normalized.size() - visible) + " 条省略";
            return truncate(value, MAX_SUMMARY_LENGTH - suffix.length()) + suffix;
        }
        return truncate(value, MAX_SUMMARY_LENGTH);
    }

    static String summary(String message) {
        return message == null || message.isBlank() ? null : truncate(message.trim(), MAX_SUMMARY_LENGTH);
    }

    static String detail(List<String> messages) {
        List<String> normalized = normalized(messages);
        return normalized.isEmpty() ? null : truncate(String.join("\n", normalized), MAX_DETAIL_LENGTH);
    }

    static String detail(String message) {
        return message == null || message.isBlank() ? null : truncate(message.trim(), MAX_DETAIL_LENGTH);
    }

    private static List<String> normalized(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(messages.size());
        for (String message : messages) {
            if (message != null && !message.isBlank()) {
                result.add(message.trim());
            }
        }
        return result;
    }

    private static String truncate(String value, int maximumLength) {
        if (value.length() <= maximumLength) {
            return value;
        }
        String suffix = "...（已截断）";
        return value.substring(0, maximumLength - suffix.length()) + suffix;
    }
}
