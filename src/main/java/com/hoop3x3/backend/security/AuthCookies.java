package com.hoop3x3.backend.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Cookie del refresh token: HttpOnly (il JavaScript non lo legge), SameSite=Lax (non parte da siti terzi),
 * Path=/api/auth (viaggia solo verso gli endpoint di autenticazione). Secure va acceso in produzione con HTTPS.
 */
@Component
@EnableConfigurationProperties(AuthProperties.class)
public class AuthCookies {

    public static final String NOME = "hoop3x3_refresh";

    private final long durataGiorni;
    private final boolean secure;

    public AuthCookies(AuthProperties proprieta) {
        this.durataGiorni = proprieta.refreshGiorni();
        this.secure = proprieta.cookieSecure();
    }

    public ResponseCookie diRefresh(String token) {
        return base(token).maxAge(Duration.ofDays(durataGiorni)).build();
    }

    /** Stesso nome e path con Max-Age=0: il browser lo cancella */
    public ResponseCookie cancellazione() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String valore) {
        return ResponseCookie.from(NOME, valore)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/api/auth");
    }
}
