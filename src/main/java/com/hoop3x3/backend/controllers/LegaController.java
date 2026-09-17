package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.*;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.services.LegaService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Leghe dell'utente autenticato (indice, dettaglio con tappe, creazione/import, rinomina, eliminazione). */
@RestController
@RequestMapping("/api/leghe")
public class LegaController {

    private final LegaService legaService;

    public LegaController(LegaService legaService) {
        this.legaService = legaService;
    }

    @GetMapping
    public List<LegaMetaDTO> indice(@AuthenticationPrincipal Utente utente) {
        return legaService.indice(utente);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LegaMetaDTO crea(@AuthenticationPrincipal Utente utente, @RequestBody @Validated NuovaLegaDTO dto) {
        return legaService.crea(utente, dto);
    }

    @GetMapping("/{id}")
    public LegaDettaglioDTO dettaglio(@AuthenticationPrincipal Utente utente, @PathVariable UUID id) {
        return legaService.dettaglio(utente, id);
    }

    @PatchMapping("/{id}")
    public LegaMetaDTO rinomina(@AuthenticationPrincipal Utente utente, @PathVariable UUID id,
                                @RequestBody @Validated PatchLegaDTO dto) {
        return legaService.rinomina(utente, id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void elimina(@AuthenticationPrincipal Utente utente, @PathVariable UUID id) {
        legaService.elimina(utente, id);
    }

    /** Nuova tappa dentro la lega (l'id lo manda il client) */
    @PostMapping("/{id}/tappe")
    @ResponseStatus(HttpStatus.CREATED)
    public TappaDTO aggiungiTappa(@AuthenticationPrincipal Utente utente, @PathVariable UUID id,
                                  @RequestBody @Validated TappaDTO dto) {
        return legaService.aggiungiTappa(utente, id, dto);
    }
}
