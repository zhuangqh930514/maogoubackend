package com.maogou.stock.service.impl.research;

import com.maogou.stock.service.impl.AiAnalysisServiceImpl;
import com.maogou.stock.service.research.ExternalIoTransactionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ExternalIoTransactionBoundaryTest {

    @Test
    void marketLlmAndTrainingEntryPointsDoNotOpenDatabaseTransactions() throws Exception {
        Method executeResearchStep = GlobalDailyResearchExecutor.class.getMethod(
                "execute", String.class,
                com.maogou.stock.service.research.AiGlobalDailyResearchExecutor.PipelineContext.class);
        Method analyzeStock = AiAnalysisServiceImpl.class.getMethod(
                "analyzeStock", String.class, boolean.class, Long.class, Long.class);
        Method train = AiMonthlyTrainingServiceImpl.class.getMethod(
                "run", Long.class, java.time.LocalDateTime.class);

        assertThat(GlobalDailyResearchExecutor.class.getAnnotation(Transactional.class)).isNull();
        assertThat(executeResearchStep.getAnnotation(Transactional.class)).isNull();
        assertThat(AiAnalysisServiceImpl.class.getAnnotation(Transactional.class)).isNull();
        assertThat(analyzeStock.getAnnotation(Transactional.class)).isNull();
        assertThat(AiMonthlyTrainingServiceImpl.class.getAnnotation(Transactional.class)).isNull();
        assertThat(train.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void externalIoGuardExecutesOnlyWhenNoDatabaseTransactionIsActive() {
        AtomicBoolean transactionSeenByExternalCall = new AtomicBoolean(true);

        String result = ExternalIoTransactionGuard.call("测试外部调用", () -> {
            transactionSeenByExternalCall.set(
                    TransactionSynchronizationManager.isActualTransactionActive());
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(transactionSeenByExternalCall).isFalse();
    }

    @Test
    void externalIoGuardRejectsCallsInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatIllegalStateException()
                    .isThrownBy(() -> ExternalIoTransactionGuard.call("行情 HTTP 调用", () -> "不应执行"))
                    .withMessage("行情 HTTP 调用不得在数据库事务中执行");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
