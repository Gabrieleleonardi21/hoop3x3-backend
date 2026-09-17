package com.hoop3x3.backend.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tappa di una lega. L'id lo genera il client (crypto.randomUUID) così lo store
 * del frontend resta sincrono e le rotte /lega/tappa/:id funzionano subito.
 * Squadre, gironi, partite, bracket e video sono JSONB (stringhe JSON già
 * validate dal service): il motore torneo li tratta come blocco unico.
 */
@Entity
@Table(name = "tappe")
@Getter
@Setter
public class Tappa {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lega_id", nullable = false)
    private Lega lega;

    /** Ordine della tappa dentro la lega */
    @Column(nullable = false)
    private int posizione;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private String luogo = "";

    /** ISO yyyy-mm-dd oppure stringa vuota (valore dell'input date del frontend) */
    @Column(nullable = false)
    private String data = "";

    @Column(name = "n_gironi", nullable = false)
    private int nGironi = 1;

    @Column(nullable = false)
    private boolean conclusa = false;

    @Embedded
    private Regole regole = new Regole();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String squadre = "[]";

    /** null = gironi non ancora sorteggiati */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String gironi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String partite = "[]";

    /** null = fase a eliminazione diretta non generata */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String bracket;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String video = "[]";

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
