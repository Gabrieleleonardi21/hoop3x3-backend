package com.hoop3x3.backend.dto;

import com.hoop3x3.backend.entities.Campetto;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.support.Tempo;

import java.util.UUID;

/**
 * Il campetto come lo legge il frontend: `autore` è il nome dell'autore (vuoto se non esiste più) e `autoreId` il suo id
 * (null se non esiste più), `ts` i millisecondi dell'ultima modifica, `versione` il numero di volte che il campetto è stato
 * cambiato (la decide il server, il client la rimanda con la PUT: vedi CampettoRequestDTO). Le stesse chiavi per l'ospite e
 * per chi ha un account; all'ospite però l'id dell'autore non si mostra (senzaAutoreId, come per l'anagrafe): il nome sì,
 * l'identificativo di una persona no. fonte e fonteId dell'entity non escono.
 */
public record CampettoDTO(
        UUID id, String nome, String indirizzo, String citta, double lat, double lng, String tipo, String superficie,
        int canestri, boolean illuminato, boolean coperto, boolean gratuito, boolean retine, boolean linee,
        boolean fontanella, String stato, String note, String autore, UUID autoreId, long versione, long ts
) {
    public static CampettoDTO from(Campetto c) {
        // L'autore può essere stato eliminato (autore_id ON DELETE SET NULL): il campetto resta, senza chi l'ha creato
        String autore = "";
        UUID autoreId = null;
        Utente u = c.getAutore();
        if (u != null) {
            autore = u.getNome();
            autoreId = u.getId();
        }
        return new CampettoDTO(c.getId(), c.getNome(), c.getIndirizzo(), c.getCitta(), c.getLat(), c.getLng(), c.getTipo(),
                c.getSuperficie(), c.getCanestri(), c.isIlluminato(), c.isCoperto(), c.isGratuito(), c.isRetine(),
                c.isLinee(), c.isFontanella(), c.getStato(), c.getNote(), autore, autoreId, c.getVersione(),
                Tempo.inMillisecondi(c.getModificatoIl()));
    }

    /** La forma per l'ospite: uguale, con autoreId a null */
    public CampettoDTO senzaAutoreId() {
        return new CampettoDTO(id, nome, indirizzo, citta, lat, lng, tipo, superficie, canestri, illuminato, coperto, gratuito,
                retine, linee, fontanella, stato, note, autore, null, versione, ts);
    }
}
