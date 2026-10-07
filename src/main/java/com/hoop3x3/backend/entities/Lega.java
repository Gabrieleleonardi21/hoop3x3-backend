package com.hoop3x3.backend.entities;

import com.hoop3x3.backend.support.Tempo;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Contenitore di tappe; ogni lega appartiene a un solo utente.
 * DynamicUpdate: l'UPDATE contiene solo le colonne cambiate. Aggiungere o eliminare una tappa, o salvarla con un contenuto
 * diverso, chiama touch() e cambia solo modificato_il: senza, Hibernate riscrive anche il nome letto all'inizio della richiesta, e una rinomina confermata nel
 * frattempo da un altro dispositivo tornerebbe com'era.
 */
@Entity
@DynamicUpdate
@Table(name = "leghe")
@Getter
@Setter
public class Lega extends ConDate {

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

    public Lega() {}

    public Lega(String nome, Utente owner) {
        this.nome = nome;
        this.owner = owner;
    }

    /** Aggiorna il timestamp anche quando cambia solo una tappa figlia (usato dall'indice leghe). */
    public void touch() {
        setModificatoIl(Tempo.adesso());
    }
}
