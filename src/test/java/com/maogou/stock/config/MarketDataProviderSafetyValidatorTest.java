package com.maogou.stock.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketDataProviderSafetyValidatorTest {

    @Test
    void allowsConfiguredRealProvider() {
        AppProperties properties = new AppProperties();
        properties.getMarket().setProvider("sina");

        assertThatCode(() -> new MarketDataProviderSafetyValidator(properties).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDemoProviderOutsideDevMockProfile() {
        AppProperties properties = new AppProperties();
        properties.getMarket().setProvider("mock");

        assertThatThrownBy(() -> new MarketDataProviderSafetyValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-mock");
    }
}
