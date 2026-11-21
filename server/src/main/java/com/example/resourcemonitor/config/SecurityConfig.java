package com.example.resourcemonitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * - POST /api/metrics : エージェント用。Spring Security では通し、コントローラで X-Api-Key を検証
 * - それ以外（ダッシュボードと参照 API）: Basic 認証
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/metrics"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/metrics").permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
