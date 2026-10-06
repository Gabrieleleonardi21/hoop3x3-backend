package com.hoop3x3.backend.services;

import com.hoop3x3.backend.RichiesteContemporanee;
import com.hoop3x3.backend.TappaDiProva;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Una rinomina della lega e il salvataggio di una sua tappa che arrivano insieme. Salvare o eliminare una tappa cambia anche
 * la data di modifica della lega (touch), e Hibernate riscriveva tutte le colonne della lega, nome compreso: se un altro
 * dispositivo aveva confermato una rinomina dopo che la richiesta aveva letto la lega, il nome tornava quello di prima. Ora
 * l'UPDATE della lega contiene solo le colonne cambiate.
 * L'intreccio è forzato, non lasciato al caso: la rinomina è scritta ma non confermata, quindi la richiesta sulla tappa legge
 * ancora il nome vecchio e, quando prova a scrivere la lega, aspetta il commit della rinomina (RichiesteContemporanee).
 */
@TestDiIntegrazione
class LegaConcorrenzaIT {

    private static final String NOME_NUOVO = "Circuito 2027";

    @Autowired LegaService legaService;
    @Autowired UtenteRepository utenti;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transazioni;

    private Utente mario; // proprietario della lega di prova
    private RichiesteContemporanee insieme;

    @BeforeEach
    void creaIlProprietario() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        insieme = new RichiesteContemporanee(jdbc, transazioni);
    }

    @Test
    void salvareUnaTappaMentreLaLegaVieneRinominata_nonRiportaIlNomeVecchio() throws Exception {
        TappaDTO tappa = TappaDiProva.tappa().build();
        UUID lega = legaService.crea(mario, new NuovaLegaDTO("Circuito 2026", List.of(tappa))).id();

        mentreLaLegaVieneRinominata(lega, () -> legaService.aggiornaTappa(mario, tappa.id(),
                TappaDiProva.tappa().id(tappa.id()).nome("Tappa salvata").build()));

        assertThat(nomeDellaLega(lega)).isEqualTo(NOME_NUOVO);
        // Il salvataggio della tappa è andato a buon fine: il test non passa perché la richiesta è fallita
        assertThat(jdbc.queryForObject("select nome from tappe where id = ?", String.class, tappa.id())).isEqualTo("Tappa salvata");
    }

    @Test
    void eliminareUnaTappaMentreLaLegaVieneRinominata_nonRiportaIlNomeVecchio() throws Exception {
        TappaDTO tappa = TappaDiProva.tappa().build();
        UUID lega = legaService.crea(mario, new NuovaLegaDTO("Circuito 2026", List.of(tappa))).id();

        mentreLaLegaVieneRinominata(lega, () -> legaService.eliminaTappa(mario, tappa.id()));

        assertThat(nomeDellaLega(lega)).isEqualTo(NOME_NUOVO);
        assertThat(jdbc.queryForObject("select count(*) from tappe where lega_id = ?", Integer.class, lega)).isZero();
    }

    /**
     * Un altro dispositivo sta rinominando la lega in «Circuito 2027»: la rinomina è scritta, la riga della lega bloccata e il
     * commit non c'è ancora. La richiesta parte adesso, legge il nome di prima e solo dopo il commit scrive la lega.
     */
    private void mentreLaLegaVieneRinominata(UUID legaId, Runnable richiesta) throws Exception {
        insieme.mentreUnaTransazioneTieneUnaRiga(
                () -> jdbc.update("update leghe set nome = ? where id = ?", NOME_NUOVO, legaId),
                () -> {
                    richiesta.run();
                    return null;
                });
    }

    private String nomeDellaLega(UUID legaId) {
        return jdbc.queryForObject("select nome from leghe where id = ?", String.class, legaId);
    }
}
