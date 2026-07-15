package com.maogou.stock.service.research;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.function.Supplier;

public final class ExternalIoTransactionGuard {

    private ExternalIoTransactionGuard() {
    }

    public static <T> T call(String operation, Supplier<T> externalCall) {
        Objects.requireNonNull(externalCall, "externalCall");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + "不得在数据库事务中执行");
        }
        return externalCall.get();
    }
}
