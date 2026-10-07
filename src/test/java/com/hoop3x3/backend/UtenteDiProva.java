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
        Utente utente = new Utente(email, "hash", "Nome", Ruolo.USER);
        ReflectionTestUtils.setField(utente, "id", UUID.randomUUID());
        return utente;
    }
}
