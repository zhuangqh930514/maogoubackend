package com.maogou.stock.infrastructure.market;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/** Uniform retry contract for historical providers. */
public interface HistoricalProviderRetryExecutor {

    <T> T execute(String providerCode, String endpointType, Supplier<T> operation);

    static HistoricalProviderRetryExecutor direct() {
        return new HistoricalProviderRetryExecutor() {
            @Override
            public <T> T execute(String providerCode, String endpointType, Supplier<T> operation) {
                return operation.get();
            }
        };
    }

    final class ProviderRetryException extends RuntimeException {
        private final String providerCode;
        private final String endpointType;
        private final int attempt;
        private final int maxAttempts;
        private final boolean retryable;
        private final LocalDateTime nextRetryAt;

        public ProviderRetryException(
                String providerCode,
                String endpointType,
                int attempt,
                int maxAttempts,
                boolean retryable,
                LocalDateTime nextRetryAt,
                String message,
                Throwable cause
        ) {
            super(message, cause);
            this.providerCode = providerCode;
            this.endpointType = endpointType;
            this.attempt = attempt;
            this.maxAttempts = maxAttempts;
            this.retryable = retryable;
            this.nextRetryAt = nextRetryAt;
        }

        public String providerCode() {
            return providerCode;
        }

        public String endpointType() {
            return endpointType;
        }

        public int attempt() {
            return attempt;
        }

        public int maxAttempts() {
            return maxAttempts;
        }

        public boolean retryable() {
            return retryable;
        }

        public LocalDateTime nextRetryAt() {
            return nextRetryAt;
        }
    }
}
