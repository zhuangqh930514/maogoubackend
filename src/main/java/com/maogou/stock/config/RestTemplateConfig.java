package com.maogou.stock.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
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
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json,text/plain,*/*")
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(timeout)
                .build();
    }

    @Bean
    public RestTemplate webSearchRestTemplate(RestTemplateBuilder builder, AppProperties properties) {
        Duration timeout = Duration.ofMillis(properties.getWebSearch().getTimeoutMs());
        return builder
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 MaogouZhitou/1.0")
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(timeout)
                .build();
    }
}
