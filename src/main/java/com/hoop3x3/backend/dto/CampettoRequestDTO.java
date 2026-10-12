package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Campi compilabili di un campetto (id, tipo, autore e timestamp li mette il server: un `tipo` nel corpo si ignora). I
 * numeri e i booleani sono wrapper e non primitivi: un campo che manca deve dare il 400 con il suo nome (@NotNull), non il
 * «corpo non valido» di Jackson, e un booleano assente vale false. `versione` è quella che il client ha letto
 * (CampettoDTO.versione), facoltativa: se c'è e non è più quella del database la PUT risponde 409
 * (CampettoService.controllaVersione); se manca non si controlla niente. La POST la ignora.
 */
public record CampettoRequestDTO(
        @NotBlank @Size(max = 120) String nome,
        @Size(max = 160) String indirizzo,
        @Size(max = 120) String citta,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
        @NotBlank @Pattern(regexp = "Asfalto|Cemento|Sintetico|Altro",
                message = "deve essere Asfalto, Cemento, Sintetico o Altro") String superficie,
        @NotNull @Min(1) @Max(8) Integer canestri,
        Boolean illuminato,
        Boolean coperto,
        Boolean gratuito,
        Boolean retine,
        Boolean linee,
        Boolean fontanella,
        @NotBlank @Pattern(regexp = "buono|discreto|da sistemare",
                message = "deve essere buono, discreto o da sistemare") String stato,
        @Size(max = 2000) String note, // colonna TEXT: il tetto è dell'API, perché una nota non pesi megabyte
        Long versione
) {}
