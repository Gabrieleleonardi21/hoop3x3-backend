package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.PubTappaDTO;
import com.hoop3x3.backend.dto.PubblicaTappaDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.services.ArchivioService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Archivio circuito: GET pubbliche, pubblicazione e rimozione con login. */
@RestController
@RequestMapping("/api/archivio")
public class ArchivioController {

    private final ArchivioService archivioService;

    public ArchivioController(ArchivioService archivioService) {
        this.archivioService = archivioService;
    }

    @GetMapping
    public List<PubTappaDTO> tutte() {
        return archivioService.tutte();
    }

    @GetMapping("/{tappaId}")
    public PubTappaDTO una(@PathVariable UUID tappaId) {
        return archivioService.una(tappaId);
    }

    /** Pubblica o ripubblica (upsert): la chiave è l'id della tappa nel corpo */
    @PutMapping
    public PubTappaDTO pubblica(@AuthenticationPrincipal Utente utente, @RequestBody @Validated PubblicaTappaDTO dto) {
        return archivioService.pubblica(utente, dto);
    }

    @DeleteMapping("/{tappaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rimuovi(@AuthenticationPrincipal Utente utente, @PathVariable UUID tappaId) {
        archivioService.rimuovi(utente, tappaId);
    }
}
