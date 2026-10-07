package com.hoop3x3.backend.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS come bean CorsConfigurationSource: lo usa Spring Security (vedi .cors() in SecurityConfig),
 * che gira prima di Spring MVC e altrimenti bloccherebbe il preflight.
 * Serve anche quando il frontend passa da un proxy (quello di Vite in sviluppo, un reverse proxy in
 * produzione): il backend confronta l'header Origin del browser con schema, host e porta che vede lui
 * (il proxy di Vite riscrive l'host, un reverse proxy che termina HTTPS cambia lo schema) e, se non
 * coincidono e l'origine non è tra quelle ammesse, risponde 403 alle richieste che lo portano
 * (POST, PUT, PATCH, DELETE).
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    /** Origini consentite (cors.origins) */
    private final CorsProperties proprieta;

    public CorsConfig(CorsProperties proprieta) {
        this.proprieta = proprieta;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(proprieta.origins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Il JavaScript di un'altra origine legge solo gli header «sicuri» di CORS (Content-Type...) e quelli esposti qui: senza
        // Retry-After il client non saprebbe quanto aspettare dopo un 429 dei limiti di frequenza (LimiteRichiesteFilter)
        config.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));
        config.setMaxAge(3600L); // il browser tiene in cache il preflight per 1 ora

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
