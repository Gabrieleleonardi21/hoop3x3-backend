package com.hoop3x3.backend.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'email del recupero password, in HTML e in testo semplice: logo dal sito, bottone con il link, il link in chiaro sotto, la
 * scadenza, e ogni valore che entra nell'HTML escapato. Nessun dato dell'account: entrano solo l'indirizzo del sito e il link.
 */
class EmailRecuperoPasswordTest {

    private static final String SITO = "https://hoop3x3.onrender.com";
    private static final String LINK = SITO + "/reimposta-password?token=AbC123_-xyz";

    private final ModelloEmail.ContenutoEmail email = EmailRecuperoPassword.componi(SITO, LINK, 30);

    @Test
    void oggetto() {
        assertThat(email.oggetto()).isEqualTo("Reimposta la password di HOOP 3X3");
    }

    // Il logo è quello servito dal sito (public/logo.png del frontend): niente allegati né data URI, che Gmail blocca
    @Test
    void ilLogoVieneDalSito_conAltEAltezzaFissa() {
        assertThat(email.html())
                .contains("<img src=\"https://hoop3x3.onrender.com/logo.png\"")
                .contains("alt=\"HOOP 3X3\"")
                .contains("height=\"56\"")
                .doesNotContain("data:image")
                .doesNotContain("cid:");
    }

    @Test
    void ilBottonePortaAlLink_eSottoCeIlLinkInChiaro() {
        String bottone = "<a href=\"" + LINK + "\"";
        assertThat(email.html()).contains(bottone).contains("Reimposta la password</a>");
        // Il link in chiaro, per chi non vede il bottone, viene dopo il bottone
        int dopoIlBottone = email.html().indexOf("Reimposta la password</a>");
        assertThat(email.html().indexOf(">" + LINK + "</a>", dopoIlBottone)).isPositive();
    }

    @Test
    void ilTestoDiceChiScrive_perche_laScadenza_eCheCosaFareSeNonSeiStatoTu() {
        assertThat(email.html()).contains("HOOP 3X3", "hai chiesto di reimpostare la password", "30 minuti",
                "Se non l'hai chiesto tu, ignora questa email");
        assertThat(email.testo()).contains("HOOP 3X3", "hai chiesto di reimpostare la password", "30 minuti", LINK,
                "Se non l'hai chiesto tu, ignora questa email");
    }

    // Il mittente è un indirizzo vero, ma l'email non aspetta risposte: lo dice il piè, sotto la nota per chi non l'ha chiesta
    @Test
    void ilPieDiceCheEUnMessaggioAutomatico_sottoLaNotaPerChiNonLHaChiesto() {
        String riga = "Questo è un messaggio automatico: non rispondere a questa email.";
        String nota = "Se non l'hai chiesto tu, ignora questa email";

        assertThat(email.html().indexOf(riga)).isGreaterThan(email.html().indexOf(nota));
        assertThat(email.testo().indexOf(riga)).isGreaterThan(email.testo().indexOf(nota));
    }

    // Layout per i client di posta: tabelle, stili in linea, al massimo 560 px, i colori del tema del sito (asphalt e court)
    @Test
    void ilLayoutEFattoPerIClientDiPosta() {
        assertThat(email.html())
                .startsWith("<!DOCTYPE html>")
                .contains("<table", "max-width:560px", "#0b0d12", "#12151c", "#f2ede4", "#ff6a1f")
                .contains("<meta name=\"color-scheme\" content=\"dark\">")
                .doesNotContain("<style", "<script", "display:flex", "display:grid", "javascript:");
    }

    // Oggi entrano solo il sito e il link, ma ogni valore passa dall'escape: un token con caratteri speciali non apre un tag
    // né chiude l'attributo
    @Test
    void unTokenConCaratteriSpeciali_vieneEscapatoNellHtml_eRestaComeNelTestoSemplice() {
        String strano = SITO + "/reimposta-password?token=a&b<c>\"d'e";

        ModelloEmail.ContenutoEmail conStrano = EmailRecuperoPassword.componi(SITO, strano, 30);

        String escapato = SITO + "/reimposta-password?token=a&amp;b&lt;c&gt;&quot;d&#39;e";
        assertThat(conStrano.html()).contains("<a href=\"" + escapato + "\"").contains(">" + escapato + "</a>")
                .doesNotContain("<c>", "\"d'e");
        assertThat(conStrano.testo()).contains(strano);
    }

    @Test
    void ancheLIndirizzoDelSitoNelLogoVieneEscapato() {
        ModelloEmail.ContenutoEmail conSito = EmailRecuperoPassword.componi("https://x.it/\"><script>", LINK, 30);

        assertThat(conSito.html()).contains("src=\"https://x.it/&quot;&gt;&lt;script&gt;/logo.png\"").doesNotContain("<script>");
    }
}
