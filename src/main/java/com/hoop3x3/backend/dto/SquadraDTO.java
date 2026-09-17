package com.hoop3x3.backend.dto;

import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** RegSquadra del frontend: il roster è la lista di id dei giocatori */
public record SquadraDTO(
        UUID id, String nome, String citta, String anno, String rank, String referente,
        List<UUID> roster, String logo, String website, String instagram, String note,
        String autore, UUID autoreId, long ts
) {
    public static SquadraDTO from(AnagrafeSquadra s) {
        long ts = s.getModificatoIl().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<UUID> roster = s.getRoster().stream().map(AnagrafeGiocatore::getId).toList();
        return new SquadraDTO(s.getId(), s.getNome(), s.getCitta(), s.getAnno(), s.getRank(), s.getReferente(),
                roster, s.getLogo(), s.getWebsite(), s.getInstagram(), s.getNote(),
                s.getAutore().getNome(), s.getAutore().getId(), ts);
    }
}
