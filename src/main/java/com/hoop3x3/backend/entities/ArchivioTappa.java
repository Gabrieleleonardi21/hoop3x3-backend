package com.hoop3x3.backend.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Snapshot di una tappa conclusa e pubblicata nell'Archivio circuito.
 * La chiave è l'id della tappa: ripubblicare sovrascrive lo snapshot.
 */
@Entity
@Table(name = "archivio_tappe")
@Getter
@Setter
public class ArchivioTappa {

    @Id
    @Column(name = "tappa_id")
    private UUID tappaId;

    @Column(name = "lega_nome", nullable = false)
    private String legaNome;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "autore_id", nullable = false)
    private Utente autore;

    /** Tappa completa (stesso formato del frontend) in JSONB */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String contenuto;

    @Column(name = "pubblicato_il", nullable = false)
    private LocalDateTime pubblicatoIl;
}
