package com.hoop3x3.backend.services;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proprietà nominatim.*: il geocoding dei luoghi (Nominatim, OpenStreetMap) chiamato dal server. Nominatim chiede uno
 * User-Agent che identifichi l'applicazione: quello di base dice chi è HOOP 3X3.
 *
 * @param userAgent l'intestazione User-Agent delle richieste a Nominatim
 */
@ConfigurationProperties(prefix = "nominatim")
public record NominatimProperties(@DefaultValue("HOOP3X3/1.0 (progetto HOOP 3X3)") String userAgent) {}
