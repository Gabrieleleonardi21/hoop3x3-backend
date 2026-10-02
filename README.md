# Hoop 3x3 — Backend

API REST del gestionale [Hoop 3x3](https://github.com/Gabrieleleonardi21/Hoops-3x3): Spring Boot 4 (Java 25), Spring Security + JWT, JPA/Hibernate, PostgreSQL. Porta `3001`.

## Avvio

**1. Database** — crea il DB `hoop3x3` ed esegui `db/schema.sql` (in pgAdmin: Query Tool → apri il file → Esegui), oppure:

```bash
createdb hoop3x3 && psql -d hoop3x3 -f db/schema.sql
```

Su un database già esistente basta rieseguire `db/schema.sql` (è tutto `IF NOT EXISTS`, i dati non si toccano): crea la tabella `refresh_tokens`, senza la quale il server non parte perché Hibernate gira con `ddl-auto=validate`.

```bash
psql -d hoop3x3 -f db/schema.sql
```

**2. Configurazione** — copia `env.properties.example` in `env.properties` (ignorato da git) e compila i valori. I segreti si controllano all'avvio, perché un esempio lasciato com'è renderebbe nota a tutti la chiave dei token o la password dell'amministratore:

- `JWT_SECRET` (obbligatorio) — almeno 32 caratteri casuali, per esempio generati con `openssl rand -base64 48`. Se manca, è più corto o è ancora il valore d'esempio del vecchio `env.properties.example` (`cambia-questa-stringa-...`), il server non parte e spiega perché; il valore del secret non finisce mai nei log. Cambiarlo invalida i JWT già emessi: gli utenti rifanno il login.
- `ADMIN_EMAIL` e `ADMIN_PASSWORD` — l'ADMIN creato al primo avvio. La password deve avere almeno 8 caratteri ed essere diversa da `admin123`: altrimenti, anche se è vuota, l'admin non viene creato e nei log compare un avviso (senza admin neanche `SEED_DEMO` carica i dati di prova). Con l'email vuota il seeder è spento: nessun admin e nessun avviso. Un admin già presente nel database non viene toccato, quindi neanche il controllo lo riguarda.
- `DB_USERNAME`, `DB_PASSWORD` e, facoltativa, `GROQ_API_KEY` per il Coach AI.

**3. Server**

```bash
./mvnw spring-boot:run
```

Il frontend in sviluppo inoltra `/api` verso `http://localhost:3001` tramite il proxy di Vite.

## Test

- `./mvnw test`: test senza database (web con MockMvc, servizi con Mockito).
- `./mvnw verify -Pintegrazione`: anche i test di integrazione con PostgreSQL (classi `*IT`). Usano il database di prova `hoop3x3_test` sul PostgreSQL locale, da creare una volta con `createdb hoop3x3_test`: all'avvio dei test lo schema lo crea `db/schema.sql` e prima di ogni test le tabelle vengono svuotate. Per un altro database c'è `TEST_DB_URL`, con `TEST_DB_USERNAME` e `TEST_DB_PASSWORD`; altrimenti valgono `DB_USERNAME` e `DB_PASSWORD` di `env.properties`. Le variabili `SPRING_DATASOURCE_*` non hanno effetto sui test di integrazione. Lo script che svuota le tabelle si rifiuta di girare su un database il cui nome non contiene «test».

## Coach AI

Proxy verso [Groq](https://console.groq.com/) (`POST /api/coach/chat`, autenticato): la chiave resta sul server. Modello di default `openai/gpt-oss-120b`, sovrascrivibile con `GROQ_MODEL` in `env.properties`. Senza chiave il Coach è disattivato e il resto dell'app funziona.

La richiesta è controllata prima di arrivare a Groq, altrimenti 400: `messages` da 1 a 60 messaggi, ognuno con ruolo `system`, `user`, `assistant` o `tool`, per al massimo 100.000 caratteri; `tools` al massimo 20 strumenti (50.000 caratteri). Modello e limite di token li fissa il server. Groq ha 5 secondi per accettare la connessione e 60 per mandare l'intera risposta.

Gli errori di Groq arrivano al client senza dettagli interni: 429 se Groq limita le richieste, 400 se rifiuta la richiesta (anche perché troppo lunga), 502 per tutto il resto (errore di Groq, chiave non valida, rete, timeout, risposta che non è un oggetto JSON), 503 se la chiave manca. Nei log del server ogni 502 e 503 ha la sua riga `WARN` con la causa: stato e corpo della risposta di Groq (troncato a 500 caratteri) oppure l'eccezione di rete. La chiave non viene mai scritta nei log.

## Sessioni e refresh token

Il JWT di accesso dura poco e il client lo rinnova con un refresh token tenuto in un cookie httpOnly: chi torna dopo giorni non deve rifare il login, e il logout revoca il refresh token.

- **JWT** — 30 minuti di default, da 5 a 1440 con `jwt.durata-minuti` (fuori da questo intervallo il server non parte: sotto i 5 minuti il client rinnoverebbe a ogni richiesta, perché rinnova in anticipo quando mancano meno di 2 minuti alla scadenza). Il client lo manda nell'header `Authorization: Bearer`; con il token vuoto, scaduto o alterato la risposta è 401.
- **Cookie** `hoop3x3_refresh` — `HttpOnly`, `SameSite=Lax`, `Path=/api/auth` (il browser lo rimanda solo agli endpoint di autenticazione), 30 giorni (`auth.refresh-giorni`). In produzione con HTTPS va impostato `AUTH_COOKIE_SECURE=true` in `env.properties` o come variabile d'ambiente (di default è `false`).
- **Database** — la tabella `refresh_tokens` conserva solo l'hash SHA-256 del token, mai il token in chiaro.

Endpoint:

- `POST /api/auth/register` e `POST /api/auth/login` — rispondono `{token, user}` e impostano il cookie di refresh.
- `POST /api/auth/refresh` — pubblico, senza Bearer: ruota il refresh token (il vecchio smette di valere), imposta il nuovo cookie e risponde `{token, user}` con un nuovo JWT. Cookie assente, sconosciuto (anche se già ruotato) o scaduto: 401 «Sessione scaduta: accedi di nuovo». Due refresh contemporanei con lo stesso cookie: uno vince (200), l'altro riceve 409 «Sessione già rinnovata da un'altra richiesta: riprova».
- `POST /api/auth/logout` — 204: cancella il cookie e la riga in tabella.

`refresh` e `logout` si autenticano solo con il cookie: il client non deve mandare `Authorization`, perché un JWT scaduto verrebbe respinto con 401 dal `JwtFilter` prima ancora di leggere il cookie.

**Deploy** — cosa serve dipende da dove stanno frontend e API.

- **Stessa origine** (stesso schema, host e porta: il proxy di Vite in sviluppo, un reverse proxy in produzione). Basta la configurazione qui sopra, con due attenzioni: con HTTPS imposta `AUTH_COOKIE_SECURE=true`, e l'origine pubblica del frontend deve stare in `cors.origins` (`CORS_ORIGINS` in `env.properties` o come variabile d'ambiente). Dietro un proxy il backend confronta l'header `Origin` del browser con schema, host e porta che vede lui (il proxy di Vite riscrive l'host, un reverse proxy che termina HTTPS cambia lo schema) e, se non coincidono, tratta POST, PUT, PATCH e DELETE come richieste di un'altra origine: se l'origine non è in elenco rispondono 403 «Invalid CORS request», mentre le GET passano. Succede anche in sviluppo se Vite parte su una porta non elencata (per esempio la 5174, quando la 5173 è occupata).
- **Origini diverse, stesso sito** (sottodomini di un dominio vostro, per esempio `app.esempio.it` e `api.esempio.it`; due sottodomini di un hosting condiviso come `onrender.com` o `vercel.app` sono siti diversi). Servono i passi 2 e 3 qui sotto e `AUTH_COOKIE_SECURE=true`; `SameSite` resta `Lax`.
- **Siti diversi** (frontend e backend su due domini). Servono tutti e quattro i passi, ma non bastano con i browser che bloccano i cookie di terze parti (Safari, la navigazione in incognito di Chrome): lì la sessione resta di 30 minuti. La strada robusta è riportare l'API sotto la stessa origine del frontend con un proxy o una regola di rewrite dell'host.

Su origini diverse l'origine del frontend deve comunque stare in `CORS_ORIGINS`: altrimenti il browser blocca anche il login e l'app mostra «Server non raggiungibile». Con l'origine elencata ma senza gli altri passi il login funziona, ma la sessione dura quanto il JWT (30 minuti), perché il cookie di refresh non arriva al backend.

1. **Cookie** (solo tra siti diversi) — `SameSite=None` + `Secure`. Oggi `AuthCookies` imposta `Lax`; `Secure` si accende con `AUTH_COOKIE_SECURE=true` (richiede HTTPS).
2. **CORS** — `allowCredentials(true)` e origini esplicite, mai `*` (`CorsConfig`, property `cors.origins`). Oggi le credenziali non sono ammesse.
3. **Frontend** — `credentials: "include"` sulle chiamate a `/api/auth/*`. Oggi usa la modalità predefinita del browser, valida solo a stessa origine.
4. **CSRF** (solo tra siti diversi) — header `X-Requested-With` richiesto dal backend su `refresh` e `logout` e inviato dal frontend: con `SameSite=None` il cookie parte anche da siti terzi, e un header personalizzato impone il preflight CORS, che i siti non ammessi non superano. Oggi il backend non lo controlla e il frontend non lo invia; l'header va ammesso anche negli `allowedHeaders` di `CorsConfig`.

**Ordine di pubblicazione** — prima il frontend nuovo (compatibile con il backend precedente), poi questo backend: con il JWT a 30 minuti, un frontend senza il rinnovo farebbe uscire gli utenti dopo mezz'ora.

**Limiti noti**

- Il logout revoca il refresh token del browser da cui parte: un JWT già emesso resta valido fino alla sua scadenza (al massimo 30 minuti) e le sessioni aperte su altri dispositivi non vengono toccate.
- Non c'è rilevamento del riuso di un refresh token già ruotato né un «esci da tutti i dispositivi»: chi ruba il cookie e lo usa per primo ottiene una sessione che si rinnova finché non scade o non viene revocata.
- Il vecchio refresh token smette di valere appena il server lo ruota: se la risposta non arriva al browser (pagina chiusa o rete caduta durante il rinnovo), al rinnovo successivo si torna al login. Un periodo di grazia di qualche decina di secondi lo eviterebbe.

## Struttura

```
db/schema.sql                   # tabelle PostgreSQL (da eseguire in pgAdmin)
env.properties.example          # segreti: copiare in env.properties
src/main/java/com/hoop3x3/backend/
├── controllers/  # REST (auth, utenti, leghe, tappe, anagrafe, archivio, coach)
├── dto/          # record con validazione Bean Validation
├── entities/     # JPA: Utente, RefreshToken, Lega, Tappa (+Regole), AnagrafeGiocatore/Squadra, ArchivioTappa
├── exceptions/   # eccezioni tipizzate + ExceptionsHandler (corpo uniforme {message, timestamp})
├── repositories/ # Spring Data JPA
├── runners/      # DataSeeder (admin iniziale), DemoSeeder (dati di prova da resources/seed/estathe25.json)
├── security/     # SecurityConfig, JwtFilter, JWTtools, JwtProperties (secret e durata del JWT, validati all'avvio), AuthCookies, CorsConfig, JsonAuthEntryPoint, LimiteDimensioneFilter (413 oltre 2 MB)
└── services/     # logica: proprietà (AccessGuard), JSON delle tappe, proxy Groq, refresh token (RefreshTokenService)
```

## Dati di prova

Con `SEED_DEMO=true` il primo avvio carica il circuito Estathé 2025 (`resources/seed/estathe25.json`) intestandolo all'admin; gli avvii successivi non lo duplicano.
