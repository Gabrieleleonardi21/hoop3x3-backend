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

/**
 * Anagrafe circuito. Le GET sono pubbliche (vedi SecurityConfig) ma i dati personali sono riservati a chi ha un account:
 * senza un utente autenticato rispondono con la forma pubblica (stesse chiavi JSON, campi riservati vuoti). Un token
 * scaduto o non valido non è «nessun token»: il JwtFilter risponde già 401. Le scritture richiedono il login.
 */
@RestController
@RequestMapping("/api/anagrafe")
public class AnagrafeController {

    private final AnagrafeService anagrafeService;

    public AnagrafeController(AnagrafeService anagrafeService) {
        this.anagrafeService = anagrafeService;
    }

    /* ── Giocatori ── */

    @GetMapping("/giocatori")
    public List<GiocatoreDTO> giocatori(@AuthenticationPrincipal Utente utente) {
        // Senza utente (ospite) la forma pubblica; con un account qualsiasi, non solo l'autore, quella completa
        if (utente == null) return anagrafeService.tuttiGiocatoriPubblici();
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
    public List<SquadraDTO> squadre(@AuthenticationPrincipal Utente utente) {
        // Come per i giocatori: ospite → forma pubblica, utente autenticato → forma completa
        if (utente == null) return anagrafeService.tutteSquadrePubbliche();
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
