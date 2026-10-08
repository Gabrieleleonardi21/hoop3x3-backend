package com.hoop3x3.backend.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** Giocatore dell'anagrafe condivisa del circuito (visibile a tutti, modificabile dall'autore o ADMIN). */
@Entity
@Table(name = "anagrafe_giocatori")
@Getter
@Setter
public class AnagrafeGiocatore extends ConDate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private String cognome;

    @Column(nullable = false) private String soprannome = "";
    @Column(nullable = false) private String nascita = "";
    @Column(nullable = false) private String citta = "";
    @Column(nullable = false) private String nazionalita = "";
    @Column(nullable = false) private String altezza = "";
    @Column(nullable = false) private String peso = "";
    @Column(nullable = false) private String ruolo = "";
    @Column(nullable = false) private String numero = "";
    /** Nome libero della squadra: non è una FK perché il giocatore può essere svincolato */
    @Column(nullable = false) private String squadra = "";
    @Column(nullable = false) private String esperienza = "";
    @Column(nullable = false) private String note = "";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "autore_id", nullable = false)
    private Utente autore;

    /**
     * Versione della scheda (migrazione V5), come per Tappa: Hibernate la aumenta a ogni UPDATE e la mette nella condizione
     * dell'UPDATE, così due salvataggi insieme non si sovrascrivono. Il client la riceve con la scheda e può rimandarla con la
     * PUT (AnagrafeService.controllaVersione). Senza setter: la gestisce Hibernate
     */
    @Version
    @Setter(AccessLevel.NONE)
    private long versione;

}
