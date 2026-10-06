package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Tappa nello stesso formato del tipo `Tappa` del frontend, usata sia in ingresso
 * (POST/PUT) che in uscita. I blocchi di gioco viaggiano come JSON grezzo (JsonNode):
 * il server li valida come struttura (array/oggetto) e li salva in JSONB.
 * nome, luogo e data hanno i limiti delle colonne di `tappe` (migrazioni in db/migration): oltre, il database rifiuterebbe la riga.
 * `versione` è il numero di volte che la tappa è stata cambiata (0 se nuova): la decide il server e la restituisce in ogni
 * risposta, e il client la rimanda con la PUT per dire quale tappa ha letto (400 se manca, 409 se non è più quella del
 * database: LegaService.aggiornaTappa). La POST e l'import di una lega la ignorano. Nessuna annotazione la pretende, perché
 * la stessa classe è il corpo della POST e rilegge anche le tappe pubblicate in archivio prima che esistesse.
 */
public record TappaDTO(
        @NotNull UUID id,
        @NotBlank @Size(max = 120) String nome,
        @Size(max = 160) String luogo,
        // vuota oppure ISO aaaa-mm-gg, come il valore dell'input date del frontend (colonna da 10 caratteri)
        @Pattern(regexp = "(\\d{4}-\\d{2}-\\d{2})?", message = "deve essere vuota oppure nel formato aaaa-mm-gg") String data,
        @Min(1) @Max(32) int nGironi,
        @NotNull @Valid RegoleDTO regole,
        @NotNull JsonNode squadre,
        JsonNode gironi,
        @NotNull JsonNode partite,
        JsonNode video,
        Boolean conclusa,
        JsonNode bracket,
        Long versione
) {}
