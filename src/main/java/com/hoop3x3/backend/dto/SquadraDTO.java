package com.hoop3x3.backend.dto;

import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;

import java.util.List;
import java.util.UUID;

/**
 * RegSquadra del frontend: il roster è la lista di id dei giocatori. Ha due forme con le stesse chiavi JSON: la completa
 * ({@link #from}, per chi ha un account) e la pubblica ({@link #pubblica}).
 */
public record SquadraDTO(
        UUID id, String nome, String citta, String anno, String rank, String referente,
        List<UUID> roster, String logo, String website, String instagram, String note,
        String autore, UUID autoreId, long ts
) {
    /** Valore dei campi riservati nella forma pubblica: il frontend nasconde le righe vuote */
    private static final String RISERVATO = "";

    /** La forma completa, per chi ha un account */
    public static SquadraDTO from(AnagrafeSquadra s) {
        return new SquadraDTO(s.getId(), s.getNome(), s.getCitta(), s.getAnno(), s.getRank(), s.getReferente(),
                idDelRoster(s), s.getLogo(), s.getWebsite(), s.getInstagram(), s.getNote(),
                s.getAutore().getNome(), s.getAutore().getId(),
                TempoSupport.inMillisecondi(s.getModificatoIl()));
    }

    /**
     * La forma pubblica, per chi chiama senza un token valido: referente e autore sono vuoti e `autoreId` è null, il resto
     * (compreso il roster) è quello di sempre. L'autore non si legge: serve solo alla forma completa.
     */
    public static SquadraDTO pubblica(AnagrafeSquadra s) {
        return new SquadraDTO(s.getId(), s.getNome(), s.getCitta(), s.getAnno(), s.getRank(), RISERVATO,
                idDelRoster(s), s.getLogo(), s.getWebsite(), s.getInstagram(), s.getNote(),
                RISERVATO, null, TempoSupport.inMillisecondi(s.getModificatoIl()));
    }

    /** Gli id dei giocatori del roster, nell'ordine salvato: uguale nelle due forme, che non possono divergere */
    private static List<UUID> idDelRoster(AnagrafeSquadra s) {
        return s.getRoster().stream().map(AnagrafeGiocatore::getId).toList();
    }
}
