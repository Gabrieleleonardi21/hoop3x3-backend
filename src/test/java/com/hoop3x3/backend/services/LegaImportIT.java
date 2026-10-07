package com.hoop3x3.backend.services;

import com.hoop3x3.backend.TappaDiProva;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.LegaDettaglioDTO;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.exceptions.ConflictException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'import di una lega da file (LegaService.crea con le tappe) e l'aggiunta di una tappa con il database vero: l'id di una tappa
 * lo sceglie il client ed è la chiave primaria di tutte le tappe, quindi un id già usato è un 409, e un import che fallisce a
 * metà non lascia né la lega né le tappe che aveva già preparato. I numeri delle posizioni li prova PosizioneTappeIT.
 */
@TestDiIntegrazione
class LegaImportIT {

    @Autowired LegaService legaService;
    @Autowired UtenteRepository utenti;
    @Autowired JdbcTemplate jdbc;

    private Utente mario;
    private Utente luigi;

    @BeforeEach
    void creaGliUtenti() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));
    }

    /* ── Un import riesce tutto o niente ── */

    // L'id è unico in tutta la tabella, non solo nella lega: Luigi non può importare una tappa che ha l'id di una di Mario
    @Test
    void unImportConUnIdGiaUsatoInUnaLegaDiUnAltro_risponde409ENonCreaNiente() {
        TappaDTO diMario = TappaDiProva.tappa().nome("Di Mario").build();
        legaService.crea(mario, new NuovaLegaDTO("Lega di Mario", List.of(diMario)));
        // Il file di Luigi ha una tappa nuova, preparata per prima, e una con l'id di quella di Mario
        TappaDTO nuova = TappaDiProva.tappa().nome("Nuova").build();
        NuovaLegaDTO file = new NuovaLegaDTO("Lega di Luigi", List.of(nuova, TappaDiProva.da(diMario).nome("Copia").build()));

        assertThatThrownBy(() -> legaService.crea(luigi, file))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Esiste già una tappa con id " + diMario.id());

        assertThat(nomiDelleLeghe()).containsExactly("Lega di Mario");
        assertThat(nomiDelleTappe()).containsExactly("Di Mario");
    }

    // Un blocco che non ha la forma attesa (qui le squadre sono un oggetto) è un 400, e la tappa valida che lo precede non resta
    @Test
    void unImportConUnBloccoNonValido_risponde400ENonCreaNiente() {
        TappaDTO valida = TappaDiProva.tappa().nome("Valida").build();
        TappaDTO squadreNonArray = TappaDiProva.tappa().nome("Non valida").squadre("{\"non\":\"un array\"}").build();

        assertThatThrownBy(() -> legaService.crea(mario, new NuovaLegaDTO("Importata", List.of(valida, squadreNonArray))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("squadre");

        assertThat(nomiDelleLeghe()).isEmpty();
        assertThat(nomiDelleTappe()).isEmpty();
    }

    /* ── Che cosa entra nel database ── */

    // I nomi e il luogo si salvano senza gli spazi ai lati
    @Test
    void ilNomeDellaLegaEDellaTappaEIlLuogoSiRipuliscono() {
        TappaDTO tappa = TappaDiProva.tappa().nome("  Tappa di Roma  ").luogo("  Roma ").build();

        UUID lega = legaService.crea(mario, new NuovaLegaDTO("  Circuito 2026 ", List.of(tappa))).id();

        LegaDettaglioDTO letta = legaService.dettaglio(mario, lega);
        assertThat(letta.nome()).isEqualTo("Circuito 2026");
        assertThat(letta.tappe()).singleElement().satisfies(t -> {
            assertThat(t.nome()).isEqualTo("Tappa di Roma");
            assertThat(t.luogo()).isEqualTo("Roma");
        });
    }

    // Un file senza tappe (la lista c'è ma è vuota) dà una lega vuota, come l'assenza della lista
    @Test
    void unImportConLaListaVuota_creaUnaLegaSenzaTappe() {
        var creata = legaService.crea(mario, new NuovaLegaDTO("Vuota", List.of()));

        assertThat(creata.nTappe()).isZero();
        assertThat(legaService.dettaglio(mario, creata.id()).tappe()).isEmpty();
    }

    /* ── Aggiungere una tappa con un id già usato ── */

    @Test
    void unaTappaAggiuntaConUnIdGiaUsato_risponde409ENonCambiaNiente() {
        TappaDTO esistente = TappaDiProva.tappa().nome("Esistente").build();
        UUID lega = legaService.crea(mario, new NuovaLegaDTO("Circuito", List.of(esistente))).id();
        TappaDTO diLuigi = TappaDiProva.tappa().nome("Di Luigi").build();
        legaService.crea(luigi, new NuovaLegaDTO("Lega di Luigi", List.of(diLuigi)));

        // Lo stesso id nella stessa lega, e l'id di una tappa di un'altra lega
        assertThatThrownBy(() -> legaService.aggiungiTappa(mario, lega, TappaDiProva.da(esistente).nome("Doppia").build()))
                .isInstanceOf(ConflictException.class).hasMessage("Esiste già una tappa con id " + esistente.id());
        assertThatThrownBy(() -> legaService.aggiungiTappa(mario, lega, TappaDiProva.da(diLuigi).nome("Rubata").build()))
                .isInstanceOf(ConflictException.class).hasMessage("Esiste già una tappa con id " + diLuigi.id());

        assertThat(nomiDelleTappe()).containsExactlyInAnyOrder("Esistente", "Di Luigi");
    }

    /* ── Letture ── */

    private List<String> nomiDelleLeghe() {
        return jdbc.queryForList("select nome from leghe order by nome", String.class);
    }

    private List<String> nomiDelleTappe() {
        return jdbc.queryForList("select nome from tappe order by nome", String.class);
    }
}
