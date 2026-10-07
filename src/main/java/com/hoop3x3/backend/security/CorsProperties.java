package com.hoop3x3.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Proprietà cors.*: le origini da cui il browser può chiamare l'API.
 *
 * @param origins origini consentite; in application.properties sono separate da virgola (CORS_ORIGINS)
 */
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(
        @DefaultValue({"http://localhost:5173", "http://127.0.0.1:5173"}) List<String> origins
) {}
