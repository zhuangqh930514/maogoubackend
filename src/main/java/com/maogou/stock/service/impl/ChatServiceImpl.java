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
import com.maogou.stock.dto.chat.ChatWebSearchResultResponse;
import com.maogou.stock.dto.chat.CreateChatSessionRequest;
import com.maogou.stock.dto.chat.SendChatMessageRequest;
import com.maogou.stock.dto.chat.UpdateChatMemoryRequest;
import com.maogou.stock.infrastructure.ai.LocalAiClient;
import com.maogou.stock.infrastructure.search.WebSearchContext;
import com.maogou.stock.infrastructure.search.WebSearchItem;
import com.maogou.stock.infrastructure.search.WebSearchService;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
public class ChatServiceImpl implements ChatService {

    private static final int HISTORY_LIMIT = 16;
    private static final int MEMORY_LIMIT = 3600;
    private static final List<String> MEMORY_RELEVANCE_MARKERS = List.of(
            "记住", "记得", "以后", "称呼", "叫我", "名字", "我叫",
            "偏好", "习惯", "不要", "喜欢", "不喜欢",
            "风险", "持仓", "自选", "关注", "股票", "模型", "接口", "项目", "产品"
    );

    private final AiChatSessionMapper sessionMapper;
    private final AiChatMessageMapper messageMapper;
    private final AiUserMemoryMapper memoryMapper;
    private final ModelConfigService modelConfigService;
    private final LocalAiClient localAiClient;
    private final WebSearchService webSearchService;

    public ChatServiceImpl(
            AiChatSessionMapper sessionMapper,
            AiChatMessageMapper messageMapper,
            AiUserMemoryMapper memoryMapper,
            ModelConfigService modelConfigService,
            LocalAiClient localAiClient,
            WebSearchService webSearchService
    ) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.memoryMapper = memoryMapper;
        this.modelConfigService = modelConfigService;
        this.localAiClient = localAiClient;
        this.webSearchService = webSearchService;
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
        String currentContent = request.content().trim();

        AiChatMessage userMessage = newMessage(session.id, userId, "user", currentContent, config.modelName, "SUCCESS", null, now);
        messageMapper.insert(userMessage);

        List<AiChatMessage> history = recentMessages(session.id);
        WebSearchContext webSearchContext = Boolean.TRUE.equals(request.webSearchEnabled())
                ? webSearchService.search(currentContent, 5)
                : WebSearchContext.notRequested();
        String prompt = buildPrompt(currentContent, history, memory.memorySummary, webSearchContext);
        AiChatMessage assistantMessage;
        try {
            String aiText = localAiClient.chat(prompt, config);
            assistantMessage = newMessage(session.id, userId, "assistant", fallback(aiText), config.modelName, "SUCCESS", null, LocalDateTime.now());
        } catch (Exception ex) {
            String error = ex.getMessage() == null ? "未知模型调用错误" : ex.getMessage();
            assistantMessage = newMessage(session.id, userId, "assistant", "模型调用失败：" + error, config.modelName, "FAILED", error, LocalDateTime.now());
        }
        messageMapper.insert(assistantMessage);

        refreshSession(session, currentContent, config.modelName);
        refreshMemory(memory, currentContent);
        List<ChatWebSearchResultResponse> searchResults = webSearchContext.results().stream()
                .map(ChatWebSearchResultResponse::from)
                .toList();

        return new ChatSendResponse(
                ChatSessionResponse.from(session),
                ChatMessageResponse.from(userMessage),
                ChatMessageResponse.from(assistantMessage),
                ChatMemoryResponse.from(memory),
                webSearchContext.requested(),
                searchResults,
                webSearchContext.errorMessage()
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

    private String buildPrompt(String currentMessage, List<AiChatMessage> history, String memorySummary, WebSearchContext webSearchContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是猫狗智投的“猫狗畅聊”助手，是一个通用中文聊天助手。
                用户可以问任何话题，也可以闲聊、学习、写作、编程、生活咨询、产品讨论或投研分析；不要把回答范围限制在股票或投研。
                你可以结合用户长期记忆、最近对话和当前问题回答，但必须让“当前用户问题”始终优先。只有当用户明确聊到投资、股票、交易或金融决策时，才需要提示风险，避免保证收益。
                如果用户要求你记住偏好、事实、称呼或长期上下文，你可以自然确认；系统会把明确的长期信息写入用户自己的记忆。
                如果提供了联网检索资料，请把它作为辅助上下文使用。涉及实时信息时优先说明来源标题或链接；没有资料支撑时不要编造来源、价格、新闻或日期。
                """);
        appendMemoryContext(builder, currentMessage, memorySummary);
        builder.append("\n最近对话：\n");
        for (AiChatMessage message : history) {
            builder.append("user".equals(message.messageRole) ? "用户：" : "助手：")
                    .append(compact(message.content, 1200))
                    .append("\n");
        }
        appendWebSearchContext(builder, webSearchContext);
        builder.append("\n当前用户问题：\n").append(currentMessage).append("\n\n");
        builder.append("请优先用中文自然回答。根据用户问题选择合适风格：闲聊可以轻松，复杂问题要结构清晰，必要时给出可执行步骤。");
        return builder.toString();
    }

    private void appendMemoryContext(StringBuilder builder, String currentMessage, String memorySummary) {
        builder.append("""

                长期记忆使用门禁：
                1. 每轮都可以读取下方长期记忆，但不要默认使用它。
                2. 只有当前问题与记忆中的称呼、偏好、约束、长期项目、持仓、自选股、模型配置或用户明确事实直接相关时，才引用记忆。
                3. 如果当前问题和记忆无关，必须忽略记忆，不要主动提起“我记得你...”之类的话，也不要让记忆改变主要回答方向。
                4. 如果用户正在更新记忆或询问你记住了什么，可以直接处理记忆内容。
                """);

        if (memorySummary == null || memorySummary.isBlank()) {
            builder.append("长期记忆内容：暂无。\n");
            return;
        }

        List<String> relatedLines = pickRelatedMemoryLines(currentMessage, memorySummary);
        if (relatedLines.isEmpty()) {
            builder.append("系统初筛：当前问题没有明显命中长期记忆，默认不要触发记忆。\n");
        } else {
            builder.append("系统初筛：以下记忆可能与当前问题相关，可优先参考：\n");
            relatedLines.forEach(line -> builder.append("- ").append(compact(line, 220)).append("\n"));
        }

        builder.append("完整长期记忆（仅用于相关性判断，禁止在无关问题中主动展开）：\n")
                .append(memorySummary.strip())
                .append("\n");
    }

    private static List<String> pickRelatedMemoryLines(String currentMessage, String memorySummary) {
        String question = normalizeForMatch(currentMessage);
        if (question.isBlank()) {
            return List.of();
        }

        boolean hasMemoryIntent = MEMORY_RELEVANCE_MARKERS.stream().anyMatch(question::contains);
        List<String> relatedLines = new ArrayList<>();
        for (String rawLine : memorySummary.split("\\R+")) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (line.isBlank()) {
                continue;
            }
            String normalizedLine = normalizeForMatch(line);
            if (hasMemoryIntent && hasSharedMarker(question, normalizedLine)) {
                relatedLines.add(line);
                continue;
            }
            if (hasMeaningfulOverlap(question, normalizedLine)) {
                relatedLines.add(line);
            }
        }
        return relatedLines.stream().limit(5).toList();
    }

    private static boolean hasSharedMarker(String question, String memoryLine) {
        return MEMORY_RELEVANCE_MARKERS.stream()
                .anyMatch(marker -> question.contains(marker) && memoryLine.contains(marker));
    }

    private static boolean hasMeaningfulOverlap(String question, String memoryLine) {
        for (String marker : MEMORY_RELEVANCE_MARKERS) {
            if (question.contains(marker) && memoryLine.contains(marker)) {
                return true;
            }
        }
        for (String token : question.split("[^\\p{IsHan}A-Za-z0-9]+")) {
            if (token.length() >= 3 && memoryLine.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeForMatch(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private void appendWebSearchContext(StringBuilder builder, WebSearchContext webSearchContext) {
        builder.append("\n联网检索：\n");
        if (webSearchContext == null || !webSearchContext.requested()) {
            builder.append("本轮未开启联网搜索。\n");
            return;
        }
        if (!webSearchContext.hasResults()) {
            builder.append("用户开启了联网搜索，但检索失败：")
                    .append(webSearchContext.errorMessage() == null ? "未返回可用资料" : webSearchContext.errorMessage())
                    .append("。如果用户问题依赖实时信息，请明确说明本轮未能联网核验。\n");
            return;
        }
        builder.append("以下是系统本轮刚检索到的网页资料，可能不完整，请结合上下文审慎回答：\n");
        int index = 1;
        for (WebSearchItem item : webSearchContext.results()) {
            builder.append("[")
                    .append(index++)
                    .append("] ")
                    .append(compact(item.title(), 140))
                    .append("\nURL: ")
                    .append(item.url())
                    .append("\n摘要: ")
                    .append(compact(item.snippet(), 360))
                    .append("\n");
        }
    }

    private static boolean shouldRemember(String content) {
        String normalized = content == null ? "" : content.replace(" ", "");
        return List.of("记住", "记一下", "记得", "以后", "我的", "我叫", "叫我", "我是", "我偏好", "风险偏好", "我关注", "我持有", "不要", "喜欢")
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
