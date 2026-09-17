package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.AuthResponseDTO;
import com.hoop3x3.backend.dto.LoginRequestDTO;
import com.hoop3x3.backend.dto.RegisterRequestDTO;
import com.hoop3x3.backend.dto.UtenteDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.security.JWTtools;
import com.hoop3x3.backend.services.UtenteService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UtenteService utenteService;
    private final JWTtools jwtTools;

    public AuthController(AuthenticationManager authenticationManager, UtenteService utenteService, JWTtools jwtTools) {
        this.authenticationManager = authenticationManager;
        this.utenteService = utenteService;
        this.jwtTools = jwtTools;
    }

    /** Registrazione: crea l'utente e risponde già con il token, così il client non deve rifare il login */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponseDTO register(@RequestBody @Validated RegisterRequestDTO dto) {
        Utente utente = utenteService.register(dto);
        return new AuthResponseDTO(jwtTools.generateToken(utente), UtenteDTO.from(utente));
    }

    @PostMapping("/login")
    public AuthResponseDTO login(@RequestBody @Validated LoginRequestDTO dto) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(UtenteService.normalizza(dto.email()), dto.password()));
        Utente utente = (Utente) auth.getPrincipal();
        return new AuthResponseDTO(jwtTools.generateToken(utente), UtenteDTO.from(utente));
    }

    /** Usato all'avvio dell'app per verificare che il token salvato sia ancora valido */
    @GetMapping("/me")
    public UtenteDTO me(@AuthenticationPrincipal Utente utente) {
        return UtenteDTO.from(utente);
    }
}
