package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Campi compilabili di una squadra dell'anagrafe; `roster` = id dei giocatori in ordine (al massimo 12, nessuno nullo).
 * `versione` è quella che il client ha letto (SquadraDTO.versione), facoltativa: se c'è e non è più quella del database la
 * PUT risponde 409 (AnagrafeService.controllaVersione); se manca non si controlla niente, come per i client di prima.
 */
public record SquadraRequestDTO(
        @NotBlank @Size(max = 120) String nome,
        @Size(max = 120) String citta,
        @Size(max = 4) String anno,
        @Size(max = 10) String rank,
        @Size(max = 120) String referente,
        @Size(max = 500) String logo,
        @Size(max = 500) String website,
        @Size(max = 500) String instagram,
        @Size(max = 2000) String note, // colonna TEXT: il tetto è dell'API, perché una nota non pesi megabyte
        @Size(max = 12) List<@NotNull UUID> roster,
        Long versione
) {}
