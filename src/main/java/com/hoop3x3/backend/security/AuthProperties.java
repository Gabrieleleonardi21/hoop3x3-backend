package com.hoop3x3.backend.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Proprietà auth.*: il refresh token e il suo cookie. Le leggono sia il servizio che salva i token sia chi scrive il cookie:
 * in un posto solo, la durata è la stessa per tutti e due. Sono validate all'avvio: con 0 giorni il cookie uscirebbe con
 * Max-Age=0 (il browser lo cancella subito) e ogni token nascerebbe già scaduto, senza nessun errore. Il server non parte e
 * dice quale proprietà è sbagliata.
 *
 * @param refreshGiorni per quanti giorni vale un refresh token (e il suo cookie): da 1 a 365
 * @param cookieSecure  cookie solo su HTTPS: vero di base (produzione), falso solo in sviluppo su http (AUTH_COOKIE_SECURE=false)
 */
@ConfigurationProperties(prefix = "auth")
@Validated
public record AuthProperties(
        @DefaultValue("30")
        @Min(value = 1, message = "auth.refresh-giorni deve essere almeno 1")
        @Max(value = 365, message = "auth.refresh-giorni deve essere al massimo 365")
        long refreshGiorni,
        @DefaultValue("true") boolean cookieSecure
) {}
