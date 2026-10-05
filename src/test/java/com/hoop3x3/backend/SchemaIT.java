package com.hoop3x3.backend;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il contesto completo parte contro il database di prova: lo schema creato dalle migrazioni di Flyway combacia con le
 * entity (Hibernate gira con ddl-auto=validate) e le tabelle si svuotano prima di ogni test.
 */
@TestDiIntegrazione
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaIT {

    @Autowired UtenteRepository utenti;
    @Autowired JdbcTemplate jdbc;

    // Il database che i test devono usare: quello di TEST_DB_URL, altrimenti quello predefinito
    @Value("${TEST_DB_URL:" + TestDiIntegrazione.URL_PREDEFINITO + "}")
    String urlDiProva;

    @Test
    @Order(1)
    void unUtenteSalvatoSiRilegge() {
        utenti.save(new Utente("schema@test.it", "hash", "Schema", Ruolo.USER));

        assertThat(utenti.findByEmail("schema@test.it")).isPresent();
    }

    @Test
    @Order(2)
    void ogniTestParteDaTabelleVuote() {
        // Il test precedente ha salvato un utente: prima di questo le tabelle sono state svuotate
        assertThat(utenti.count()).isZero();
    }

    @Test
    void usaIlDatabaseDiTestDbUrlAncheConSpringDatasourceUrl() {
        // SPRING_DATASOURCE_URL esportata nel terminale (per esempio per avviare il server) non sposta i test altrove
        String nomeAtteso = urlDiProva.replaceFirst("^.*/([^/?]+).*$", "$1");

        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(nomeAtteso);
    }
}
