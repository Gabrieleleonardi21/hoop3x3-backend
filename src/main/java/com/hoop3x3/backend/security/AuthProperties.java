package com.hoop3x3.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Proprietà auth.*: il refresh token e il suo cookie. Le leggono sia il servizio che salva i token sia chi scrive il cookie:
 * in un posto solo, la durata è la stessa per tutti e due.
 *
 * @param refreshGiorni per quanti giorni vale un refresh token (e il suo cookie)
 * @param cookieSecure  cookie solo su HTTPS: vero in produzione (AUTH_COOKIE_SECURE), falso in sviluppo su http://localhost
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        @DefaultValue("30") long refreshGiorni,
        @DefaultValue("false") boolean cookieSecure
) {}
