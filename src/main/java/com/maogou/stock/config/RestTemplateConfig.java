package com.maogou.stock.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate(RestTemplateBuilder builder, AppProperties properties) {
        Duration timeout = Duration.ofMillis(properties.getAi().getTimeoutMs());
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(timeout)
                .build();
    }

    @Bean
    public RestTemplate marketRestTemplate(RestTemplateBuilder builder, AppProperties properties) {
        Duration timeout = Duration.ofMillis(properties.getMarket().getTimeoutMs());
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(timeout)
                .build();
    }
}
