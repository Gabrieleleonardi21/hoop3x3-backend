package com.hoop3x3.backend.services;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proprietà app.*: per ora solo l'indirizzo pubblico del frontend (APP_FRONTEND_URL), con cui il recupero password costruisce
 * il link dell'email ({frontendUrl}/reimposta-password?token=...). Su Render è https://hoop3x3.onrender.com.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(@DefaultValue("http://localhost:5173") String frontendUrl) {}
