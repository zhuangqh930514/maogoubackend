package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.ai.AiUserNotificationPayload;
import com.maogou.stock.service.AiUserNotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/notifications")
public class AiUserNotificationController {

    private final AiUserNotificationService notificationService;

    public AiUserNotificationController(AiUserNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiResponse<List<AiUserNotificationPayload>> recent(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(notificationService.recent(limit));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount() {
        return ApiResponse.ok(Map.of("count", notificationService.unreadCount()));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Void> markRead(@PathVariable Long notificationId) {
        notificationService.markRead(notificationId);
        return ApiResponse.ok(null);
    }
}
