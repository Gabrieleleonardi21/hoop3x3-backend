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
 * La chiave è l'id della tappa: ripubblicare sovrascrive lo snapshot. Lo costruisce il server
 * (ArchivioService.pubblica) dalla tappa salvata, e l'autore è il proprietario della sua lega.
 * Eliminare la tappa, la sua lega o il suo proprietario elimina anche la pubblicazione: lo fa il database,
 * con la chiave esterna su tappa_id della migrazione V2.
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

    /**
     * Ciò che l'elenco pubblico mostra della tappa (migrazione V6): copie di nome, luogo, data e numero delle squadre iscritte,
     * scritte alla pubblicazione, così l'elenco non decomprime il JSONB del contenuto a ogni richiesta. Le pubblicazioni
     * fatte prima della V6 le ha riempite la migrazione dal contenuto
     */
    @Column(nullable = false)
    private String nome = "";

    @Column(nullable = false)
    private String luogo = "";

    @Column(nullable = false)
    private String data = "";

    @Column(name = "numero_squadre", nullable = false)
    private int numeroSquadre;

    /** La tappa nella forma delle API (LegaService.toDto, lo stesso formato del frontend) in JSONB */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String contenuto;

    @Column(name = "pubblicato_il", nullable = false)
    private LocalDateTime pubblicatoIl;
}
