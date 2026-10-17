package com.hoop3x3.backend.services;

import com.hoop3x3.backend.UtenteDiProva;
import com.hoop3x3.backend.dto.FoglioRequestDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Una riga di debug futura («{}», un oggetto) non deve portare nei log la foto di un foglio né una chiave. Qui il toString()
 * di ogni oggetto che tocca l'immagine o la chiave di un fornitore, e delle eccezioni che nascono da un'immagine respinta:
 * nessuno contiene il base64 né la chiave. Chi aggiunge un oggetto che li porta (un record della richiesta, un DTO) lo
 * aggiunge qui.
 */
class ToStringSenzaImmagineTest {

    private static final String CHIAVE_GROQ = "gsk_chiave_da_non_scrivere";
    private static final String CHIAVE_ANTHROPIC = "sk-ant-chiave-da-non-scrivere";
    // Una foto JPEG di prova abbastanza lunga da riconoscerne il base64 in qualunque testo
    private static final byte[] FOTO = new byte[300];
    static {
        FOTO[0] = (byte) 0xff;
        FOTO[1] = (byte) 0xd8;
        FOTO[2] = (byte) 0xff;
        for (int i = 3; i < FOTO.length; i++) FOTO[i] = (byte) (i * 7);
    }
    private static final String BASE64 = Base64.getEncoder().encodeToString(FOTO);

    @Test
    void nessunToStringPortaLaFotoOLaChiave() {
        ObjectMapper mapper = new ObjectMapper();
        GroqProperties groq = new GroqProperties(new GroqProperties.Api(CHIAVE_GROQ), "m", "low", "v");
        AnthropicProperties anthropic = new AnthropicProperties(new AnthropicProperties.Api(CHIAVE_ANTHROPIC), "m");
        // Un lettore finto e configurato: le immagini qui sotto sono tutte da respingere, ma nessuna deve poter uscire dal computer
        LettoreFoglio lettore = mock(LettoreFoglio.class);
        when(lettore.isConfigurato()).thenReturn(true);
        FoglioService service = new FoglioService(lettore);

        List<Object> oggetti = new ArrayList<>(List.of(
                new FoglioRequestDTO("data:image/jpeg;base64," + BASE64),
                new ImmagineFoglio(FOTO, "image/jpeg"),
                groq, groq.api(), anthropic, anthropic.api(),
                new LettoreFoglioGroq(mapper, groq), new LettoreFoglioAnthropic(mapper, anthropic)));
        // Le eccezioni di un'immagine respinta: il messaggio e il toString non riportano ciò che il client ha mandato
        for (String immagine : List.of("data:image/png;base64," + BASE64, "data:image/gif;base64," + BASE64,
                "data:image/jpeg;base64," + BASE64 + "@", "data:image/jpeg;base64," + BASE64.repeat(5_000))) {
            oggetti.add(catchThrowable(() -> service.leggi(UtenteDiProva.conId("mario@test.it"), immagine)));
        }

        verify(lettore, never()).leggi(any());
        assertThat(oggetti).hasSize(12).allSatisfy(oggetto -> {
            assertThat(oggetto).isNotNull();
            assertThat(oggetto.toString()).doesNotContain(BASE64.substring(0, 40)).doesNotContain(CHIAVE_GROQ)
                    .doesNotContain(CHIAVE_ANTHROPIC);
        });
    }
}
