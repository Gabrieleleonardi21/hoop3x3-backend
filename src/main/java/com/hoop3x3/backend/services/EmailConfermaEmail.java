package com.hoop3x3.backend.services;

/**
 * L'email di conferma del cambio email, mandata al nuovo indirizzo: i suoi testi. L'HTML, il testo semplice e l'escape dei
 * valori sono di {@link ModelloEmail}; dentro non c'è nessun dato dell'account (né l'email di prima né il nome), entrano solo
 * l'indirizzo del sito e il link.
 */
public final class EmailConfermaEmail {

    static final String OGGETTO = "Conferma il nuovo indirizzo email di HOOP 3X3";

    private static final ModelloEmail.Testi TESTI = new ModelloEmail.Testi(
            OGGETTO,
            "Conferma il nuovo indirizzo",
            "Ciao, hai chiesto di usare questo indirizzo per il tuo account HOOP 3X3.",
            "Conferma l'indirizzo",
            "Se non l'hai chiesto tu, ignora questa email: l'indirizzo del tuo account resta quello di prima.",
            """
            Ciao,

            hai chiesto di usare questo indirizzo per il tuo account HOOP 3X3.

            Apri questo link entro {minuti} minuti per confermarlo (vale una volta sola):

            {link}

            Se non l'hai chiesto tu, ignora questa email: l'indirizzo del tuo account resta quello di prima.

            Questo è un messaggio automatico: non rispondere a questa email.

            HOOP 3X3
            """);

    private EmailConfermaEmail() {}

    /**
     * L'email per il link di conferma.
     *
     * @param frontendUrl  l'indirizzo pubblico del sito, senza la barra finale: da qui il logo
     * @param link         il link con il token, che va nel bottone e in chiaro
     * @param durataMinuti quanto vale il link
     */
    public static ModelloEmail.ContenutoEmail componi(String frontendUrl, String link, int durataMinuti) {
        return ModelloEmail.componi(TESTI, frontendUrl, link, durataMinuti);
    }
}
