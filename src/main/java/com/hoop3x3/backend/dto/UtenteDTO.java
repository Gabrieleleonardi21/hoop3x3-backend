package com.hoop3x3.backend.dto;

import com.hoop3x3.backend.entities.Utente;

import java.util.UUID;

/** Utente esposto al client (mai la password). `name` è il campo che il frontend usa come User.name */
public record UtenteDTO(UUID id, String name, String email, String ruolo) {
    public static UtenteDTO from(Utente u) {
        return new UtenteDTO(u.getId(), u.getNome(), u.getEmail(), u.getRuolo().name());
    }
}
