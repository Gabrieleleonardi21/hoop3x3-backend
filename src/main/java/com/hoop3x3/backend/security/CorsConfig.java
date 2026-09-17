package com.hoop3x3.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS come bean CorsConfigurationSource: lo usa Spring Security (vedi .cors() in SecurityConfig),
 * che gira prima di Spring MVC e altrimenti bloccherebbe il preflight.
 * In sviluppo il frontend passa dal proxy di Vite (stessa origine) e non serve; resta per
 * la produzione o per chiamare l'API direttamente su :3001.
 */
@Configuration
public class CorsConfig {

    /** Origini consentite, separate da virgola (property cors.origins) */
    @Value("${cors.origins:http://localhost:5173,http://127.0.0.1:5173}")
    private List<String> origins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L); // il browser tiene in cache il preflight per 1 ora

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
