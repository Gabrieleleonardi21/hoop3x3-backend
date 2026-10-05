package com.hoop3x3.backend.security;

import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Emette e verifica i JWT di accesso. Secret e durata arrivano da JwtProperties, validate all'avvio:
 * {@code @EnableConfigurationProperties} sta qui perché JwtProperties serve solo a questa classe.
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JWTtools {

    /** Messaggio del 401 per ogni token non valido (alterato, scaduto, malformato, vuoto): lo riusa anche JwtFilter */
    static final String TOKEN_NON_VALIDO = "Sessione scaduta o token non valido: accedi di nuovo";

    private final SecretKey chiave;
    /** Durata del token in minuti: scaduto, il client lo rinnova con il refresh token */
    private final long durataMinuti;

    public JWTtools(JwtProperties props) {
        // La chiave si costruisce una volta sola, dai byte UTF-8 del secret
        this.chiave = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.durataMinuti = props.durataMinuti();
    }

    public String generateToken(Utente utente) {
        long adesso = System.currentTimeMillis();
        return Jwts.builder()
                .subject(utente.getId().toString())
                .issuedAt(new Date(adesso))
                .expiration(new Date(adesso + 1000L * 60 * durataMinuti))
                .signWith(chiave)
                .compact();
    }

    /** Verifica firma e scadenza (401 se alterato/scaduto/malformato/vuoto) e restituisce le claims */
    public Claims verifyToken(String accessToken) {
        // Con un token nullo, vuoto o di soli spazi (per esempio da «Authorization: Bearer ») JJWT lancia
        // IllegalArgumentException e non una JwtException: senza questo controllo la richiesta risponderebbe 500
        if (accessToken == null || accessToken.isBlank()) throw new UnauthorizedException(TOKEN_NON_VALIDO);
        try {
            return Jwts.parser().verifyWith(chiave).build().parseSignedClaims(accessToken).getPayload();
        } catch (JwtException _) {
            throw new UnauthorizedException(TOKEN_NON_VALIDO);
        }
    }
}
