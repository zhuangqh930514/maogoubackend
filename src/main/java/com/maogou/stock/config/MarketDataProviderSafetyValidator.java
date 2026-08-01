package com.maogou.stock.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Prevents demo or local fallback data from being enabled in a normal runtime profile. */
@Component
@Profile("!dev-mock")
public class MarketDataProviderSafetyValidator {

    private final AppProperties properties;

    public MarketDataProviderSafetyValidator(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        String provider = properties.getMarket().getProvider();
        if ("mock".equalsIgnoreCase(provider) || "local_fallback".equalsIgnoreCase(provider)) {
            throw new IllegalStateException(
                    "禁止在非 dev-mock profile 启用演示行情 provider=" + provider
                            + "；如需本地静态数据，请同时启用 SPRING_PROFILES_ACTIVE=dev-mock。"
            );
        }
    }
}
