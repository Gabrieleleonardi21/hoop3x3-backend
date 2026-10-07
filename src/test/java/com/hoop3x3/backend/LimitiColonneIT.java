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
 * rifiuterebbe la riga con un 409 senza spiegazioni. Nessun valore è scritto a mano: né i tetti né le lunghezze. L'altra metà,
 * che ogni endpoint applichi il tetto del suo DTO (un carattere oltre dà 400), la prova ValidazioneWebTest sugli stessi campi.
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
        Integer lunghezza = lunghezzaDellaColonna(colonna);

        // Una colonna TEXT non ha un limite (null): il tetto è dell'API e non c'è niente da confrontare
        if (lunghezza != null) {
            assertThat(campo.tetto()).as("tetto di %s e lunghezza della colonna %s", campo, colonna).isLessThanOrEqualTo(lunghezza);
        }
    }

    /**
     * La lunghezza massima della colonna «tabella.colonna» dello schema del database, o null se non ha un limite (TEXT). Se la
     * colonna non esiste il test cade qui: una voce della tabella con il nome sbagliato non confronta niente.
     */
    private Integer lunghezzaDellaColonna(String tabellaEColonna) {
        String[] parti = tabellaEColonna.split("\\.");
        List<Integer> trovate = jdbc.query("""
                select character_maximum_length from information_schema.columns
                where table_schema = current_schema() and table_name = ? and column_name = ?""",
                (riga, numero) -> riga.getObject(1, Integer.class), parti[0], parti[1]);

        assertThat(trovate).as("la colonna %s esiste", tabellaEColonna).hasSize(1);
        return trovate.getFirst();
    }
}
