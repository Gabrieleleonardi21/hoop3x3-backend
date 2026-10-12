package com.hoop3x3.backend.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Un campetto da basket geolocalizzato (migrazione V8): visibile a tutti, lo aggiunge chi ha un account e lo modifica
 * l'autore o un ADMIN. A differenza delle schede dell'anagrafe sopravvive al suo autore (autore_id ON DELETE SET NULL): è un
 * dato del territorio, non di una persona. Senza autore lo modifica solo un ADMIN.
 */
@Entity
@Table(name = "campetti")
@Getter
@Setter
public class Campetto extends ConDate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false) private String indirizzo = "";
    @Column(nullable = false) private String citta = "";

    @Column(nullable = false) private double lat;
    @Column(nullable = false) private double lng;

    /** campetto | palestra | arena: lo decide il server (le creazioni dall'app sono sempre «campetto»), il client lo legge */
    @Column(nullable = false) private String tipo = "campetto";

    /** Asfalto | Cemento | Sintetico | Altro (i valori ammessi li controlla CampettoRequestDTO) */
    @Column(nullable = false) private String superficie;

    /** La colonna è SMALLINT: Hibernate (ddl-auto=validate) la accetta solo per uno short */
    @Column(nullable = false) private short canestri;

    @Column(nullable = false) private boolean illuminato;
    @Column(nullable = false) private boolean coperto;
    @Column(nullable = false) private boolean gratuito;
    @Column(nullable = false) private boolean retine;
    @Column(nullable = false) private boolean linee;
    @Column(nullable = false) private boolean fontanella;

    /** buono | discreto | da sistemare */
    @Column(nullable = false) private String stato;

    @Column(nullable = false) private String note = "";

    /** Null se l'autore è stato eliminato: la riga resta e la modifica solo un ADMIN */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "autore_id")
    private Utente autore;

    /** Fonte esterna dei campetti importati (null per quelli creati dall'app) e il loro id in quella fonte: non escono dall'API */
    private String fonte;
    @Column(name = "fonte_id") private String fonteId;

    /**
     * Versione del campetto, come per le schede dell'anagrafe: Hibernate la aumenta a ogni UPDATE e la mette nella condizione
     * dell'UPDATE, così due salvataggi insieme non si sovrascrivono. Il client la riceve con il campetto e può rimandarla con
     * la PUT (CampettoService.controllaVersione). Senza setter: la gestisce Hibernate
     */
    @Version
    @Setter(AccessLevel.NONE)
    private long versione;
}
