package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.TappaDiProva;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Lega;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.TappaRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.services.LegaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Il tetto di tappe per lega (Lega.MAX_TAPPE) con il database vero: valeva solo per l'import, e una tappa alla volta si poteva
 * andare oltre senza limite. La tappa oltre il tetto è un 400 con un messaggio suo, e non un 409: il frontend legge il 409 di
 * una tappa come «modificata da un altro dispositivo». L'import oltre il tetto lo prova ValidazioneWebTest (è il @Size del DTO).
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class LimiteTappeIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTools jwt;
    @Autowired UtenteRepository utenti;
    @Autowired TappaRepository tappe;
    @Autowired LegaService legaService;

    private Utente mario;

    @BeforeEach
    void creaIlProprietario() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
    }

    @Test
    void laTappaOltreIlTetto_risponde400ConIlMessaggioENonLaSalva() throws Exception {
        UUID lega = legaCon(Lega.MAX_TAPPE - 1);

        // La centesima entra...
        aggiungi(lega).andExpect(status().isCreated());
        // ...la centunesima no
        aggiungi(lega).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Limite di 100 tappe per lega raggiunto"))
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(tappe.countByLegaId(lega)).isEqualTo(Lega.MAX_TAPPE);
    }

    // La centesima POST è stata salvata ma la risposta si è persa (Render che si sveglia): il frontend la rimanda uguale, e
    // deve ricevere il 409 «Esiste già», che sa riconciliare, non il 400 del tetto, che non saprebbe come trattare
    @Test
    void rimandareLaCentesimaTappaGiaSalvata_risponde409ENon400() throws Exception {
        UUID lega = legaCon(Lega.MAX_TAPPE - 1);
        TappaDTO centesima = TappaDiProva.tappa().build();
        aggiungi(lega, centesima).andExpect(status().isCreated());

        aggiungi(lega, centesima).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Esiste già una tappa con id " + centesima.id()));
    }

    // Il tetto è per lega, non per utente: una lega piena non toglie tappe alle altre dello stesso proprietario
    @Test
    void unaLegaPienaNonBloccaLeAltreDelloStessoProprietario() throws Exception {
        legaCon(Lega.MAX_TAPPE);
        UUID altra = legaCon(0);

        aggiungi(altra).andExpect(status().isCreated());
    }

    /** Una lega di Mario con quel numero di tappe, importata da file (la via più rapida per arrivare al tetto) */
    private UUID legaCon(int quante) {
        List<TappaDTO> tappe = new ArrayList<>();
        for (int i = 0; i < quante; i++) tappe.add(TappaDiProva.tappa().nome("Tappa " + i).build());
        return legaService.crea(mario, new NuovaLegaDTO("Circuito", tappe)).id();
    }

    /** POST di una tappa nuova di Mario nella lega */
    private ResultActions aggiungi(UUID lega) throws Exception {
        return aggiungi(lega, TappaDiProva.tappa().build());
    }

    private ResultActions aggiungi(UUID lega, TappaDTO tappa) throws Exception {
        return mvc.perform(post("/api/leghe/" + lega + "/tappe").header(AUTHORIZATION, "Bearer " + jwt.generateToken(mario))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(tappa)));
    }
}
