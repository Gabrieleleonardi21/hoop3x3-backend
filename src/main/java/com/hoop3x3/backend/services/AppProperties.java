package com.hoop3x3.backend.services;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proprietà app.*: ciò che serve al recupero password.
 *
 * @param frontendUrl l'indirizzo pubblico del frontend (APP_FRONTEND_URL), con cui si costruisce il link dell'email
 *                    ({frontendUrl}/reimposta-password?token=...). Su Render è https://hoop3x3.onrender.com
 * @param brevo       la chiave dell'API di Brevo (BREVO_API_KEY), che manda le email
 * @param mail        il mittente delle email (MAIL_USERNAME), un indirizzo verificato in Brevo
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("http://localhost:5173") String frontendUrl,
        @DefaultValue Brevo brevo,
        @DefaultValue Mail mail
) {
    /** La chiave dell'API di Brevo: vuota se non configurata (le email non partono) */
    public record Brevo(@DefaultValue("") String apiKey) {
        /** Il toString() automatico di un record scrive la chiave: chi stampa le proprietà non la porta nei log */
        @Override
        public String toString() {
            if (apiKey == null || apiKey.isBlank()) return "Brevo[apiKey=assente]";
            return "Brevo[apiKey=impostata]";
        }
    }

    /** Il mittente delle email: vuoto se non configurato (le email non partono) */
    public record Mail(@DefaultValue("") String mittente) {}
}
