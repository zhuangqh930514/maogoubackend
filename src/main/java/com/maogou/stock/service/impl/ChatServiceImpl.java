package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiChatMessage;
import com.maogou.stock.domain.entity.AiChatSession;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.entity.AiUserMemory;
import com.maogou.stock.dto.chat.ChatMemoryResponse;
import com.maogou.stock.dto.chat.ChatMessageResponse;
import com.maogou.stock.dto.chat.ChatSendResponse;
import com.maogou.stock.dto.chat.ChatSessionDetailResponse;
import com.maogou.stock.dto.chat.ChatSessionResponse;
import com.maogou.stock.dto.chat.CreateChatSessionRequest;
import com.maogou.stock.dto.chat.SendChatMessageRequest;
import com.maogou.stock.dto.chat.UpdateChatMemoryRequest;
import com.maogou.stock.infrastructure.ai.LocalAiClient;
import com.maogou.stock.mapper.AiChatMessageMapper;
import com.maogou.stock.mapper.AiChatSessionMapper;
import com.maogou.stock.mapper.AiUserMemoryMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.ChatService;
import com.maogou.stock.service.ModelConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class ChatServiceImpl implements ChatService {

    private static final int HISTORY_LIMIT = 16;
    private static final int MEMORY_LIMIT = 3600;

    private final AiChatSessionMapper sessionMapper;
    private final AiChatMessageMapper messageMapper;
    private final AiUserMemoryMapper memoryMapper;
    private final ModelConfigService modelConfigService;
    private final LocalAiClient localAiClient;

    public ChatServiceImpl(
            AiChatSessionMapper sessionMapper,
            AiChatMessageMapper messageMapper,
            AiUserMemoryMapper memoryMapper,
            ModelConfigService modelConfigService,
            LocalAiClient localAiClient
    ) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.memoryMapper = memoryMapper;
        this.modelConfigService = modelConfigService;
        this.localAiClient = localAiClient;
    }

    @Override
    public List<ChatSessionResponse> listSessions() {
        long userId = AuthContext.currentUserIdOrDefault();
        return sessionMapper.selectList(new QueryWrapper<AiChatSession>()
                        .eq("user_id", userId)
                        .orderByDesc("updated_at"))
                .stream()
                .map(ChatSessionResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public ChatSessionResponse createSession(CreateChatSessionRequest request) {
        long userId = AuthContext.currentUserIdOrDefault();
        AiModelConfig config = modelConfigService.currentEntity();
        LocalDateTime now = LocalDateTime.now();
        AiChatSession session = new AiChatSession();
        session.userId = userId;
        session.title = request == null || request.title() == null || request.title().isBlank()
                ? "新会话"
                : compact(request.title(), 40);
        session.modelName = config.modelName;
        session.deleted = 0;
        session.createdAt = now;
        session.updatedAt = now;
        sessionMapper.insert(session);
        return ChatSessionResponse.from(session);
    }

    @Override
    public ChatSessionDetailResponse sessionDetail(Long sessionId) {
        AiChatSession session = getOwnedSession(sessionId);
        List<ChatMessageResponse> messages = listMessages(session.id).stream()
                .map(ChatMessageResponse::from)
                .toList();
        return new ChatSessionDetailResponse(
                ChatSessionResponse.from(session),
                ChatMemoryResponse.from(currentMemoryEntity(session.userId)),
                messages
        );
    }

    @Override
    public ChatSendResponse sendMessage(Long sessionId, SendChatMessageRequest request) {
        AiChatSession session = getOwnedSession(sessionId);
        long userId = AuthContext.currentUserIdOrDefault();
        AiModelConfig config = modelConfigService.currentEntity();
        AiUserMemory memory = currentMemoryEntity(userId);
        LocalDateTime now = LocalDateTime.now();

        AiChatMessage userMessage = newMessage(session.id, userId, "user", request.content().trim(), config.modelName, "SUCCESS", null, now);
        messageMapper.insert(userMessage);

        List<AiChatMessage> history = recentMessages(session.id);
        String prompt = buildPrompt(request.content().trim(), history, memory.memorySummary);
        AiChatMessage assistantMessage;
        try {
            String aiText = localAiClient.chat(prompt, config);
            assistantMessage = newMessage(session.id, userId, "assistant", fallback(aiText), config.modelName, "SUCCESS", null, LocalDateTime.now());
        } catch (Exception ex) {
            String error = ex.getMessage() == null ? "未知模型调用错误" : ex.getMessage();
            assistantMessage = newMessage(session.id, userId, "assistant", "模型调用失败：" + error, config.modelName, "FAILED", error, LocalDateTime.now());
        }
        messageMapper.insert(assistantMessage);

        refreshSession(session, request.content(), config.modelName);
        refreshMemory(memory, request.content());

        return new ChatSendResponse(
                ChatSessionResponse.from(session),
                ChatMessageResponse.from(userMessage),
                ChatMessageResponse.from(assistantMessage),
                ChatMemoryResponse.from(memory)
        );
    }

    @Override
    @Transactional
    public void deleteSession(Long sessionId) {
        AiChatSession session = getOwnedSession(sessionId);
        sessionMapper.deleteById(session.id);
    }

    @Override
    public ChatMemoryResponse currentMemory() {
        return ChatMemoryResponse.from(currentMemoryEntity(AuthContext.currentUserIdOrDefault()));
    }

    @Override
    @Transactional
    public ChatMemoryResponse updateMemory(UpdateChatMemoryRequest request) {
        AiUserMemory memory = currentMemoryEntity(AuthContext.currentUserIdOrDefault());
        memory.memorySummary = request == null || request.memorySummary() == null ? "" : request.memorySummary().trim();
        memory.updatedAt = LocalDateTime.now();
        saveMemory(memory);
        return ChatMemoryResponse.from(memory);
    }

    private AiChatSession getOwnedSession(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        AiChatSession session = sessionMapper.selectOne(new QueryWrapper<AiChatSession>()
                .eq("id", sessionId)
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .last("limit 1"));
        if (session == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        return session;
    }

    private List<AiChatMessage> listMessages(Long sessionId) {
        return messageMapper.selectList(new QueryWrapper<AiChatMessage>()
                .eq("session_id", sessionId)
                .orderByAsc("created_at")
                .orderByAsc("id"));
    }

    private List<AiChatMessage> recentMessages(Long sessionId) {
        List<AiChatMessage> messages = messageMapper.selectList(new QueryWrapper<AiChatMessage>()
                .eq("session_id", sessionId)
                .orderByDesc("created_at")
                .orderByDesc("id")
                .last("limit " + HISTORY_LIMIT));
        Collections.reverse(messages);
        return messages;
    }

    private AiUserMemory currentMemoryEntity(long userId) {
        AiUserMemory memory = memoryMapper.selectOne(new QueryWrapper<AiUserMemory>()
                .eq("user_id", userId)
                .last("limit 1"));
        if (memory != null) {
            return memory;
        }
        LocalDateTime now = LocalDateTime.now();
        AiUserMemory empty = new AiUserMemory();
        empty.userId = userId;
        empty.memorySummary = "";
        empty.lastInteractionAt = now;
        empty.createdAt = now;
        empty.updatedAt = now;
        return empty;
    }

    private AiChatMessage newMessage(
            Long sessionId,
            long userId,
            String role,
            String content,
            String modelName,
            String status,
            String errorMessage,
            LocalDateTime createdAt
    ) {
        AiChatMessage message = new AiChatMessage();
        message.sessionId = sessionId;
        message.userId = userId;
        message.messageRole = role;
        message.content = content;
        message.modelName = modelName;
        message.status = status;
        message.errorMessage = errorMessage;
        message.createdAt = createdAt;
        return message;
    }

    private void refreshSession(AiChatSession session, String userContent, String modelName) {
        if (session.title == null || session.title.isBlank() || "新会话".equals(session.title)) {
            session.title = compact(userContent, 24);
        }
        session.modelName = modelName;
        session.updatedAt = LocalDateTime.now();
        sessionMapper.updateById(session);
    }

    private void refreshMemory(AiUserMemory memory, String userContent) {
        memory.lastInteractionAt = LocalDateTime.now();
        memory.updatedAt = memory.lastInteractionAt;
        if (shouldRemember(userContent)) {
            String note = "- " + LocalDate.now() + " 用户偏好/事实：" + compact(userContent, 180);
            String next = memory.memorySummary == null || memory.memorySummary.isBlank()
                    ? note
                    : memory.memorySummary.strip() + "\n" + note;
            memory.memorySummary = tail(next, MEMORY_LIMIT);
        }
        saveMemory(memory);
    }

    private void saveMemory(AiUserMemory memory) {
        if (memory.id == null) {
            memoryMapper.insert(memory);
        } else {
            memoryMapper.updateById(memory);
        }
    }

    private String buildPrompt(String currentMessage, List<AiChatMessage> history, String memorySummary) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是猫狗智投的“猫狗畅聊”助手，服务个人 A 股投研工作台。
                你可以结合用户长期记忆、最近对话和当前问题回答。涉及股票交易时必须提示风险，避免保证收益。
                如果用户要求你记住偏好，你可以自然确认；系统会把明确的长期偏好写入用户自己的记忆。

                用户长期记忆：
                """);
        builder.append(memorySummary == null || memorySummary.isBlank() ? "暂无。\n" : memorySummary.strip() + "\n");
        builder.append("\n最近对话：\n");
        for (AiChatMessage message : history) {
            builder.append("user".equals(message.messageRole) ? "用户：" : "助手：")
                    .append(compact(message.content, 1200))
                    .append("\n");
        }
        builder.append("\n当前用户问题：\n").append(currentMessage).append("\n\n");
        builder.append("请用中文回答，结构清晰，必要时给出可执行步骤。");
        return builder.toString();
    }

    private static boolean shouldRemember(String content) {
        String normalized = content == null ? "" : content.replace(" ", "");
        return List.of("记住", "以后", "我的", "我偏好", "风险偏好", "我关注", "我持有", "我是", "不要", "喜欢")
                .stream()
                .anyMatch(normalized::contains);
    }

    private static String fallback(String content) {
        return content == null || content.isBlank() ? "模型返回为空，请检查模型配置或稍后重试。" : content.trim();
    }

    private static String compact(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.strip().replaceAll("\\s+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private static String tail(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(text.length() - maxLength);
    }
}
