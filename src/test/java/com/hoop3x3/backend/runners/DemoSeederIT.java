package com.hoop3x3.backend.runners;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.PubTappaDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.LegaRepository;
import com.hoop3x3.backend.repositories.TappaRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.LegaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il seed demo con il database vero: le 4 tappe del circuito Estathé finiscono in archivio con la forma che il frontend
 * legge dalle API delle tappe, intestate all'admin. Il seeder parte da solo solo all'avvio (SEED_DEMO=true) e i test
 * cominciano con le tabelle vuote: qui lo si accende a mano, dopo aver creato l'admin.
 */
@TestDiIntegrazione
class DemoSeederIT {

    @Autowired DemoSeeder seeder;
    @Autowired UtenteRepository utenti;
    @Autowired LegaRepository leghe;
    @Autowired TappaRepository tappe;
    @Autowired ArchivioService archivioService;
    @Autowired LegaService legaService;
    @Autowired ObjectMapper mapper;

    // Il seeder è un bean condiviso con gli altri test di integrazione: lo si rimette come lo vuole il profilo di prova,
    // spento (seed.demo=false e nessuna email dell'admin, vedi application-test.properties)
    @AfterEach
    void spegniIlSeeder() {
        ReflectionTestUtils.setField(seeder, "abilitato", false);
        ReflectionTestUtils.setField(seeder, "adminEmail", "");
    }

    @Test
    void leTappeDelSeedSonoInArchivioUgualiAQuelleSalvateEIntestateAllAdmin() throws Exception {
        Utente admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));
        ReflectionTestUtils.setField(seeder, "abilitato", true);
        ReflectionTestUtils.setField(seeder, "adminEmail", admin.getEmail());

        seeder.run();

        String nomeLega = leghe.findAll().getFirst().getNome();
        List<PubTappaDTO> pubblicate = archivioService.tutte();
        assertThat(pubblicate).hasSize(4).allSatisfy(pubblicata -> {
            assertThat(pubblicata.autoreId()).isEqualTo(admin.getId());
            assertThat(pubblicata.lega()).isEqualTo(nomeLega);
            // Come nell'archivio dell'app: la tappa salvata nel database, nella forma delle API
            TappaDTO salvata = legaService.toDto(tappe.findById(pubblicata.tappa().id()).orElseThrow());
            assertThat(comeJson(pubblicata.tappa())).isEqualTo(comeJson(salvata));
        });
    }

    /** Confronto sul JSON e non sui record: un blocco assente è null in uno e NullNode nell'altro, ma in JSON è lo stesso */
    private JsonNode comeJson(TappaDTO tappa) {
        return mapper.valueToTree(tappa);
    }
}
