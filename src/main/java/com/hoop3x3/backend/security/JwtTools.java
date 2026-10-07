package com.hoop3x3.backend.security;

import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Deserializer;
import io.jsonwebtoken.io.Serializer;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * Emette e verifica i JWT di accesso. Secret e durata arrivano da JwtProperties, validate all'avvio:
 * {@code @EnableConfigurationProperties} sta qui perché JwtProperties serve solo a questa classe.
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtTools {

    /** Messaggio del 401 per ogni token non valido (alterato, scaduto, malformato, vuoto): lo riusa anche JwtFilter */
    static final String TOKEN_NON_VALIDO = "Sessione scaduta o token non valido: accedi di nuovo";

    private final SecretKey chiave;
    /** Durata del token in minuti: scaduto, il client lo rinnova con il refresh token */
    private final long durataMinuti;
    // Il JSON dei token lo scrive e lo legge Jackson 3, non jjwt-jackson (che porterebbe Jackson 2): vedi JwtJson
    private final Serializer<Map<String, ?>> serializzatore;
    private final Deserializer<Map<String, ?>> deserializzatore;

    public JwtTools(JwtProperties props, ObjectMapper mapper) {
        // La chiave si costruisce una volta sola, dai byte UTF-8 del secret
        this.chiave = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.durataMinuti = props.durataMinuti();
        this.serializzatore = JwtJson.serializzatore(mapper);
        this.deserializzatore = JwtJson.deserializzatore(mapper);
    }

    public String generateToken(Utente utente) {
        long adesso = System.currentTimeMillis();
        return Jwts.builder()
                .subject(utente.getId().toString())
                .issuedAt(new Date(adesso))
                .expiration(new Date(adesso + 1000L * 60 * durataMinuti))
                .signWith(chiave)
                .json(serializzatore)
                .compact();
    }

    /** Verifica firma e scadenza (401 se alterato/scaduto/malformato/vuoto) e restituisce le claims */
    public Claims verifyToken(String accessToken) {
        // Con un token nullo, vuoto o di soli spazi (per esempio da «Authorization: Bearer ») JJWT lancia
        // IllegalArgumentException e non una JwtException: senza questo controllo la richiesta risponderebbe 500
        if (accessToken == null || accessToken.isBlank()) throw new UnauthorizedException(TOKEN_NON_VALIDO);
        try {
            return Jwts.parser().verifyWith(chiave).json(deserializzatore).build().parseSignedClaims(accessToken).getPayload();
        } catch (JwtException _) {
            throw new UnauthorizedException(TOKEN_NON_VALIDO);
        }
    }
}
