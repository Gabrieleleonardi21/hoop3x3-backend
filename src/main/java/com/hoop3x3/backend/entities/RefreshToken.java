package com.hoop3x3.backend.entities;

import com.hoop3x3.backend.support.Tempo;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** Refresh token di un utente: in tabella c'è solo l'hash SHA-256, il token in chiaro vive nel cookie httpOnly. */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utente_id", nullable = false)
    private Utente utente;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "scade_il", nullable = false)
    private LocalDateTime scadeIl;

    @Column(name = "creato_il", nullable = false, updatable = false)
    private LocalDateTime creatoIl;

    @PrePersist
    private void onCreazione() {
        this.creatoIl = Tempo.adesso();
    }

    public RefreshToken() {}

    public RefreshToken(Utente utente, String tokenHash, LocalDateTime scadeIl) {
        this.utente = utente;
        this.tokenHash = tokenHash;
        this.scadeIl = scadeIl;
    }

    public boolean isScaduto() {
        return scadeIl.isBefore(Tempo.adesso());
    }
}
