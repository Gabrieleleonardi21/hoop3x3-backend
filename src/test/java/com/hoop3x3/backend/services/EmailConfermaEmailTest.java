package com.hoop3x3.backend.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'email di conferma del cambio email, mandata al nuovo indirizzo: lo stesso modello del recupero password (ModelloEmail)
 * con i suoi testi. Bottone con il link, il link in chiaro sotto, la scadenza, ogni valore escapato nell'HTML e nessun dato
 * dell'account: né l'email vecchia né il nome.
 */
class EmailConfermaEmailTest {

    private static final String SITO = "https://hoop3x3.onrender.com";
    private static final String LINK = SITO + "/conferma-email?token=AbC123_-xyz";

    private final ModelloEmail.ContenutoEmail email = EmailConfermaEmail.componi(SITO, LINK, 30);

    @Test
    void oggetto() {
        assertThat(email.oggetto()).isEqualTo("Conferma il nuovo indirizzo email di HOOP 3X3");
        assertThat(email.html()).contains("<title>Conferma il nuovo indirizzo email di HOOP 3X3</title>");
    }

    @Test
    void ilLogoVieneDalSito() {
        assertThat(email.html()).contains("<img src=\"https://hoop3x3.onrender.com/logo.png\"").contains("alt=\"HOOP 3X3\"");
    }

    @Test
    void ilBottonePortaAlLink_eSottoCeIlLinkInChiaro() {
        assertThat(email.html()).contains("<a href=\"" + LINK + "\"").contains("Conferma l'indirizzo</a>");
        int dopoIlBottone = email.html().indexOf("Conferma l'indirizzo</a>");
        assertThat(email.html().indexOf(">" + LINK + "</a>", dopoIlBottone)).isPositive();
    }

    @Test
    void ilTestoDiceChiScrive_perche_laScadenza_eCheCosaFareSeNonSeiStatoTu() {
        assertThat(email.html()).contains("Conferma il nuovo indirizzo</h1>",
                "Ciao, hai chiesto di usare questo indirizzo per il tuo account HOOP 3X3.",
                "Il link vale 30 minuti e una volta sola.",
                "Se non l'hai chiesto tu, ignora questa email: l'indirizzo del tuo account resta quello di prima.",
                "Questo è un messaggio automatico: non rispondere a questa email.");
        assertThat(email.testo()).contains("hai chiesto di usare questo indirizzo per il tuo account HOOP 3X3", "30 minuti",
                LINK, "Se non l'hai chiesto tu, ignora questa email: l'indirizzo del tuo account resta quello di prima.",
                "Questo è un messaggio automatico: non rispondere a questa email.");
    }

    // Né i testi del recupero password (è un'altra email) né dati dell'account
    @Test
    void nonParlaDiPassword_eNonContieneDatiDellAccount() {
        assertThat(email.html()).doesNotContain("password", "Reimposta", "@");
        assertThat(email.testo()).doesNotContain("password", "Reimposta", "@");
    }

    @Test
    void unTokenConCaratteriSpeciali_vieneEscapatoNellHtml_eRestaComeNelTestoSemplice() {
        String strano = SITO + "/conferma-email?token=a&b<c>\"d'e{minuti}";

        ModelloEmail.ContenutoEmail conStrano = EmailConfermaEmail.componi(SITO, strano, 30);

        String escapato = SITO + "/conferma-email?token=a&amp;b&lt;c&gt;&quot;d&#39;e{minuti}";
        assertThat(conStrano.html()).contains("<a href=\"" + escapato + "\"").contains(">" + escapato + "</a>")
                .doesNotContain("<c>", "\"d'e");
        // Il valore non viene riletto: «{minuti}» dentro il link resta com'è
        assertThat(conStrano.testo()).contains(strano);
    }
}
