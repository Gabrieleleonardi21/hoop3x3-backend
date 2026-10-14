package com.hoop3x3.backend.services;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proprietà anthropic.*: il fornitore alternativo a Groq per la lettura dei fogli (ai.lettore-foglio=anthropic). Senza chiave
 * la lettura con Anthropic è spenta.
 *
 * @param api   la chiave dell'API (ANTHROPIC_API_KEY)
 * @param model il modello con immagini (ANTHROPIC_MODEL): deve accettare uno strumento forzato (tool_choice), come Haiku 5.5
 */
@ConfigurationProperties(prefix = "anthropic")
public record AnthropicProperties(
        @DefaultValue Api api,
        @DefaultValue("claude-haiku-5-5") String model
) {
    /** La chiave dell'API: vuota se non configurata */
    public record Api(@DefaultValue("") String key) {
        /** Il toString() automatico di un record scrive la chiave: chi stampa le proprietà non la porta nei log */
        @Override
        public String toString() {
            if (key == null || key.isBlank()) return "Api[key=assente]";
            return "Api[key=impostata]";
        }
    }
}
