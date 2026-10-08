package com.hoop3x3.backend;

import com.hoop3x3.backend.CampiDiTesto.Campo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * I tetti di lunghezza dei DTO di richiesta contro le colonne vere del database. Per ogni campo di testo (la tabella
 * CampiDiTesto) il massimo del suo @Size, letto dal DTO, non deve superare la lunghezza della colonna in cui il valore viene
 * salvato, letta da information_schema: se un @Size venisse alzato oltre la colonna, il 400 non scatterebbe e il database
 * rifiuterebbe la riga con un 409 senza spiegazioni. Nessun valore è scritto a mano: né i tetti né le lunghezze.
 * <p>
 * Il confronto garantisce che il tetto dichiarato dal DTO entri nella colonna. Che il tetto venga applicato, cioè che un carattere
 * oltre dia 400, lo provano due test: ValidazioneWebTest lo prova su un endpoint per ogni DTO della tabella, e
 * AccessoEndpointIT:ogniCorpoDiUnaRichiestaVieneValidato garantisce che ogni endpoint con un corpo lo validi (@Validated o @Valid).
 */
@TestDiIntegrazione
class LimitiColonneIT {

    @Autowired JdbcTemplate jdbc;

    // Un solo test per tutti i campi: se più tetti sono sbagliati si vedono tutti insieme, e il database si svuota una volta sola
    @Test
    void ilTettoDiOgniCampoEntraNelleColonneDelDatabase() {
        assertAll(CampiDiTesto.TUTTI.stream().flatMap(campo -> campo.colonne().stream()
                .map(colonna -> (Executable) () -> assertIlTettoEntraNellaColonna(campo, colonna))));
    }

    private void assertIlTettoEntraNellaColonna(Campo campo, String colonna) {
        Colonna trovata = colonnaDelloSchema(colonna);

        if (trovata.lunghezza() != null) {
            assertThat(campo.tetto()).as("tetto di %s e lunghezza della colonna %s", campo, colonna)
                    .isLessThanOrEqualTo(trovata.lunghezza());
            return;
        }
        // Senza una lunghezza va bene solo una colonna TEXT, che non ha limite: il tetto è dell'API e non c'è niente da confrontare.
        // Un altro tipo (un numero, una data) non ha niente a che fare con un testo di quella lunghezza
        assertThat(trovata.tipo()).as("tipo della colonna %s di %s, che non ha una lunghezza", colonna, campo).isEqualTo("text");
    }

    /** Lunghezza massima (null se non ne ha) e tipo di una colonna, come li dichiara lo schema */
    private record Colonna(Integer lunghezza, String tipo) {}

    /** La colonna «tabella.colonna» dello schema del database. Se non esiste il test cade: una voce con il nome sbagliato non confronta niente */
    private Colonna colonnaDelloSchema(String tabellaEColonna) {
        String[] parti = tabellaEColonna.split("\\.");
        List<Colonna> trovate = jdbc.query("""
                select character_maximum_length, data_type from information_schema.columns
                where table_schema = current_schema() and table_name = ? and column_name = ?""",
                (riga, numero) -> new Colonna(riga.getObject(1, Integer.class), riga.getString(2)), parti[0], parti[1]);

        assertThat(trovate).as("la colonna %s esiste", tabellaEColonna).hasSize(1);
        return trovate.getFirst();
    }
}
