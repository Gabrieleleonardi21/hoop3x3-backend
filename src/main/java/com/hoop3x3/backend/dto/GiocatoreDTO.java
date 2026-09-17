package com.hoop3x3.backend.dto;

import com.hoop3x3.backend.entities.AnagrafeGiocatore;

import java.time.ZoneId;
import java.util.UUID;

/** RegGiocatore del frontend: `autore` è il nome dell'autore, `ts` i millisecondi dell'ultima modifica */
public record GiocatoreDTO(
        UUID id, String nome, String cognome, String soprannome, String nascita, String citta,
        String nazionalita, String altezza, String peso, String ruolo, String numero, String squadra,
        String esperienza, String note, String autore, UUID autoreId, long ts
) {
    public static GiocatoreDTO from(AnagrafeGiocatore g) {
        long ts = g.getModificatoIl().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new GiocatoreDTO(g.getId(), g.getNome(), g.getCognome(), g.getSoprannome(), g.getNascita(),
                g.getCitta(), g.getNazionalita(), g.getAltezza(), g.getPeso(), g.getRuolo(), g.getNumero(),
                g.getSquadra(), g.getEsperienza(), g.getNote(), g.getAutore().getNome(), g.getAutore().getId(), ts);
    }
}
