package com.hoop3x3.backend.services;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proprietà ai.*: le scelte sui modelli che non riguardano un fornitore solo.
 *
 * @param lettoreFoglio chi legge le foto dei fogli di tappa (AI_LETTORE_FOGLIO): «groq» o «anthropic». Vuoto = lettura spenta,
 *                      POST /api/coach/foglio risponde 503 e il resto dell'app funziona com'è
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(@DefaultValue("") String lettoreFoglio) {}
