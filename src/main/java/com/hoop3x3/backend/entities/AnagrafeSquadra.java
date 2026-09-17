package com.hoop3x3.backend.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Squadra dell'anagrafe condivisa, con roster many-to-many verso i giocatori. */
@Entity
@Table(name = "anagrafe_squadre")
@Getter
@Setter
public class AnagrafeSquadra {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false) private String citta = "";
    @Column(nullable = false) private String anno = "";
    /** Punti ranking del circuito; stringa perché il form può lasciarlo vuoto */
    @Column(nullable = false) private String rank = "";
    @Column(nullable = false) private String referente = "";
    @Column(nullable = false) private String logo = "";
    @Column(nullable = false) private String website = "";
    @Column(nullable = false) private String instagram = "";
    @Column(nullable = false) private String note = "";

    // @OrderColumn mantiene l'ordine di inserimento dei giocatori nel roster
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "anagrafe_squadre_roster",
            joinColumns = @JoinColumn(name = "squadra_id"),
            inverseJoinColumns = @JoinColumn(name = "giocatore_id")
    )
    @OrderColumn(name = "posizione", nullable = false)
    private List<AnagrafeGiocatore> roster = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "autore_id", nullable = false)
    private Utente autore;

    @Column(name = "creato_il", nullable = false, updatable = false)
    private LocalDateTime creatoIl;

    @Column(name = "modificato_il", nullable = false)
    private LocalDateTime modificatoIl;

    @PrePersist
    private void onCreazione() {
        this.creatoIl = LocalDateTime.now();
        this.modificatoIl = LocalDateTime.now();
    }

    @PreUpdate
    private void onModifica() {
        this.modificatoIl = LocalDateTime.now();
    }
}
