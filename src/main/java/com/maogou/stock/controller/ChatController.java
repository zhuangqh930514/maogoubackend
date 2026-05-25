package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.chat.ChatMemoryResponse;
import com.maogou.stock.dto.chat.ChatSendResponse;
import com.maogou.stock.dto.chat.ChatSessionDetailResponse;
import com.maogou.stock.dto.chat.ChatSessionResponse;
import com.maogou.stock.dto.chat.CreateChatSessionRequest;
import com.maogou.stock.dto.chat.SendChatMessageRequest;
import com.maogou.stock.dto.chat.UpdateChatMemoryRequest;
import com.maogou.stock.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/sessions")
    public ApiResponse<List<ChatSessionResponse>> sessions() {
        return ApiResponse.ok(chatService.listSessions());
    }

    @PostMapping("/sessions")
    public ApiResponse<ChatSessionResponse> createSession(@RequestBody(required = false) CreateChatSessionRequest request) {
        return ApiResponse.ok(chatService.createSession(request));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<ChatSessionDetailResponse> sessionDetail(@PathVariable Long sessionId) {
        return ApiResponse.ok(chatService.sessionDetail(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ApiResponse<ChatSendResponse> sendMessage(
            @PathVariable Long sessionId,
            @RequestBody @Valid SendChatMessageRequest request
    ) {
        return ApiResponse.ok(chatService.sendMessage(sessionId, request));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId) {
        chatService.deleteSession(sessionId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/memory")
    public ApiResponse<ChatMemoryResponse> memory() {
        return ApiResponse.ok(chatService.currentMemory());
    }

    @PutMapping("/memory")
    public ApiResponse<ChatMemoryResponse> updateMemory(@RequestBody @Valid UpdateChatMemoryRequest request) {
        return ApiResponse.ok(chatService.updateMemory(request));
    }
}
