package com.hoop3x3.backend.dto;

import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le forme pubbliche dell'anagrafe, senza contesto Spring. La forma si prova per intero dal JSON in AnagrafePubblicaWebTest:
 * qui resta ciò che dal JSON non si vede, cioè che la scheda pubblica non dipende dall'autore (non lo legge: un'entity
 * senza autore la costruisce lo stesso), e il roster con un buco.
 */
class AnagrafeDTOTest {

    // L'autore è null: se la forma pubblica lo leggesse sarebbe una NullPointerException, come per un'entity senza autore
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

    // Un buco nell'@OrderColumn del roster arriva da Hibernate come un null (AnagrafeIT lo crea con il database vero): le due
    // forme lo saltano invece di cadere con una NullPointerException, e tengono l'ordine degli altri
    @Test
    void ilRosterConUnBuco_leDueFormeLoSaltano() {
        AnagrafeSquadra s = new AnagrafeSquadra();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setNome("Roma 3x3");
        s.setAutore(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        s.setModificatoIl(LocalDateTime.now());
        AnagrafeGiocatore primo = new AnagrafeGiocatore();
        ReflectionTestUtils.setField(primo, "id", UUID.randomUUID());
        AnagrafeGiocatore secondo = new AnagrafeGiocatore();
        ReflectionTestUtils.setField(secondo, "id", UUID.randomUUID());
        s.getRoster().addAll(Arrays.asList(null, primo, null, secondo));

        assertThat(SquadraDTO.from(s).roster()).containsExactly(primo.getId(), secondo.getId());
        assertThat(SquadraDTO.pubblica(s).roster()).containsExactly(primo.getId(), secondo.getId());
    }
}
