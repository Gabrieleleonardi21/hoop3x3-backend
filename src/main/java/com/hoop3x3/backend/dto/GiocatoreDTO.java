package com.hoop3x3.backend.dto;

import com.hoop3x3.backend.entities.AnagrafeGiocatore;

import java.util.UUID;

/**
 * RegGiocatore del frontend: `autore` è il nome dell'autore, `ts` i millisecondi dell'ultima modifica. Ha due forme con le
 * stesse chiavi JSON: la completa ({@link #from}, per chi ha un account) e la pubblica ({@link #pubblico}).
 */
public record GiocatoreDTO(
        UUID id, String nome, String cognome, String soprannome, String nascita, String citta,
        String nazionalita, String altezza, String peso, String ruolo, String numero, String squadra,
        String esperienza, String note, String autore, UUID autoreId, long ts
) {
    /** Valore dei campi riservati nella forma pubblica: il frontend nasconde le righe vuote */
    private static final String RISERVATO = "";

    /** La forma completa, per chi ha un account */
    public static GiocatoreDTO from(AnagrafeGiocatore g) {
        return new GiocatoreDTO(g.getId(), g.getNome(), g.getCognome(), g.getSoprannome(), g.getNascita(),
                g.getCitta(), g.getNazionalita(), g.getAltezza(), g.getPeso(), g.getRuolo(), g.getNumero(),
                g.getSquadra(), g.getEsperienza(), g.getNote(), g.getAutore().getNome(), g.getAutore().getId(),
                TempoSupport.inMillisecondi(g.getModificatoIl()));
    }

    /**
     * La forma pubblica, per chi chiama senza un token valido: restano nome, cognome, soprannome, squadra, ruolo e numero;
     * data di nascita, città, nazionalità, altezza, peso, esperienza, note e autore sono vuoti e `autoreId` è null. L'autore
     * non si legge: serve solo alla forma completa.
     */
    public static GiocatoreDTO pubblico(AnagrafeGiocatore g) {
        return new GiocatoreDTO(g.getId(), g.getNome(), g.getCognome(), g.getSoprannome(), RISERVATO,
                RISERVATO, RISERVATO, RISERVATO, RISERVATO, g.getRuolo(), g.getNumero(),
                g.getSquadra(), RISERVATO, RISERVATO, RISERVATO, null,
                TempoSupport.inMillisecondi(g.getModificatoIl()));
    }
}
