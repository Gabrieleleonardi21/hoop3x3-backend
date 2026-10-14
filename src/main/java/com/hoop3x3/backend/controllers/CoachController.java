package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.BozzaFoglioDTO;
import com.hoop3x3.backend.dto.CoachChatRequestDTO;
import com.hoop3x3.backend.dto.FoglioRequestDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.services.CoachAiService;
import com.hoop3x3.backend.services.FoglioService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/** Coach AI: proxy autenticato verso Groq (la chiave non lascia mai il server) e lettura delle foto dei fogli di tappa. */
@RestController
@RequestMapping("/api/coach")
public class CoachController {

    private final CoachAiService coachAiService;
    private final FoglioService foglioService;

    public CoachController(CoachAiService coachAiService, FoglioService foglioService) {
        this.coachAiService = coachAiService;
        this.foglioService = foglioService;
    }

    /**
     * Dice se il server ha una chiave Groq configurata (available) e se la lettura dei fogli è accesa (foglio). Il frontend
     * oggi non lo chiama: mostra sempre il Coach e scopre che è spento dal 503. Serve a chi controlla la configurazione di un
     * ambiente (curl, monitoraggio, la verifica dopo il deploy)
     */
    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("available", coachAiService.isConfigurato(), "foglio", foglioService.isConfigurato());
    }

    // Il limite per utente lo applica LimiteRichiesteFilter, prima di arrivare qui
    @PostMapping("/chat")
    public JsonNode chat(@RequestBody @Validated CoachChatRequestDTO dto) {
        return coachAiService.chat(dto);
    }

    /** La foto di un foglio (data URL) diventa una bozza da importare: il server non salva niente, né la foto né la bozza */
    @PostMapping("/foglio")
    public BozzaFoglioDTO foglio(@AuthenticationPrincipal Utente utente, @RequestBody @Validated FoglioRequestDTO dto) {
        return foglioService.leggi(utente, dto.immagine());
    }
}
