package com.hoop3x3.backend.dto;

import java.util.List;

/**
 * La bozza letta dalla foto di un foglio, già ripulita da FoglioService: il frontend la trasforma nel JSON di una lega e la
 * importa come un file. È una risposta, non entra mai da fuori. Nome, luogo, data e gironi possono essere null; gli elenchi
 * non sono mai null.
 *
 * @param data   aaaa-mm-gg, o null se il modello non l'ha letta in quel formato (con un dubbio «Data non letta»)
 * @param gironi i nomi delle squadre di ogni girone, se il foglio li ha
 * @param dubbi  dove e che cosa non si legge con certezza
 */
public record BozzaFoglioDTO(String nome, String luogo, String data, List<SquadraLetta> squadre, List<List<String>> gironi,
                             List<PartitaLetta> partite, List<String> dubbi) {

    /** Una squadra con i giocatori scritti sul foglio */
    public record SquadraLetta(String nome, List<String> giocatori) {}

    /** Una partita: i nomi delle due squadre e i punti, null dove non si leggono */
    public record PartitaLetta(String a, String b, Integer puntiA, Integer puntiB) {}
}
