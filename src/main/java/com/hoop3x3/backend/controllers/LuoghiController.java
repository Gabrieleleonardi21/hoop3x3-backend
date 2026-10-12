package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.LuogoDTO;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.services.LuoghiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La ricerca di un luogo per spostare la mappa dei campetti: pubblica, come la GET dei campetti (vedi SecurityConfig). La
 * lunghezza di q si controlla qui, prima di Nominatim.
 */
@RestController
@RequestMapping("/api/luoghi")
public class LuoghiController {

    static final int LUNGHEZZA_MINIMA = 2;
    static final int LUNGHEZZA_MASSIMA = 100;

    private final LuoghiService luoghi;

    public LuoghiController(LuoghiService luoghi) {
        this.luoghi = luoghi;
    }

    /** GET /api/luoghi?q=firenze: il primo luogo che Nominatim trova per q, con nome e coordinate */
    @GetMapping
    public LuogoDTO cerca(@RequestParam(required = false) String q) {
        String testo = q == null ? "" : q.trim();
        if (testo.length() < LUNGHEZZA_MINIMA || testo.length() > LUNGHEZZA_MASSIMA) {
            throw new BadRequestException("Indica il luogo da cercare (q, da 2 a 100 caratteri)");
        }
        return luoghi.cerca(testo);
    }
}
