package com.hoop3x3.backend.runners;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.CampettoDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.CampettoRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.CampettoService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static com.hoop3x3.backend.runners.ImportCampettiTest.EXPORT_FINTO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'import dei campetti di Pick-Roll con il database vero: le righe entrano con la fonte e si trovano con le ricerche
 * pubbliche, un import ripetuto non crea doppioni (indice unico della V8 su fonte e fonte_id) e aggiorna solo le righe
 * cambiate, un file da più blocchi entra tutto, un errore di schema non lascia niente.
 */
@TestDiIntegrazione
class ImportCampettiIT {

    @Autowired CampettoRepository campetti;
    @Autowired UtenteRepository utenti;
    @Autowired Validator validator;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transazioni;
    @Autowired CampettoService campettoService;
    @Autowired JdbcTemplate jdbc;

    @TempDir Path cartella;

    private Utente admin;
    private ImportCampetti comando;

    @BeforeEach
    void adminEComando() {
        admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));
        comando = new ImportCampetti(campetti, utenti, validator, mapper, transazioni,
                new SeedProperties(false, new SeedProperties.Admin(admin.getEmail(), "")));
    }

    @Test
    void exportFinto_leSeiRigheValideEntranoConLaFonteESiTrovanoConLeRicerchePubbliche() {
        ImportCampetti.Esito esito = comando.importa(EXPORT_FINTO);

        assertThat(esito.inseriti()).isEqualTo(6);
        assertThat(esito.aggiornati()).isZero();
        assertThat(esito.scartati()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from campetti where fonte = 'pick-roll'", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForList("select tipo from campetti", String.class)).containsOnly("campetto");

        List<CampettoDTO> aRoma = campettoService.cercaPerRaggio(41.9028, 12.4964, 20);
        assertThat(aRoma).hasSize(5).allSatisfy(c -> {
            assertThat(c.autore()).isEqualTo("Admin");
            assertThat(c.autoreId()).isEqualTo(admin.getId());
        });
        assertThat(campettoService.cercaPerTesto("milano", null, null)).extracting(CampettoDTO::nome)
                .containsExactly("Campo Parco Sempione");
    }

    // Il secondo import dello stesso file non cambia niente; con una riga cambiata nell'export, quella riga si aggiorna (e la
    // sua versione sale, come per una PUT), le altre restano
    @Test
    void importRipetuto_nessunDoppione_eLaRigaCambiataSiAggiorna() throws Exception {
        comando.importa(EXPORT_FINTO);

        ImportCampetti.Esito uguale = comando.importa(EXPORT_FINTO);
        assertThat(uguale.inseriti()).isZero();
        assertThat(uguale.aggiornati()).isZero();
        assertThat(campetti.count()).isEqualTo(6);

        Path cambiato = cartella.resolve("cambiato.json");
        Files.writeString(cambiato, Files.readString(EXPORT_FINTO).replace("Campo Testaccio", "Campo Testaccio rinnovato"));
        ImportCampetti.Esito aggiornato = comando.importa(cambiato);

        assertThat(aggiornato.inseriti()).isZero();
        assertThat(aggiornato.aggiornati()).isEqualTo(1);
        assertThat(campetti.count()).isEqualTo(6);
        Map<String, Object> testaccio = jdbc.queryForMap("select nome, versione from campetti where fonte_id = 'pr-001'");
        assertThat(testaccio.get("nome")).isEqualTo("Campo Testaccio rinnovato");
        assertThat(testaccio.get("versione")).isEqualTo(1L);
    }

    // Più righe di un blocco: più transazioni, tutte le righe entrano. Due righe con lo stesso fonteId nello stesso file sono
    // la stessa riga: la seconda aggiorna la prima, senza violare l'indice unico
    @Test
    void fileDaPiuBlocchi_entraTutto_eUnFonteIdRipetutoNelFileNonCreaDoppioni() throws Exception {
        int quante = ImportCampetti.BLOCCO * 2 + 10;
        String righe = IntStream.range(0, quante)
                .mapToObj(i -> riga("pr-" + i, "Campo " + i))
                .collect(java.util.stream.Collectors.joining(","));
        Path file = cartella.resolve("grande.json");
        Files.writeString(file, "[" + righe + "," + riga("pr-0", "Campo 0 ripetuto") + "]");

        ImportCampetti.Esito esito = comando.importa(file);

        assertThat(esito.inseriti()).isEqualTo(quante);
        assertThat(esito.aggiornati()).isEqualTo(1);
        assertThat(campetti.count()).isEqualTo(quante);
        assertThat(jdbc.queryForObject("select nome from campetti where fonte_id = 'pr-0'", String.class))
                .isEqualTo("Campo 0 ripetuto");
    }

    @Test
    void erroreDiSchema_nonScriveNiente() throws Exception {
        Path file = cartella.resolve("rotto.json");
        Files.writeString(file, "[" + riga("pr-1", "Valido") + ", {\"fonteId\": \"pr-2\", \"nome\": \"Rotto\", \"lat\": \"nord\"}]");

        assertThatThrownBy(() -> comando.importa(file)).hasMessageContaining("riga 2");

        assertThat(campetti.count()).isZero();
    }

    private static String riga(String fonteId, String nome) {
        return "{\"fonteId\": \"" + fonteId + "\", \"nome\": \"" + nome + "\", \"citta\": \"Roma\", \"lat\": 41.9, \"lng\": 12.5, "
                + "\"tipo\": \"campetto\"}";
    }
}
