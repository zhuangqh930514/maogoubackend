package com.maogou.stock.service;

import com.maogou.stock.dto.chat.ChatMemoryResponse;
import com.maogou.stock.dto.chat.ChatSendResponse;
import com.maogou.stock.dto.chat.ChatSessionDetailResponse;
import com.maogou.stock.dto.chat.ChatSessionResponse;
import com.maogou.stock.dto.chat.CreateChatSessionRequest;
import com.maogou.stock.dto.chat.SendChatMessageRequest;
import com.maogou.stock.dto.chat.UpdateChatMemoryRequest;

import java.util.List;

public interface ChatService {
    List<ChatSessionResponse> listSessions();

    ChatSessionResponse createSession(CreateChatSessionRequest request);

    ChatSessionDetailResponse sessionDetail(Long sessionId);

    ChatSendResponse sendMessage(Long sessionId, SendChatMessageRequest request);

    void deleteSession(Long sessionId);

    ChatMemoryResponse currentMemory();

    ChatMemoryResponse updateMemory(UpdateChatMemoryRequest request);
}
