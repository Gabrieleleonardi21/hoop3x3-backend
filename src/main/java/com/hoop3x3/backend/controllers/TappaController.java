package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.services.LegaService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Modifica ed eliminazione di una singola tappa (la proprietà passa dalla lega). */
@RestController
@RequestMapping("/api/tappe")
public class TappaController {

    private final LegaService legaService;

    public TappaController(LegaService legaService) {
        this.legaService = legaService;
    }

    /** La tappa porta la versione che il client ha letto: 400 se manca, 409 se non è più quella del database */
    @PutMapping("/{id}")
    public TappaDTO aggiorna(@AuthenticationPrincipal Utente utente, @PathVariable UUID id,
                             @RequestBody @Validated TappaDTO dto) {
        return legaService.aggiornaTappa(utente, id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void elimina(@AuthenticationPrincipal Utente utente, @PathVariable UUID id) {
        legaService.eliminaTappa(utente, id);
    }
}
