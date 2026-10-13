package com.hoop3x3.backend.dto;

import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.support.Tempo;

import java.util.UUID;

/**
 * RegGiocatore del frontend: `autore` è il nome dell'autore, `ts` i millisecondi dell'ultima modifica, `versione` il numero
 * di volte che la scheda è stata cambiata (la decide il server, il client la rimanda con la PUT: vedi GiocatoreRequestDTO).
 * Ha due forme con le stesse chiavi JSON: la completa ({@link #from}, per chi ha un account) e la pubblica ({@link #pubblico}).
 */
public record GiocatoreDTO(
        UUID id, String nome, String cognome, String soprannome, String nascita, String citta,
        String nazionalita, String altezza, String peso, String ruolo, String numero, String squadra,
        String esperienza, String note, String autore, UUID autoreId, long ts, long versione
) {
    /** Valore dei campi riservati nella forma pubblica: il frontend nasconde le righe vuote */
    private static final String RISERVATO = "";

    /**
     * La forma completa, per chi ha un account. L'autore può essere stato eliminato (autore_id ON DELETE SET NULL, V11): la
     * scheda resta, con `autore` vuoto e `autoreId` null, come un campetto senza autore
     */
    public static GiocatoreDTO from(AnagrafeGiocatore g) {
        String autore = "";
        UUID autoreId = null;
        Utente u = g.getAutore();
        if (u != null) {
            autore = u.getNome();
            autoreId = u.getId();
        }
        return new GiocatoreDTO(g.getId(), g.getNome(), g.getCognome(), g.getSoprannome(), g.getNascita(),
                g.getCitta(), g.getNazionalita(), g.getAltezza(), g.getPeso(), g.getRuolo(), g.getNumero(),
                g.getSquadra(), g.getEsperienza(), g.getNote(), autore, autoreId,
                Tempo.inMillisecondi(g.getModificatoIl()), g.getVersione());
    }

    /**
     * La forma pubblica, per chi chiama senza un token valido: restano nome, cognome, soprannome, squadra, ruolo, numero e
     * versione; data di nascita, città, nazionalità, altezza, peso, esperienza, note e autore sono vuoti e `autoreId` è null.
     * L'autore non si legge: serve solo alla forma completa.
     */
    public static GiocatoreDTO pubblico(AnagrafeGiocatore g) {
        return new GiocatoreDTO(g.getId(), g.getNome(), g.getCognome(), g.getSoprannome(), RISERVATO,
                RISERVATO, RISERVATO, RISERVATO, RISERVATO, g.getRuolo(), g.getNumero(),
                g.getSquadra(), RISERVATO, RISERVATO, RISERVATO, null,
                Tempo.inMillisecondi(g.getModificatoIl()), g.getVersione());
    }
}
