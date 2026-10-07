package com.hoop3x3.backend.services;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proprietà groq.*: il servizio che risponde al Coach AI. Senza chiave il Coach è spento e non parte nessuna richiesta.
 *
 * @param api   la chiave dell'API (GROQ_API_KEY)
 * @param model il modello usato per le risposte
 */
@ConfigurationProperties(prefix = "groq")
public record GroqProperties(
        @DefaultValue Api api,
        // openai/gpt-oss-120b: gratuito su Groq, supporta il tool calling (llama-3.3-70b è stato dismesso)
        @DefaultValue("openai/gpt-oss-120b") String model
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
