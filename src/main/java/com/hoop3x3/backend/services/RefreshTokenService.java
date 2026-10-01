package com.hoop3x3.backend.services;

import com.hoop3x3.backend.entities.RefreshToken;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import com.hoop3x3.backend.repositories.RefreshTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh token opachi: il browser li tiene in un cookie httpOnly, il database solo il loro hash.
 * Ogni token vale per un solo rinnovo (rotazione) e scade dopo auth.refresh-giorni.
 */
@Service
public class RefreshTokenService {

    /** Esito di un rinnovo: a chi appartiene il token e il nuovo token da mettere nel cookie */
    public record Rinnovo(Utente utente, String nuovoToken) {}

    private static final String SESSIONE_SCADUTA = "Sessione scaduta: accedi di nuovo";

    private final RefreshTokenRepository repository;
    private final long durataGiorni;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, @Value("${auth.refresh-giorni:30}") long durataGiorni) {
        this.repository = repository;
        this.durataGiorni = durataGiorni;
    }

    /** Genera un token casuale, ne salva l'hash e restituisce il token in chiaro: esiste solo nel cookie */
    @Transactional
    public String emetti(Utente utente) {
        repository.deleteByUtente_IdAndScadeIlBefore(utente.getId(), LocalDateTime.now());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(utente, sha256(token), LocalDateTime.now().plusDays(durataGiorni)));
        return token;
    }

    /** Scambia un token valido con uno nuovo: il vecchio viene cancellato, così vale per un solo rinnovo */
    @Transactional
    public Rinnovo ruota(String token) {
        RefreshToken salvato = repository.findByTokenHash(sha256(token))
                .orElseThrow(() -> new UnauthorizedException(SESSIONE_SCADUTA));
        if (salvato.isScaduto()) throw new UnauthorizedException(SESSIONE_SCADUTA);
        repository.delete(salvato);
        return new Rinnovo(salvato.getUtente(), emetti(salvato.getUtente()));
    }

    /** Logout: il token non vale più; un token sconosciuto si ignora */
    @Transactional
    public void revoca(String token) {
        repository.findByTokenHash(sha256(token)).ifPresent(repository::delete);
    }

    /** Hash esadecimale del token: è l'unica forma in cui finisce nel database */
    static String sha256(String testo) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(testo.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }
}
