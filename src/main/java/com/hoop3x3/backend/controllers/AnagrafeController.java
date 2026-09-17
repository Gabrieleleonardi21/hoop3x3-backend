package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.GiocatoreDTO;
import com.hoop3x3.backend.dto.GiocatoreRequestDTO;
import com.hoop3x3.backend.dto.SquadraDTO;
import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.services.AnagrafeService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Anagrafe circuito. Le GET sono pubbliche (vedi SecurityConfig), le scritture richiedono il login. */
@RestController
@RequestMapping("/api/anagrafe")
public class AnagrafeController {

    private final AnagrafeService anagrafeService;

    public AnagrafeController(AnagrafeService anagrafeService) {
        this.anagrafeService = anagrafeService;
    }

    /* ── Giocatori ── */

    @GetMapping("/giocatori")
    public List<GiocatoreDTO> giocatori() {
        return anagrafeService.tuttiGiocatori();
    }

    @PostMapping("/giocatori")
    @ResponseStatus(HttpStatus.CREATED)
    public GiocatoreDTO creaGiocatore(@AuthenticationPrincipal Utente utente, @RequestBody @Validated GiocatoreRequestDTO dto) {
        return anagrafeService.creaGiocatore(utente, dto);
    }

    @PutMapping("/giocatori/{id}")
    public GiocatoreDTO aggiornaGiocatore(@AuthenticationPrincipal Utente utente, @PathVariable UUID id,
                                          @RequestBody @Validated GiocatoreRequestDTO dto) {
        return anagrafeService.aggiornaGiocatore(utente, id, dto);
    }

    @DeleteMapping("/giocatori/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminaGiocatore(@AuthenticationPrincipal Utente utente, @PathVariable UUID id) {
        anagrafeService.eliminaGiocatore(utente, id);
    }

    /* ── Squadre ── */

    @GetMapping("/squadre")
    public List<SquadraDTO> squadre() {
        return anagrafeService.tutteSquadre();
    }

    @PostMapping("/squadre")
    @ResponseStatus(HttpStatus.CREATED)
    public SquadraDTO creaSquadra(@AuthenticationPrincipal Utente utente, @RequestBody @Validated SquadraRequestDTO dto) {
        return anagrafeService.creaSquadra(utente, dto);
    }

    @PutMapping("/squadre/{id}")
    public SquadraDTO aggiornaSquadra(@AuthenticationPrincipal Utente utente, @PathVariable UUID id,
                                      @RequestBody @Validated SquadraRequestDTO dto) {
        return anagrafeService.aggiornaSquadra(utente, id, dto);
    }

    @DeleteMapping("/squadre/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminaSquadra(@AuthenticationPrincipal Utente utente, @PathVariable UUID id) {
        anagrafeService.eliminaSquadra(utente, id);
    }
}
