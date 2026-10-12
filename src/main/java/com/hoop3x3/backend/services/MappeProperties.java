package com.hoop3x3.backend.services;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proprietà maps.*: la chiave con cui il server chiama Google Static Maps per la mappa dei campetti. Vive solo qui, sul
 * servizio: il sito statico non la vede (MAPS_API_KEY sul backend). Senza chiave la mappa risponde 503.
 *
 * @param staticApiKey la chiave dell'API Maps Static (MAPS_API_KEY), vuota se non configurata
 */
@ConfigurationProperties(prefix = "maps")
public record MappeProperties(@DefaultValue("") String staticApiKey) {

    /** La chiave non deve finire in un log o in un messaggio: chi stampa le proprietà vede solo se c'è */
    @Override
    public String toString() {
        if (staticApiKey == null || staticApiKey.isBlank()) return "MappeProperties[staticApiKey=assente]";
        return "MappeProperties[staticApiKey=impostata]";
    }
}
