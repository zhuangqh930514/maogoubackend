package com.maogou.stock.infrastructure.ai;

/**
 * A model endpoint either asked us to slow down or is still inside its shared cooldown window.
 */
public final class AiModelRateLimitException extends RuntimeException {

    private final long retryAfterMillis;

    public AiModelRateLimitException(String message, long retryAfterMillis, Throwable cause) {
        super(message, cause);
        this.retryAfterMillis = Math.max(0L, retryAfterMillis);
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
