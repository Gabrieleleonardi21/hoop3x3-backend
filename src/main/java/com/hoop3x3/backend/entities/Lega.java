package com.hoop3x3.backend.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Contenitore di tappe; ogni lega appartiene a un solo utente. */
@Entity
@Table(name = "leghe")
@Getter
@Setter
public class Lega {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(nullable = false)
    private String nome;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Utente owner;

    // orphanRemoval: eliminare una tappa dalla lista la cancella anche a database.
    // Ordine: per posizione, poi dalla più vecchia, poi per id. Lo spareggio serve ai database già in uso, che hanno
    // posizioni doppie (BE-10): senza, PostgreSQL darebbe le tappe a pari posizione in un ordine qualsiasi
    @OneToMany(mappedBy = "lega", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("posizione ASC, creatoIl ASC, id ASC")
    private List<Tappa> tappe = new ArrayList<>();

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

    public Lega() {}

    public Lega(String nome, Utente owner) {
        this.nome = nome;
        this.owner = owner;
    }

    /** Aggiorna il timestamp anche quando cambia solo una tappa figlia (usato dall'indice leghe). */
    public void touch() {
        this.modificatoIl = LocalDateTime.now();
    }
}
