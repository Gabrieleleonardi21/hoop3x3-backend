package com.hoop3x3.backend.entities;

import com.hoop3x3.backend.support.Tempo;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Token del link di recupero password (migrazione V9): in tabella c'è solo l'hash SHA-256, il token in chiaro esiste solo
 * nell'email. Vale fino a `scadenza` e una volta sola: usatoIl lo segnano le query di TokenRecuperoPasswordRepository, mai
 * l'entità, così due richieste contemporanee non lo usano entrambe.
 */
@Entity
@Table(name = "token_recupero_password")
@Getter
public class TokenRecuperoPassword {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utente_id", nullable = false)
    private Utente utente;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime scadenza;

    /** Quando è stato usato o sostituito da uno più nuovo: null = ancora utilizzabile */
    @Column(name = "usato_il")
    private LocalDateTime usatoIl;

    @Column(name = "creato_il", nullable = false, updatable = false)
    private LocalDateTime creatoIl;

    @PrePersist
    private void onCreazione() {
        this.creatoIl = Tempo.adesso();
    }

    public TokenRecuperoPassword() {}

    public TokenRecuperoPassword(Utente utente, String tokenHash, LocalDateTime scadenza) {
        this.utente = utente;
        this.tokenHash = tokenHash;
        this.scadenza = scadenza;
    }

    /** Non ancora usato (né sostituito) e non scaduto */
    public boolean isUtilizzabile() {
        return usatoIl == null && scadenza.isAfter(Tempo.adesso());
    }
}
