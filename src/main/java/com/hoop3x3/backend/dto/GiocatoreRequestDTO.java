package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Campi compilabili di un giocatore dell'anagrafe (id, autore e timestamp li mette il server) */
public record GiocatoreRequestDTO(
        @NotBlank @Size(max = 80) String nome,
        @NotBlank @Size(max = 80) String cognome,
        @Size(max = 80) String soprannome,
        @Size(max = 10) String nascita,
        @Size(max = 120) String citta,
        @Size(max = 80) String nazionalita,
        @Size(max = 10) String altezza,
        @Size(max = 10) String peso,
        @Size(max = 40) String ruolo,
        @Size(max = 5) String numero,
        @Size(max = 120) String squadra,
        @Size(max = 40) String esperienza,
        @Size(max = 2000) String note // colonna TEXT: il tetto è dell'API, perché una nota non pesi megabyte
) {}
