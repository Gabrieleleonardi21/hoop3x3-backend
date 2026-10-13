package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.AuthResponseDTO;
import com.hoop3x3.backend.dto.CambioNomeDTO;
import com.hoop3x3.backend.dto.CambioPasswordDTO;
import com.hoop3x3.backend.dto.UtenteDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.services.ProfiloService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Il profilo dell'account, accanto ad AuthController sotto /api/auth: chi è dentro cambia il suo nome e la sua password. Il
 * JWT identifica l'utente per id (JwtTools), quindi cambiare il nome non invalida il token in corso. Come funziona: vedi
 * ProfiloService e il README, «Profilo dell'account». I tentativi con la password li limita LimiteRichiesteFilter, per utente.
 */
@RestController
@RequestMapping("/api/auth")
public class ProfiloController {

    private final ProfiloService service;
    private final Sessioni sessioni;

    public ProfiloController(ProfiloService service, Sessioni sessioni) {
        this.service = service;
        this.sessioni = sessioni;
    }

    /** 200 con l'utente aggiornato: il client sostituisce la copia che tiene nel browser */
    @PatchMapping("/me")
    public UtenteDTO cambiaNome(@AuthenticationPrincipal Utente utente, @RequestBody @Validated CambioNomeDTO dto) {
        return UtenteDTO.from(service.cambiaNome(utente, dto.name()));
    }

    /**
     * 200 {token, user} con un nuovo cookie di refresh: il servizio chiude tutte le sessioni dell'account, poi se ne riapre una
     * per questo browser, che resta dentro. Gli altri dispositivi escono alla scadenza del loro JWT.
     */
    @PostMapping("/me/password")
    public AuthResponseDTO cambiaPassword(@AuthenticationPrincipal Utente utente, @RequestBody @Validated CambioPasswordDTO dto,
                                          HttpServletResponse response) {
        service.cambiaPassword(utente, dto.passwordAttuale(), dto.nuovaPassword());
        return sessioni.apri(utente, response);
    }
}
