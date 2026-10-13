package com.hoop3x3.backend.services;

/**
 * L'email del recupero password: i suoi testi. L'HTML, il testo semplice e l'escape dei valori sono di {@link ModelloEmail};
 * dentro non c'è nessun dato dell'account, entrano solo l'indirizzo del sito e il link.
 */
public final class EmailRecuperoPassword {

    static final String OGGETTO = "Reimposta la password di HOOP 3X3";

    private static final ModelloEmail.Testi TESTI = new ModelloEmail.Testi(
            OGGETTO,
            "Reimposta la password",
            "Ciao, ti scriviamo da HOOP 3X3 perché hai chiesto di reimpostare la password del tuo account.",
            "Reimposta la password",
            "Se non l'hai chiesto tu, ignora questa email: la password resta quella di prima.",
            """
            Ciao,

            ti scriviamo da HOOP 3X3 perché hai chiesto di reimpostare la password del tuo account.

            Apri questo link entro {minuti} minuti per sceglierne una nuova (vale una volta sola):

            {link}

            Se non l'hai chiesto tu, ignora questa email: la password resta quella di prima.

            Questo è un messaggio automatico: non rispondere a questa email.

            HOOP 3X3
            """);

    private EmailRecuperoPassword() {}

    /**
     * L'email per il link di recupero.
     *
     * @param frontendUrl  l'indirizzo pubblico del sito, senza la barra finale: da qui il logo
     * @param link         il link con il token, che va nel bottone e in chiaro
     * @param durataMinuti quanto vale il link
     */
    public static ModelloEmail.ContenutoEmail componi(String frontendUrl, String link, int durataMinuti) {
        return ModelloEmail.componi(TESTI, frontendUrl, link, durataMinuti);
    }
}
