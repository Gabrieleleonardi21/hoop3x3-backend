package com.hoop3x3.backend.dto;

import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le forme pubbliche dell'anagrafe, senza contesto Spring. La forma si prova per intero dal JSON in AnagrafePubblicaWebTest:
 * qui resta ciò che dal JSON non si vede, cioè che la scheda pubblica non legge l'autore (negli elenchi è caricato a
 * richiesta, e con open-in-view spento leggerlo fuori dalla transazione sarebbe un errore).
 */
class AnagrafeDTOTest {

    // L'autore è null: se la forma pubblica lo leggesse sarebbe una NullPointerException, come un autore non caricato
    @Test
    void ilGiocatorePubblicoNonLeggeLAutore() {
        AnagrafeGiocatore g = new AnagrafeGiocatore();
        ReflectionTestUtils.setField(g, "id", UUID.randomUUID());
        g.setNome("Mario");
        g.setCognome("Rossi");
        g.setNote("Una nota");
        g.setModificatoIl(LocalDateTime.now());

        GiocatoreDTO pubblico = GiocatoreDTO.pubblico(g);

        assertThat(pubblico.nome()).isEqualTo("Mario");
        assertThat(pubblico.note()).isEmpty();
        assertThat(pubblico.autore()).isEmpty();
        assertThat(pubblico.autoreId()).isNull();
    }

    @Test
    void laSquadraPubblicaNonLeggeLAutore() {
        AnagrafeSquadra s = new AnagrafeSquadra();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setNome("Roma 3x3");
        s.setReferente("Luigi Bianchi");
        s.setModificatoIl(LocalDateTime.now());

        SquadraDTO pubblica = SquadraDTO.pubblica(s);

        assertThat(pubblica.nome()).isEqualTo("Roma 3x3");
        assertThat(pubblica.referente()).isEmpty();
        assertThat(pubblica.autore()).isEmpty();
        assertThat(pubblica.autoreId()).isNull();
    }
}
