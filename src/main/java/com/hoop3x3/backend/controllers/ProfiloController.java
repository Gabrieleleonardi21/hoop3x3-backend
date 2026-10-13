package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.AuthResponseDTO;
import com.hoop3x3.backend.dto.CambioEmailDTO;
import com.hoop3x3.backend.dto.CambioNomeDTO;
import com.hoop3x3.backend.dto.CambioPasswordDTO;
import com.hoop3x3.backend.dto.ConfermaEmailDTO;
import com.hoop3x3.backend.dto.EliminaAccountDTO;
import com.hoop3x3.backend.dto.MessaggioDTO;
import com.hoop3x3.backend.dto.UtenteDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.services.ProfiloService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Il profilo dell'account, accanto ad AuthController sotto /api/auth: chi è dentro cambia il suo nome, la sua password e la
 * sua email, ed elimina l'account. Il JWT identifica l'utente per id (JwtTools), quindi cambiare nome o email non invalida il token in corso. Come
 * funziona: vedi ProfiloService e il README, «Profilo dell'account». I tentativi con la password li limita
 * LimiteRichiesteFilter, per utente. La conferma dell'email è pubblica (SecurityConfig) e senza Bearer (JwtFilter): il link
 * si apre anche da un browser senza sessione, e vale il token.
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

    /** 200 con il messaggio: il link parte verso il nuovo indirizzo, l'email dell'account cambia solo alla conferma */
    @PostMapping("/me/email")
    public MessaggioDTO richiediCambioEmail(@AuthenticationPrincipal Utente utente, @RequestBody @Validated CambioEmailDTO dto) {
        service.richiediCambioEmail(utente, dto.nuovaEmail(), dto.password());
        return new MessaggioDTO(ProfiloService.RISPOSTA_LINK_INVIATO);
    }

    /**
     * 204 e il cookie di refresh cancellato: la riga del refresh token è già sparita con la cascata del database, il cookie va
     * tolto dal browser. Il JWT lo butta via il client; se lo rimanda, il JwtFilter risponde 401 (l'utente non esiste più).
     */
    @PostMapping("/me/elimina")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void elimina(@AuthenticationPrincipal Utente utente, @RequestBody @Validated EliminaAccountDTO dto,
                        HttpServletResponse response) {
        service.elimina(utente, dto.password());
        sessioni.chiudi(response);
    }

    /** 200 se l'email è cambiata; 400 con un messaggio unico se il link non vale più; 409 se l'indirizzo è stato preso */
    @PostMapping("/email/conferma")
    public MessaggioDTO confermaEmail(@RequestBody @Validated ConfermaEmailDTO dto) {
        service.confermaEmail(dto.token());
        return new MessaggioDTO(ProfiloService.RISPOSTA_EMAIL_CAMBIATA);
    }
}
