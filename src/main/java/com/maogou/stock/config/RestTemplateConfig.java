package com.maogou.stock.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder, AppProperties properties) {
        Duration timeout = Duration.ofMillis(properties.getAi().getTimeoutMs());
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(timeout)
                .build();
    }
}
