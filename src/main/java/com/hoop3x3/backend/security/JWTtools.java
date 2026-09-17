package com.hoop3x3.backend.security;

import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JWTtools {

    @Value("${jwt.secret}")
    private String secret;

    /** Durata del token in giorni (default 7): scaduto, l'utente rifà il login */
    @Value("${jwt.durata-giorni:7}")
    private long durataGiorni;

    public String generateToken(Utente utente) {
        long adesso = System.currentTimeMillis();
        return Jwts.builder()
                .subject(utente.getId().toString())
                .issuedAt(new Date(adesso))
                .expiration(new Date(adesso + 1000L * 60 * 60 * 24 * durataGiorni))
                .signWith(getSecretKey())
                .compact();
    }

    /** Verifica firma e scadenza (401 se alterato/scaduto/malformato) e restituisce le claims */
    public Claims verifyToken(String accessToken) {
        try {
            return Jwts.parser().verifyWith(getSecretKey()).build().parseSignedClaims(accessToken).getPayload();
        } catch (JwtException e) {
            throw new UnauthorizedException("Sessione scaduta o token non valido: accedi di nuovo");
        }
    }

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }
}
