package com.hoop3x3.backend.security;

import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.TroppeRichiesteException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.time.Clock;

import static com.hoop3x3.backend.security.LimiteRichieste.Finestra.GIORNO;
import static com.hoop3x3.backend.security.LimiteRichieste.Finestra.MINUTO;
import static com.hoop3x3.backend.services.LogSupport.perLog;
import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * Limiti di frequenza: conta le richieste e, oltre il massimo, risponde 429 con Retry-After senza lasciarle arrivare al
 * controller. Due regole:
 * <ul>
 *   <li><b>Login, registrazione, rinnovo del token e richiesta del link di recupero password</b>, per indirizzo IP e ognuno
 *       con il suo contatore: ogni login e ogni registrazione costano un BCrypt a 12 giri, che gira nel controller, e senza
 *       limite le password si proverebbero a ripetizione e gli account si creerebbero a migliaia (BE-11); ogni richiesta del
 *       link può far partire un'email, e senza limite il server diventerebbe un modo per riempire le caselle degli altri
 *       (il limite per email, 3 ogni 15 minuti, è di RecuperoPasswordService). Respinta qui, la richiesta non costa niente.</li>
 *   <li><b>Coach AI</b> (POST /api/coach/chat), per utente e in due finestre, il minuto e il giorno UTC: ogni richiesta è una
 *       chiamata alla chiave Groq del server, e la registrazione è libera, quindi qualsiasi account potrebbe usarla come proxy
 *       senza fine (BE-2). Respinta qui, non arriva a Groq.</li>
 *   <li><b>Foto dei fogli</b> (POST /api/coach/foglio), per utente e in due finestre come il Coach, con contatori loro: ogni
 *       foto è una chiamata a un modello con immagini, più cara di un messaggio. La chat non consuma le foto e viceversa.</li>
 *   <li><b>Profilo con la password attuale</b> (POST /api/auth/me/password, /api/auth/me/email e /api/auth/me/elimina), per
 *       utente e con un contatore solo per i tre: ognuno verifica la password con un BCrypt, e chi ha rubato un JWT potrebbe
 *       provare password a raffica. Il limite è quello del login (limite.auth-al-minuto). Il cambio del nome non si conta.</li>
 * </ul>
 * È un filtro, come JwtFilter e LimiteDimensioneFilter, e non un HandlerInterceptor o un controllo nel controller, perché deve
 * girare prima di tutto il resto: una richiesta respinta qui non fa leggere il corpo (LimiteDimensioneFilter, che gira dopo,
 * ne legge fino a 2 MB) e non arriva a Spring MVC, e un interceptor non conterebbe le richieste che un filtro ha già fermato,
 * per esempio con il 413. Sta subito dopo il JwtFilter: il Coach e il profilo si contano per utente e l'utente lo riconosce
 * lui (senza utente non si conta niente: ci pensa l'autorizzazione con il 401), mentre login, registrazione e rinnovo non
 * passano dal JwtFilter, come il recupero password.
 * <p>
 * I percorsi si confrontano come fanno le regole di autorizzazione di SecurityConfig (PathPatternRequestMatcher): Spring
 * decodifica il percorso e ne toglie i parametri prima di scegliere il controller, e con un confronto sul testo grezzo
 * «/api/auth/%6Cogin» sarebbe un login che il limite non vede.
 * <p>
 * L'indirizzo è {@code request.getRemoteAddr()}. Dietro un reverse proxy è quello del proxy, a meno che chi pubblica non
 * imposti server.forward-headers-strategy (vedi il README): senza, tutti condividono un contatore. Non si legge a mano
 * X-Forwarded-For: lo scrive chi manda la richiesta, e basterebbe un valore diverso a ogni richiesta per non incontrare mai il limite.
 * <p>
 * L'errore lo scrive ExceptionsHandler, a cui il filtro lo affida come fa JwtFilter con il 401: stesso corpo {message,
 * timestamp} di tutti gli altri errori, più il Retry-After.
 */
@Slf4j
@Component
@EnableConfigurationProperties(LimiteRichiesteProperties.class)
public class LimiteRichiesteFilter extends OncePerRequestFilter {

    private static final RequestMatcher LOGIN = pathPattern(HttpMethod.POST, "/api/auth/login");
    private static final RequestMatcher REGISTRAZIONE = pathPattern(HttpMethod.POST, "/api/auth/register");
    private static final RequestMatcher RINNOVO = pathPattern(HttpMethod.POST, "/api/auth/refresh");
    // Solo la richiesta del link manda un'email: la nuova password con il token non ne manda e non si conta
    private static final RequestMatcher RECUPERO = pathPattern(HttpMethod.POST, "/api/auth/password/dimenticata");
    // Solo la chat chiama Groq: lo stato del Coach (GET /api/coach/status) legge una proprietà e non consuma la quota
    private static final RequestMatcher COACH = pathPattern(HttpMethod.POST, "/api/coach/chat");
    // La lettura di una foto chiama il fornitore dei fogli (Groq o Anthropic): contatori suoi, non quelli della chat
    private static final RequestMatcher FOGLIO = pathPattern(HttpMethod.POST, "/api/coach/foglio");
    // Gli endpoint del profilo che verificano la password attuale: un contatore solo, così chi la cerca non ne ha tre
    private static final RequestMatcher PROFILO_CON_PASSWORD = new OrRequestMatcher(
            pathPattern(HttpMethod.POST, "/api/auth/me/password"),
            pathPattern(HttpMethod.POST, "/api/auth/me/email"),
            pathPattern(HttpMethod.POST, "/api/auth/me/elimina"));

    /** Come si chiama chi supera il limite, nella riga di log */
    private static final String INDIRIZZO = "indirizzo";
    private static final String UTENTE = "utente";

    private final HandlerExceptionResolver exceptionResolver;
    // Un contatore per endpoint: chi sbaglia la password non resta senza poter rinnovare la sessione dallo stesso indirizzo
    private final LimiteRichieste login;
    private final LimiteRichieste registrazione;
    private final LimiteRichieste rinnovo;
    private final LimiteRichieste recupero;
    private final LimiteRichieste coachAlMinuto;
    private final LimiteRichieste coachAlGiorno;
    private final LimiteRichieste foglioAlMinuto;
    private final LimiteRichieste foglioAlGiorno;
    private final LimiteRichieste profilo;

    public LimiteRichiesteFilter(LimiteRichiesteProperties limiti, Clock orologio,
                                 @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
        this.login = new LimiteRichieste(limiti.authAlMinuto(), MINUTO, orologio);
        this.registrazione = new LimiteRichieste(limiti.authAlMinuto(), MINUTO, orologio);
        this.rinnovo = new LimiteRichieste(limiti.authAlMinuto(), MINUTO, orologio);
        this.recupero = new LimiteRichieste(limiti.authAlMinuto(), MINUTO, orologio);
        this.coachAlMinuto = new LimiteRichieste(limiti.coachAlMinuto(), MINUTO, orologio);
        this.coachAlGiorno = new LimiteRichieste(limiti.coachAlGiorno(), GIORNO, orologio);
        this.foglioAlMinuto = new LimiteRichieste(limiti.foglioAlMinuto(), MINUTO, orologio);
        this.foglioAlGiorno = new LimiteRichieste(limiti.foglioAlGiorno(), GIORNO, orologio);
        this.profilo = new LimiteRichieste(limiti.authAlMinuto(), MINUTO, orologio);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            controlla(request);
        } catch (TroppeRichiesteException e) {
            // La richiesta si ferma qui: nessun BCrypt, nessuna chiamata a Groq
            exceptionResolver.resolveException(request, response, null, e);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Conta la richiesta se è una di quelle limitate; se supera il limite lancia TroppeRichiesteException */
    private void controlla(HttpServletRequest request) {
        if (LOGIN.matches(request)) {
            conta(login, request.getRemoteAddr(), INDIRIZZO, "Troppi tentativi di accesso");
        } else if (REGISTRAZIONE.matches(request)) {
            conta(registrazione, request.getRemoteAddr(), INDIRIZZO, "Troppe richieste di registrazione");
        } else if (RINNOVO.matches(request)) {
            conta(rinnovo, request.getRemoteAddr(), INDIRIZZO, "Troppi rinnovi della sessione");
        } else if (RECUPERO.matches(request)) {
            conta(recupero, request.getRemoteAddr(), INDIRIZZO, "Troppe richieste di recupero password");
        } else if (COACH.matches(request)) {
            controllaIlCoach();
        } else if (FOGLIO.matches(request)) {
            controllaIlFoglio();
        } else if (PROFILO_CON_PASSWORD.matches(request)) {
            String id = idDellUtente();
            if (id != null) conta(profilo, id, UTENTE, "Troppi tentativi con la password");
        }
    }

    private void controllaIlCoach() {
        String id = idDellUtente();
        if (id == null) return;
        // Prima il minuto, poi il giorno: la quota giornaliera conta le richieste che passano il minuto, non quelle respinte
        conta(coachAlMinuto, id, UTENTE, "Troppe richieste al Coach AI");
        conta(coachAlGiorno, id, UTENTE, "Quota giornaliera del Coach AI esaurita");
    }

    private void controllaIlFoglio() {
        String id = idDellUtente();
        if (id == null) return;
        // Come per il Coach: prima il minuto, poi il giorno, che conta solo le foto passate dal minuto
        conta(foglioAlMinuto, id, UTENTE, "Troppe foto in poco tempo");
        conta(foglioAlGiorno, id, UTENTE, "Quota giornaliera di lettura dei fogli esaurita");
    }

    /**
     * L'id dell'utente che il JwtFilter ha messo nel contesto, o null. Senza utente (nessun token) non si conta niente: se
     * contasse, chiunque potrebbe togliere le richieste a un utente vero e le richieste anonime riempirebbero la mappa.
     * Risponde l'autorizzazione con il 401
     */
    private static String idDellUtente() {
        Authentication autenticazione = SecurityContextHolder.getContext().getAuthentication();
        if (autenticazione == null || !(autenticazione.getPrincipal() instanceof Utente utente)) return null;
        return String.valueOf(utente.getId());
    }

    private static void conta(LimiteRichieste limite, String chi, String soggetto, String motivo) {
        LimiteRichieste.Esito esito = limite.conta(chi);
        if (esito.consentita()) return;
        // Una riga sola per chiave e per finestra: chi insiste non riempie i log. L'indirizzo può arrivare da
        // un'intestazione del proxy, quindi passa da perLog come ogni valore che sceglie chi manda la richiesta
        if (esito.primoSuperamento()) {
            log.warn("Limite di richieste superato: {} (massimo {}), {} {}", motivo, limite, soggetto, perLog(chi));
        }
        throw new TroppeRichiesteException(motivo, esito.secondiAttesa());
    }
}
