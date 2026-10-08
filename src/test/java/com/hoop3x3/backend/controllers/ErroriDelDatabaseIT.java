package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.ErrorsDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JwtTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le scritture che PostgreSQL rifiuta, con il database vero: Spring le traduce tutte in DataIntegrityViolationException, ma
 * un dato che il database non accetta (SQLState di classe 22) è un 400 e solo un vincolo violato (classe 23) è un 409.
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class ErroriDelDatabaseIT {

    @Autowired MockMvc mvc;
    @Autowired JwtTools jwt;
    @Autowired UtenteRepository utenti;
    @Autowired AnagrafeGiocatoreRepository giocatori;
    @Autowired ExceptionsHandler gestore;

    // Un carattere NUL passa la validazione del DTO ma PostgreSQL non lo salva in un testo (SQLState 22021): era un 409, che il
    // client legge come «modificata da un altro dispositivo», e scartava la modifica
    @Test
    void unNomeConUnCarattereNul_risponde400ENonSalvaNiente() throws Exception {
        Utente mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));

        mvc.perform(post("/api/anagrafe/giocatori").header(AUTHORIZATION, "Bearer " + jwt.generateToken(mario))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Ma\\u0000rio\",\"cognome\":\"Rossi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(startsWith("Dati non validi")));

        assertThat(giocatori.count()).isZero();
    }

    // Un doppione vero (l'email unica degli utenti, SQLState 23505), con l'eccezione che arriva davvero dal database: resta un 409
    @Test
    void unVincoloViolato_resta409() {
        utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        Throwable errore = catchThrowable(() -> utenti.save(new Utente("mario@test.it", "hash", "Mario bis", Ruolo.USER)));
        assertThat(errore).isInstanceOf(DataIntegrityViolationException.class);

        ResponseEntity<ErrorsDTO> esito = gestore.handleDataIntegrity((DataIntegrityViolationException) errore);

        assertThat(esito.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
