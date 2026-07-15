package com.maogou.stock.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ObjectMapper objectMapper
    ) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/ai/research-lab/actions/run-daily",
                                "/api/ai/research-lab/actions/run-historical-bootstrap",
                                "/api/ai/research-lab/actions/verify-labels",
                                "/api/ai/research-lab/actions/run-weekly",
                                "/api/ai/research-lab/actions/run-training",
                                "/api/ai/research-lab/strategies/*/promote",
                                "/api/ai/research-lab/strategies/*/reject",
                                "/api/ai/research-lab/strategies/*/rollback")
                        .hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> writeUnauthorized(response, objectMapper))
                        .accessDeniedHandler((request, response, accessDeniedException) -> writeForbidden(response, objectMapper))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "https://maogou.subo.work",
                "http://maogou.subo.work",
                "http://127.0.0.1:5174",
                "http://localhost:5174",
                "http://127.0.0.1:5173",
                "http://localhost:5173",
                "http://127.0.0.1:4173",
                "http://localhost:4173"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "X-Request-Id", "Server-Timing"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static void writeUnauthorized(HttpServletResponse response, ObjectMapper objectMapper) throws java.io.IOException {
        writeJson(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
    }

    private static void writeForbidden(HttpServletResponse response, ObjectMapper objectMapper) throws java.io.IOException {
        writeJson(response, objectMapper, HttpServletResponse.SC_FORBIDDEN, "没有访问权限");
    }

    private static void writeJson(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String message
    ) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(message)));
    }
}
