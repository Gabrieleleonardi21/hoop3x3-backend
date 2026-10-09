package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.CoachChatRequestDTO;
import com.hoop3x3.backend.services.CoachAiService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/** Coach AI: proxy autenticato verso Groq (la chiave non lascia mai il server). */
@RestController
@RequestMapping("/api/coach")
public class CoachController {

    private final CoachAiService coachAiService;

    public CoachController(CoachAiService coachAiService) {
        this.coachAiService = coachAiService;
    }

    /**
     * Dice se il server ha una chiave Groq configurata. Il frontend oggi non lo chiama: mostra sempre il Coach e scopre
     * che è spento dal 503 della chat. Serve a chi controlla la configurazione di un ambiente (curl, monitoraggio)
     */
    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("available", coachAiService.isConfigurato());
    }

    // Il limite per utente lo applica LimiteRichiesteFilter, prima di arrivare qui
    @PostMapping("/chat")
    public JsonNode chat(@RequestBody @Validated CoachChatRequestDTO dto) {
        return coachAiService.chat(dto);
    }
}
