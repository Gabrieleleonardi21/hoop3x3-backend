package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.UtenteDTO;
import com.hoop3x3.backend.services.UtenteService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/utenti")
public class UtenteController {

    private final UtenteService utenteService;

    public UtenteController(UtenteService utenteService) {
        this.utenteService = utenteService;
    }

    /** Elenco utenti: solo ADMIN */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<UtenteDTO> findAll() {
        return utenteService.findAll().stream().map(UtenteDTO::from).toList();
    }
}
