package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.MessaggioDTO;
import com.hoop3x3.backend.dto.PasswordDimenticataDTO;
import com.hoop3x3.backend.dto.ReimpostaPasswordDTO;
import com.hoop3x3.backend.services.RecuperoPasswordService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recupero password, pubblico (SecurityConfig) e senza Bearer (JwtFilter): chi l'ha dimenticata non ha una sessione. La
 * richiesta del link è limitata per indirizzo da LimiteRichiesteFilter, e per email dal servizio. Come funziona: vedi
 * RecuperoPasswordService e il README, «Recupero password».
 */
@RestController
@RequestMapping("/api/auth/password")
public class RecuperoPasswordController {

    private final RecuperoPasswordService service;

    public RecuperoPasswordController(RecuperoPasswordService service) {
        this.service = service;
    }

    /** Sempre 200 con lo stesso messaggio, che l'email sia registrata o no */
    @PostMapping("/dimenticata")
    public MessaggioDTO dimenticata(@RequestBody @Validated PasswordDimenticataDTO dto) {
        service.richiedi(dto.email());
        return new MessaggioDTO(RecuperoPasswordService.RISPOSTA_RICHIESTA);
    }

    /** 200 se la password è cambiata; 400 con un messaggio unico se il link non vale più */
    @PostMapping("/reimposta")
    public MessaggioDTO reimposta(@RequestBody @Validated ReimpostaPasswordDTO dto) {
        service.reimposta(dto.token(), dto.nuovaPassword());
        return new MessaggioDTO(RecuperoPasswordService.RISPOSTA_REIMPOSTATA);
    }
}
