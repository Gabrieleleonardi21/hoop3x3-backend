package com.hoop3x3.backend.services;

import org.springframework.web.util.HtmlUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Il modello delle email dell'app (recupero password, conferma del cambio email), in HTML e in testo semplice, la riserva per
 * i client che non mostrano l'HTML. Ogni email ha la sua classe con i soli testi (EmailRecuperoPassword, EmailConfermaEmail);
 * qui c'è tutto il resto, in un posto solo.
 * <p>
 * L'HTML è fatto per i client di posta, che ignorano molto CSS (Gmail, Outlook): struttura a tabelle, stili in linea, niente
 * {@code <style>}, script, flexbox o grid, al massimo 560 px. I colori sono quelli del tema del sito (src/index.css del
 * frontend): sfondo asphalt-950, scheda asphalt-900 con il bordo asphalt-700, testo chalk, chalk-muted e chalk-dim, bottone
 * court con il testo asphalt-950 come il bottone primario del sito. L'email è già scura e lo dichiara (color-scheme), così i
 * client in modalità scura non la invertono e il testo resta leggibile.
 * <p>
 * Il logo è l'immagine servita dal sito ({frontend}/logo.png): non un allegato né un data URI, che Gmail blocca. I testi fissi
 * delle email sono scritti nel codice ed entrano così come sono; i valori che arrivano da fuori (l'indirizzo del sito, il link
 * con il token) passano dall'escape dell'HTML. Nessun dato dell'account entra in un'email.
 */
public final class ModelloEmail {

    /** Oggetto, testo semplice e HTML di un'email */
    public record ContenutoEmail(String oggetto, String testo, String html) {}

    /**
     * I testi fissi di un'email, scritti nel codice: entrano nell'HTML senza escape (sono i nostri, con le lettere accentate e
     * gli apostrofi come sono). `testo` è il testo semplice intero, con i segnaposto {link} e {minuti}. La nota sulla scadenza
     * è la stessa per tutte le email e sta nel modello, con i suoi {minuti}
     */
    record Testi(String oggetto, String titolo, String intro, String bottone, String piede, String testo) {}

    // I segnaposto: si sostituiscono in un solo passaggio, così un valore che contiene «{link}» non viene riletto
    private static final Pattern SEGNAPOSTO =
            Pattern.compile("\\{(oggetto|logo|titolo|intro|bottone|link|minuti|piede)}");

    private static final String HTML = """
            <!DOCTYPE html>
            <html lang="it">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <meta name="color-scheme" content="dark">
            <meta name="supported-color-schemes" content="dark">
            <title>{oggetto}</title>
            </head>
            <body style="margin:0;padding:0;background-color:#0b0d12;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#0b0d12" style="background-color:#0b0d12;">
            <tr><td align="center" style="padding:32px 16px;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#12151c" style="max-width:560px;background-color:#12151c;border:1px solid #2a2f3d;border-radius:8px;">
            <tr><td align="center" style="padding:32px 32px 8px;">
            <img src="{logo}" alt="HOOP 3X3" width="59" height="56" style="display:block;height:56px;width:auto;border:0;outline:none;text-decoration:none;color:#f2ede4;font-family:Arial,Helvetica,sans-serif;font-size:20px;font-weight:700;">
            </td></tr>
            <tr><td style="padding:16px 32px 0;font-family:'IBM Plex Sans',Arial,Helvetica,sans-serif;color:#f2ede4;">
            <h1 style="margin:0 0 12px;font-family:'Barlow Condensed','Arial Narrow',Arial,sans-serif;font-size:26px;line-height:1.2;font-weight:700;text-transform:uppercase;letter-spacing:0.5px;color:#f2ede4;">{titolo}</h1>
            <p style="margin:0 0 12px;font-size:15px;line-height:1.6;color:#f2ede4;">{intro}</p>
            <p style="margin:0;font-size:15px;line-height:1.6;color:#f2ede4;">Il link vale {minuti} minuti e una volta sola.</p>
            </td></tr>
            <tr><td align="center" style="padding:24px 32px;">
            <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
            <td align="center" bgcolor="#ff6a1f" style="border-radius:6px;background-color:#ff6a1f;">
            <a href="{link}" target="_blank" style="display:inline-block;padding:14px 28px;font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:700;line-height:1.2;color:#0b0d12;text-decoration:none;border-radius:6px;">{bottone}</a>
            </td></tr></table>
            </td></tr>
            <tr><td style="padding:0 32px 24px;font-family:'IBM Plex Sans',Arial,Helvetica,sans-serif;">
            <p style="margin:0 0 6px;font-size:13px;line-height:1.5;color:#a9a398;">Se il bottone non funziona, copia questo link nel browser:</p>
            <p style="margin:0;font-size:13px;line-height:1.5;word-break:break-all;"><a href="{link}" target="_blank" style="color:#ff6a1f;text-decoration:underline;">{link}</a></p>
            </td></tr>
            <tr><td style="padding:16px 32px 32px;border-top:1px solid #2a2f3d;font-family:'IBM Plex Sans',Arial,Helvetica,sans-serif;">
            <p style="margin:0 0 8px;font-size:13px;line-height:1.5;color:#8c8780;">{piede}</p>
            <p style="margin:0;font-size:12px;line-height:1.5;color:#8c8780;">Questo è un messaggio automatico: non rispondere a questa email.</p>
            </td></tr>
            </table>
            </td></tr>
            </table>
            </body>
            </html>
            """;

    private ModelloEmail() {}

    /**
     * L'email con i suoi testi.
     *
     * @param frontendUrl  l'indirizzo pubblico del sito, senza la barra finale: da qui il logo
     * @param link         il link con il token, che va nel bottone e in chiaro
     * @param durataMinuti quanto vale il link
     */
    static ContenutoEmail componi(Testi testi, String frontendUrl, String link, int durataMinuti) {
        String minuti = String.valueOf(durataMinuti);
        // Il testo semplice non è HTML: il link resta com'è
        String testo = sostituisci(testi.testo(), Map.of("link", link, "minuti", minuti));
        // Nell'HTML i valori che arrivano da fuori sono escapati (& < > " '), anche negli attributi src e href
        String html = sostituisci(HTML, Map.of(
                "oggetto", testi.oggetto(),
                "titolo", testi.titolo(),
                "intro", testi.intro(),
                "bottone", testi.bottone(),
                "piede", testi.piede(),
                "logo", HtmlUtils.htmlEscape(frontendUrl + "/logo.png"),
                "link", HtmlUtils.htmlEscape(link),
                "minuti", HtmlUtils.htmlEscape(minuti)));
        return new ContenutoEmail(testi.oggetto(), testo, html);
    }

    /** I segnaposto del modello con i loro valori, in un solo passaggio */
    private static String sostituisci(String modello, Map<String, String> valori) {
        return SEGNAPOSTO.matcher(modello).replaceAll(trovato -> Matcher.quoteReplacement(valori.get(trovato.group(1))));
    }
}
