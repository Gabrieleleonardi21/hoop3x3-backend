package com.hoop3x3.backend.services;

import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ForbiddenException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Regola unica di proprietà: una risorsa la modifica il suo autore/proprietario oppure un ADMIN. */
@Component
public class AccessGuard {

    public void checkOwner(Utente corrente, UUID ownerId, String cosa) {
        if (corrente.isAdmin()) return;
        if (corrente.getId().equals(ownerId)) return;
        throw new ForbiddenException("Solo chi ha creato " + cosa + " (o un ADMIN) può modificarla");
    }
}
