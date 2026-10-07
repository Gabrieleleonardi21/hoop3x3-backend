package com.hoop3x3.backend.services;

import com.hoop3x3.backend.UtenteDiProva;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La regola di proprietà (checkOwner): una risorsa la modifica il suo proprietario oppure un ADMIN, e il confronto è sull'id.
 * Le righe di log degli interventi dell'ADMIN (tracciaModifica, tracciaEliminazione) le prova LogApplicativiTest.
 */
class AccessGuardTest {

    private final AccessGuard guard = new AccessGuard();
    private final Utente mario = UtenteDiProva.conId("mario@test.it");
    private final Utente admin = UtenteDiProva.conId("admin@test.it", Ruolo.ADMIN);

    @Test
    void ilProprietarioPassa() {
        assertThatCode(() -> guard.checkOwner(mario, mario.getId(), "questa lega")).doesNotThrowAnyException();
    }

    @Test
    void unAdminPassaSuiDatiDiUnAltro() {
        assertThatCode(() -> guard.checkOwner(admin, mario.getId(), "questa lega")).doesNotThrowAnyException();
    }

    // Il messaggio dice di che risorsa si tratta: è quello che il client mostra, quindi il testo è un contratto
    @Test
    void chiNonEProprietarioNeAdmin_riceveForbiddenConIlNomeDellaRisorsa() {
        Utente luigi = UtenteDiProva.conId("luigi@test.it");

        assertThatThrownBy(() -> guard.checkOwner(luigi, mario.getId(), "questa lega"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Solo chi ha creato questa lega (o un ADMIN) può modificarla");
    }

    // Conta l'id: un altro utente con la stessa email e lo stesso nome non è il proprietario
    @Test
    void ilConfrontoESuLId_nonSuEmailENome() {
        Utente omonimo = new Utente(mario.getEmail(), "hash", mario.getNome(), Ruolo.USER);
        ReflectionTestUtils.setField(omonimo, "id", UUID.randomUUID());

        assertThatThrownBy(() -> guard.checkOwner(omonimo, mario.getId(), "questa tappa"))
                .isInstanceOf(ForbiddenException.class);
    }

    // I dati di un ADMIN non hanno un trattamento speciale: un utente comune che non è il proprietario non passa
    @Test
    void unUtenteComuneNonPassaSuiDatiDiUnAdmin() {
        assertThatThrownBy(() -> guard.checkOwner(mario, admin.getId(), "questa squadra"))
                .isInstanceOf(ForbiddenException.class);
    }
}
