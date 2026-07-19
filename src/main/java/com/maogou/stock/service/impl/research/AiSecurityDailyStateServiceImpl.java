package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiSecurityDailyState;
import com.maogou.stock.mapper.research.AiSecurityDailyStateMapper;
import com.maogou.stock.service.research.AiSecurityDailyStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AiSecurityDailyStateServiceImpl implements AiSecurityDailyStateService {

    private final AiSecurityDailyStateMapper mapper;

    public AiSecurityDailyStateServiceImpl(AiSecurityDailyStateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public AiSecurityDailyState store(StateCommand command) {
        validate(command);
        AiSecurityDailyState current = mapper.selectCurrentForUpdate(command.stockCode(), command.tradeDate());
        if (current != null && Objects.equals(current.sourceFingerprint, command.sourceFingerprint())) {
            return current;
        }
        AiSecurityDailyState candidate = state(command, current);
        if (current != null && current.id != null && mapper.markSuperseded(current.id) != 1) {
            throw new IllegalStateException("证券日度交易状态并发修订失败：" + command.stockCode());
        }
        mapper.insertImmutable(candidate);
        AiSecurityDailyState persisted = mapper.selectCurrent(command.stockCode(), command.tradeDate());
        if (persisted == null || !Objects.equals(persisted.sourceFingerprint, command.sourceFingerprint())) {
            throw new IllegalStateException("证券日度交易状态写入后与输入证据不一致");
        }
        return persisted;
    }

    private static AiSecurityDailyState state(StateCommand command, AiSecurityDailyState current) {
        AiSecurityDailyState state = new AiSecurityDailyState();
        state.stockCode = command.stockCode().trim();
        state.tradeDate = command.tradeDate();
        state.sourceBatchId = command.sourceBatchId();
        state.sourceRevision = command.sourceRevision().trim();
        state.revisionNo = current == null ? 1 : value(current.revisionNo) + 1;
        state.isCurrent = 1;
        state.supersedesStateId = current == null ? null : current.id;
        state.listedOn = command.listedOn();
        state.listedDays = command.listedDays();
        state.securityStatus = command.securityStatus();
        state.stStatus = command.stStatus();
        state.isSt = command.isSt();
        state.suspended = command.suspended();
        state.limitRatio = command.limitRatio();
        state.limitUpPrice = command.limitUpPrice();
        state.limitDownPrice = command.limitDownPrice();
        state.isLimitUp = command.isLimitUp();
        state.isLimitDown = command.isLimitDown();
        state.buyTradable = command.buyTradable();
        state.sellTradable = command.sellTradable();
        state.qualityStatus = command.qualityStatus();
        state.missingReason = command.missingReason();
        state.evidenceJson = command.evidenceJson();
        state.sourceFingerprint = command.sourceFingerprint();
        state.observedAt = command.observedAt();
        state.createdAt = LocalDateTime.now();
        return state;
    }

    private static void validate(StateCommand command) {
        if (command == null || blank(command.stockCode()) || command.tradeDate() == null
                || blank(command.sourceRevision()) || blank(command.securityStatus()) || blank(command.stStatus())
                || blank(command.qualityStatus()) || blank(command.evidenceJson())
                || blank(command.sourceFingerprint()) || command.observedAt() == null) {
            throw new IllegalArgumentException("证券日度交易状态缺少时点、来源或质量证据");
        }
        if (command.listedDays() != null && command.listedDays() < 0) {
            throw new IllegalArgumentException("上市天数不能为负数");
        }
        if (command.limitRatio() != null && command.limitRatio().signum() <= 0) {
            throw new IllegalArgumentException("涨跌停比例必须为正数");
        }
    }

    private static int value(Integer number) {
        return number == null ? 1 : number;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
