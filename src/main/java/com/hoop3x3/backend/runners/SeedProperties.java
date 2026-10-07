package com.hoop3x3.backend.runners;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proprietà seed.*: l'admin creato all'avvio (DataSeeder) e i dati di prova (DemoSeeder).
 *
 * @param demo  carica i dati di prova all'avvio (SEED_DEMO); si caricano una sola volta, vedi DemoSeeder
 * @param admin l'admin da creare se non c'è (ADMIN_EMAIL, ADMIN_PASSWORD); i dati di prova sono intestati a lui
 */
@ConfigurationProperties(prefix = "seed")
public record SeedProperties(
        @DefaultValue("false") boolean demo,
        @DefaultValue Admin admin
) {
    /** Le credenziali dell'admin: vuote se non configurate */
    public record Admin(@DefaultValue("") String email, @DefaultValue("") String password) {
        /** Il toString() automatico di un record scrive tutti i campi, password compresa: chi stampa le proprietà non la porta nei log */
        @Override
        public String toString() {
            return "Admin[email=" + email + "]";
        }
    }
}
