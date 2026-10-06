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

    /** Il frontend lo usa per mostrare il Coach solo se il server ha una chiave configurata */
    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("available", coachAiService.isConfigurato());
    }

    // Il limite per utente (20 richieste al minuto, 300 al giorno) lo applica LimiteRichiesteFilter prima di arrivare qui
    @PostMapping("/chat")
    public JsonNode chat(@RequestBody @Validated CoachChatRequestDTO dto) {
        return coachAiService.chat(dto);
    }
}
