package com.hoop3x3.backend.entities;

import com.hoop3x3.backend.support.Tempo;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Token del link di conferma del cambio email (migrazione V10), gemello di TokenRecuperoPassword con in più l'email nuova:
 * l'account la prende solo alla conferma. In tabella c'è solo l'hash SHA-256, il token in chiaro esiste solo nell'email. Vale
 * fino a `scadenza` e una volta sola: usatoIl lo segnano le query di TokenCambioEmailRepository, mai l'entità, così due
 * richieste contemporanee non lo usano entrambe.
 */
@Entity
@Table(name = "token_cambio_email")
@Getter
public class TokenCambioEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utente_id", nullable = false)
    private Utente utente;

    /** L'indirizzo nuovo, già normalizzato: diventa l'email dell'account alla conferma */
    @Column(name = "email_nuova", nullable = false)
    private String emailNuova;

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

    public TokenCambioEmail() {}

    public TokenCambioEmail(Utente utente, String emailNuova, String tokenHash, LocalDateTime scadenza) {
        this.utente = utente;
        this.emailNuova = emailNuova;
        this.tokenHash = tokenHash;
        this.scadenza = scadenza;
    }

    /** Non ancora usato (né sostituito) e non scaduto */
    public boolean isUtilizzabile() {
        return usatoIl == null && scadenza.isAfter(Tempo.adesso());
    }
}
