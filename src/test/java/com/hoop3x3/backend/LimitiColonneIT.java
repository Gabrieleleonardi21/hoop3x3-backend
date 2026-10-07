package com.hoop3x3.backend;

import com.hoop3x3.backend.dto.GiocatoreRequestDTO;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.RegisterRequestDTO;
import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.LegaService;
import com.hoop3x3.backend.services.UtenteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I tetti di lunghezza dei DTO con le colonne vere (VARCHAR) del database: un valore lungo quanto il massimo che l'API accetta
 * deve entrare nella colonna. Se un @Size superasse la colonna, il 400 non scatterebbe e il database rifiuterebbe la riga con
 * un 409 senza spiegazioni (ValidazioneWebTest prova l'altro verso: oltre il tetto risponde 400). Ogni test salva un valore al
 * massimo in ogni campo con un tetto.
 */
@TestDiIntegrazione
class LimitiColonneIT {

    @Autowired UtenteRepository utenti;
    @Autowired UtenteService utenteService;
    @Autowired AnagrafeService anagrafeService;
    @Autowired LegaService legaService;
    @Autowired ArchivioService archivioService;
    @Autowired JdbcTemplate jdbc;

    private Utente mario;

    @BeforeEach
    void creaIlProprietario() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
    }

    @Test
    void unGiocatoreConOgniCampoAlTetto_entraNelDatabase() {
        GiocatoreRequestDTO alTetto = new GiocatoreRequestDTO(x(80), x(80), x(80), x(10), x(120), x(80), x(10), x(10), x(40),
                x(5), x(120), x(40), x(2000));

        anagrafeService.creaGiocatore(mario, alTetto);

        assertThat(anagrafeService.tuttiGiocatori()).singleElement().satisfies(g -> {
            assertThat(g.nome()).hasSize(80);
            assertThat(g.numero()).hasSize(5);
            assertThat(g.note()).hasSize(2000);
        });
    }

    @Test
    void unaSquadraConOgniCampoAlTetto_entraNelDatabase() {
        SquadraRequestDTO alTetto = new SquadraRequestDTO(x(120), x(120), x(4), x(10), x(120), x(500), x(500), x(500), x(2000),
                List.of());

        anagrafeService.creaSquadra(mario, alTetto);

        assertThat(anagrafeService.tutteSquadre()).singleElement().satisfies(s -> {
            assertThat(s.nome()).hasSize(120);
            assertThat(s.instagram()).hasSize(500);
            assertThat(s.note()).hasSize(2000);
        });
    }

    // Il nome della lega finisce anche in archivio_tappe.lega_nome, una colonna a parte: ha lo stesso tetto
    @Test
    void unaLegaEUnaTappaAlTetto_entranoEPubblicarlaCopiaIlNomeInArchivio() {
        TappaDTO tappa = TappaDiProva.tappa().nome(x(120)).luogo(x(160)).data("2026-06-14").conclusa(true).build();
        legaService.crea(mario, new NuovaLegaDTO(x(120), List.of(tappa)));

        archivioService.pubblica(mario, tappa.id());

        assertThat(jdbc.queryForObject("select length(lega_nome) from archivio_tappe", Integer.class)).isEqualTo(120);
        assertThat(jdbc.queryForObject("select length(luogo) from tappe", Integer.class)).isEqualTo(160);
    }

    // L'email più lunga e il nome più lungo che la registrazione accetta, con la password al massimo di BCrypt (72 byte)
    @Test
    void unaRegistrazioneAlTetto_entraNelDatabase() {
        String email = "a".repeat(247) + "@test.it"; // 255 caratteri
        RegisterRequestDTO alTetto = new RegisterRequestDTO(x(80), email, "x".repeat(72));

        utenteService.register(alTetto);

        assertThat(utenti.findByEmail(email)).get().satisfies(u -> assertThat(u.getNome()).hasSize(80));
    }

    /** Un testo di `n` caratteri */
    private static String x(int n) {
        return "x".repeat(n);
    }
}
