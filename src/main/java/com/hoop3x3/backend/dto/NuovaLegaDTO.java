package com.hoop3x3.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * `tappe` è opzionale: valorizzato solo dall'import di una lega da file JSON. Al massimo 100 tappe, nessun elemento
 * nullo (causerebbe un NullPointerException nel service) e nessun id ripetuto.
 */
public record NuovaLegaDTO(
        @NotBlank @Size(max = 120) String nome,
        @Size(max = 100) List<@NotNull @Valid TappaDTO> tappe
) {

    /**
     * Due tappe con lo stesso id romperebbero il salvataggio (chiave primaria) a import avviato: si rifiutano qui,
     * sul DTO, così rispondono 400 prima di arrivare al database.
     */
    @AssertTrue(message = "due tappe dell'import hanno lo stesso id")
    public boolean isTappeConIdUnici() {
        if (tappe == null) return true;
        Set<UUID> visti = new HashSet<>();
        for (TappaDTO t : tappe) {
            // un elemento nullo o senza id lo segnalano già @NotNull: qui contano solo gli id presenti
            if (t != null && t.id() != null && !visti.add(t.id())) return false;
        }
        return true;
    }
}
