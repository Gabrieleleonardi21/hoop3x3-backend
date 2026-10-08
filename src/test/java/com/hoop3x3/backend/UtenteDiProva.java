package com.hoop3x3.backend;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

/** Un utente come lo ricostruisce il JwtFilter dal database, cioè con l'id: l'entità non ha un setter, lo assegna il salvataggio */
public final class UtenteDiProva {

    private UtenteDiProva() {}

    /** Un utente comune con quell'email e un id casuale */
    public static Utente conId(String email) {
        return conId(email, Ruolo.USER);
    }

    /** Un utente con quell'email, quel ruolo e un id casuale */
    public static Utente conId(String email, Ruolo ruolo) {
        Utente utente = new Utente(email, "hash", "Nome", ruolo);
        ReflectionTestUtils.setField(utente, "id", UUID.randomUUID());
        return utente;
    }
}
