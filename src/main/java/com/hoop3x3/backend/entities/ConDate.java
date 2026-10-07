package com.hoop3x3.backend.entities;

import com.hoop3x3.backend.support.Tempo;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Le date di creazione e di ultima modifica delle entity, scritte in un posto solo e in UTC (Tempo). Le colonne sono le stesse
 * di prima in ogni tabella (creato_il, modificato_il): lo schema non cambia.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class ConDate {

    @Column(name = "creato_il", nullable = false, updatable = false)
    private LocalDateTime creatoIl;

    @Column(name = "modificato_il", nullable = false)
    private LocalDateTime modificatoIl;

    @PrePersist
    private void onCreazione() {
        LocalDateTime adesso = Tempo.adesso();
        this.creatoIl = adesso;
        this.modificatoIl = adesso;
    }

    @PreUpdate
    private void onModifica() {
        this.modificatoIl = Tempo.adesso();
    }
}
