package com.maogou.stock.service.impl;

import com.maogou.stock.domain.entity.AiResearchUserFeedback;
import com.maogou.stock.domain.entity.research.AiResearchDailyReport;
import com.maogou.stock.dto.ai.AiResearchFeedbackPayload;
import com.maogou.stock.mapper.AiResearchUserFeedbackMapper;
import com.maogou.stock.mapper.research.AiResearchDailyReportMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchFeedbackService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AiResearchFeedbackServiceImpl implements AiResearchFeedbackService {

    private static final Set<String> FEEDBACK_TYPES = Set.of("HELPFUL", "NOT_HELPFUL", "UNCLEAR");

    private final AiResearchDailyReportMapper reportMapper;
    private final AiResearchUserFeedbackMapper feedbackMapper;

    public AiResearchFeedbackServiceImpl(
            AiResearchDailyReportMapper reportMapper,
            AiResearchUserFeedbackMapper feedbackMapper
    ) {
        this.reportMapper = reportMapper;
        this.feedbackMapper = feedbackMapper;
    }

    @Override
    public List<AiResearchFeedbackPayload.Item> list(Long reportId) {
        long userId = AuthContext.currentUserIdOrDefault();
        requireOwnedReport(userId, reportId);
        return feedbackMapper.selectByReport(userId, reportId).stream()
                .map(AiResearchFeedbackPayload.Item::from)
                .toList();
    }

    @Override
    public AiResearchFeedbackPayload.Item submit(Long reportId, AiResearchFeedbackPayload.SubmitRequest request) {
        long userId = AuthContext.currentUserIdOrDefault();
        requireOwnedReport(userId, reportId);
        if (request == null) {
            throw new IllegalArgumentException("反馈内容不能为空");
        }
        String stockCode = normalizedStockCode(request.stockCode());
        String feedbackType = normalizedType(request.feedbackType());
        String comment = normalizedComment(request.comment());
        LocalDateTime now = LocalDateTime.now();

        AiResearchUserFeedback entity = new AiResearchUserFeedback();
        entity.userId = userId;
        entity.reportId = reportId;
        entity.stockCode = stockCode;
        entity.feedbackType = feedbackType;
        entity.comment = comment;
        entity.createdAt = now;
        entity.updatedAt = now;
        feedbackMapper.upsert(entity);
        AiResearchUserFeedback stored = feedbackMapper.selectByReportAndStock(userId, reportId, stockCode);
        if (stored == null) {
            throw new IllegalStateException("反馈保存后未读取到记录");
        }
        return AiResearchFeedbackPayload.Item.from(stored);
    }

    private void requireOwnedReport(long userId, Long reportId) {
        if (reportId == null || reportId <= 0) {
            throw new IllegalArgumentException("日报 ID 无效");
        }
        AiResearchDailyReport report = reportMapper.selectById(reportId);
        if (report == null || report.userId == null || report.userId != userId) {
            throw new IllegalArgumentException("日报不存在");
        }
    }

    private static String normalizedStockCode(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("\\d{6}(?:\\.(?:SH|SZ|BJ))?")) {
            throw new IllegalArgumentException("股票代码无效");
        }
        return code;
    }

    private static String normalizedType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!FEEDBACK_TYPES.contains(type)) {
            throw new IllegalArgumentException("反馈类型无效");
        }
        return type;
    }

    private static String normalizedComment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String comment = value.trim();
        if (comment.length() > 500) {
            throw new IllegalArgumentException("反馈说明不能超过 500 个字符");
        }
        return comment;
    }
}
