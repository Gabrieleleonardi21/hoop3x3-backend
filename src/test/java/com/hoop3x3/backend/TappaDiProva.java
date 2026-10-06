package com.hoop3x3.backend;

import com.hoop3x3.backend.dto.RegoleDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * La tappa dei test: l'unico punto dove si chiama il costruttore di TappaDTO, che ha un argomento per campo. Un campo nuovo
 * del DTO si aggiunge qui e non in ogni test che costruisce una tappa. Parte da una tappa valida di Roma, non conclusa,
 * con un id nuovo e senza squadre né partite: ogni test cambia solo ciò che gli serve.
 */
public final class TappaDiProva {

    private UUID id = UUID.randomUUID();
    private String nome = "Tappa";
    private String luogo = "Roma";
    private String data = "2026-06-14";
    private String squadre = "[]";
    private String partite = "[]";
    private boolean conclusa = false;

    private TappaDiProva() {}

    public static TappaDiProva tappa() {
        return new TappaDiProva();
    }

    public TappaDiProva id(UUID id) {
        this.id = id;
        return this;
    }

    public TappaDiProva nome(String nome) {
        this.nome = nome;
        return this;
    }

    public TappaDiProva luogo(String luogo) {
        this.luogo = luogo;
        return this;
    }

    public TappaDiProva data(String data) {
        this.data = data;
        return this;
    }

    /** I blocchi di gioco si scrivono come JSON, come li manda il frontend */
    public TappaDiProva squadre(String json) {
        this.squadre = json;
        return this;
    }

    public TappaDiProva partite(String json) {
        this.partite = json;
        return this;
    }

    public TappaDiProva conclusa(boolean conclusa) {
        this.conclusa = conclusa;
        return this;
    }

    public TappaDTO build() {
        // La lettura di una stringa JSON non dipende dalla configurazione del mapper: basta quello condiviso di Jackson
        JsonMapper json = JsonMapper.shared();
        return new TappaDTO(id, nome, luogo, data, 1, new RegoleDTO(21, 10, 2, 12),
                json.readTree(squadre), null, json.readTree(partite), json.readTree("[]"), conclusa, null);
    }
}
