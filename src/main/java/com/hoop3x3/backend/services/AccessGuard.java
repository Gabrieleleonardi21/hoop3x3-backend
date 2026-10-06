package com.hoop3x3.backend.services;

import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ForbiddenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Regola unica di proprietà: una risorsa la modifica il suo autore/proprietario oppure un ADMIN. */
@Slf4j
@Component
public class AccessGuard {

    public void checkOwner(Utente corrente, UUID ownerId, String cosa) {
        if (corrente.isAdmin()) return;
        if (corrente.getId().equals(ownerId)) return;
        throw new ForbiddenException("Solo chi ha creato " + cosa + " (o un ADMIN) può modificarla");
    }

    /**
     * Lascia una riga INFO nei log quando un ADMIN modifica i dati di un altro utente. La chiamano le operazioni che
     * scrivono, dopo checkOwner e subito prima di scrivere, quando ogni verifica che può respingere la richiesta (404,
     * 409, 400) è già passata: la riga dice che l'intervento c'è stato. Non sta dentro checkOwner perché lo attraversano
     * anche le letture (il dettaglio di una lega): un ADMIN che apre i dati di un altro non deve riempire i log.
     *
     * @param risorsa che cosa si modifica («lega», «tappa», «giocatore»...), non il suo nome: i nomi li scrivono gli utenti
     */
    public void tracciaModifica(Utente corrente, UUID ownerId, String risorsa, UUID id) {
        traccia(corrente, ownerId, "modifica", risorsa, id);
    }

    /** Come {@link #tracciaModifica}, per le eliminazioni */
    public void tracciaEliminazione(Utente corrente, UUID ownerId, String risorsa, UUID id) {
        traccia(corrente, ownerId, "eliminazione", risorsa, id);
    }

    private static void traccia(Utente corrente, UUID ownerId, String azione, String risorsa, UUID id) {
        // Sui dati propri un ADMIN è un utente come gli altri
        if (!corrente.isAdmin() || corrente.getId().equals(ownerId)) return;
        // Solo id ed email dell'ADMIN, mai testo scritto da altri utenti (come i nomi). Un ADMIN non passa dalla registrazione:
        // nasce solo da DataSeeder, con l'ADMIN_EMAIL della configurazione, ed è per questo che qui non serve perLog
        log.info("Intervento ADMIN: {} (id {}): {} {} {} di proprietà dell'utente {}",
                corrente.getEmail(), corrente.getId(), azione, risorsa, id, ownerId);
    }
}
