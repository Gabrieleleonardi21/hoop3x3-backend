# Hoop 3x3 — Backend

API REST del gestionale [Hoop 3x3](https://github.com/Gabrieleleonardi21/Hoops-3x3): Spring Boot 4 (Java 17+), Spring Security + JWT, JPA/Hibernate, PostgreSQL. Porta `3001`.

## Avvio

**1. Database** — crea il DB `hoop3x3` ed esegui `db/schema.sql` (in pgAdmin: Query Tool → apri il file → Esegui), oppure:

```bash
createdb hoop3x3 && psql -d hoop3x3 -f db/schema.sql
```

Su un database già esistente basta rieseguire `db/schema.sql` (è tutto `IF NOT EXISTS`, i dati non si toccano): crea la tabella `refresh_tokens`, senza la quale il server non parte perché Hibernate gira con `ddl-auto=validate`.

```bash
psql -d hoop3x3 -f db/schema.sql
```

**2. Configurazione** — copia `env.properties.example` in `env.properties` (ignorato da git) e compila password DB, `JWT_SECRET` e, facoltativa, `GROQ_API_KEY` per il Coach AI.

**3. Server**

```bash
mvn spring-boot:run
```

Il frontend in sviluppo inoltra `/api` verso `http://localhost:3001` tramite il proxy di Vite.

## Coach AI

Proxy verso [Groq](https://console.groq.com/) (`POST /api/coach/chat`, autenticato): la chiave resta sul server. Modello di default `openai/gpt-oss-120b`, sovrascrivibile con `GROQ_MODEL` in `env.properties`. Senza chiave il Coach è disattivato e il resto dell'app funziona.

## Sessioni e refresh token

Il JWT di accesso dura poco e il client lo rinnova con un refresh token tenuto in un cookie httpOnly: chi torna dopo giorni non deve rifare il login, e il logout revoca il refresh token.

- **JWT** — 30 minuti (`jwt.durata-minuti`); il client lo manda nell'header `Authorization: Bearer`.
- **Cookie** `hoop3x3_refresh` — `HttpOnly`, `SameSite=Lax`, `Path=/api/auth` (il browser lo rimanda solo agli endpoint di autenticazione), 30 giorni (`auth.refresh-giorni`). In produzione con HTTPS va impostato `AUTH_COOKIE_SECURE=true` in `env.properties` o come variabile d'ambiente (di default è `false`).
- **Database** — la tabella `refresh_tokens` conserva solo l'hash SHA-256 del token, mai il token in chiaro.

Endpoint:

- `POST /api/auth/register` e `POST /api/auth/login` — rispondono `{token, user}` e impostano il cookie di refresh.
- `POST /api/auth/refresh` — pubblico, senza Bearer: ruota il refresh token (il vecchio smette di valere), imposta il nuovo cookie e risponde `{token, user}` con un nuovo JWT. Cookie assente, sconosciuto (anche se già ruotato) o scaduto: 401 «Sessione scaduta: accedi di nuovo». Due refresh contemporanei con lo stesso cookie: uno vince (200), l'altro riceve 409 «Sessione già rinnovata da un'altra richiesta: riprova».
- `POST /api/auth/logout` — 204: cancella il cookie e la riga in tabella.

`refresh` e `logout` si autenticano solo con il cookie: il client non deve mandare `Authorization`, perché un JWT scaduto verrebbe respinto con 401 dal `JwtFilter` prima ancora di leggere il cookie.

**Deploy** — cosa serve dipende da dove stanno frontend e API.

- **Stessa origine** (stesso schema, host e porta: il proxy di Vite in sviluppo, un reverse proxy in produzione). Basta la configurazione qui sopra, con due attenzioni: con HTTPS imposta `AUTH_COOKIE_SECURE=true`, e l'origine pubblica del frontend deve stare in `cors.origins`. Dietro un proxy il backend riceve l'header `Origin` del browser e, se non coincide con il proprio host, tratta POST, PUT, PATCH e DELETE come richieste di un'altra origine: se l'origine non è in elenco rispondono 403 «Invalid CORS request», mentre le GET passano. Succede anche in sviluppo se Vite parte su una porta non elencata (per esempio la 5174, quando la 5173 è occupata).
- **Origini diverse, stesso sito** (sottodomini dello stesso dominio, per esempio `app.esempio.it` e `api.esempio.it`). Servono i passi 2 e 3 qui sotto e `AUTH_COOKIE_SECURE=true`; `SameSite` resta `Lax`.
- **Siti diversi** (frontend e backend su due domini). Servono tutti e quattro i passi, ma non bastano con i browser che bloccano i cookie di terze parti (Safari, la navigazione in incognito di Chrome): lì la sessione resta di 30 minuti. La strada robusta è riportare l'API sotto la stessa origine del frontend con un proxy o una regola di rewrite dell'host.

Senza questi passi, su origini diverse il login funziona ma la sessione dura quanto il JWT (30 minuti), perché il cookie di refresh non arriva al backend.

1. **Cookie** (solo tra siti diversi) — `SameSite=None` + `Secure`. Oggi `AuthCookies` imposta `Lax`; `Secure` si accende con `AUTH_COOKIE_SECURE=true` (richiede HTTPS).
2. **CORS** — `allowCredentials(true)` e origini esplicite, mai `*` (`CorsConfig`, property `cors.origins`). Oggi le credenziali non sono ammesse.
3. **Frontend** — `credentials: "include"` sulle chiamate a `/api/auth/*`. Oggi usa la modalità predefinita del browser, valida solo a stessa origine.
4. **CSRF** (solo tra siti diversi) — header `X-Requested-With` richiesto dal backend su `refresh` e `logout` e inviato dal frontend: con `SameSite=None` il cookie parte anche da siti terzi, e un header personalizzato impone il preflight CORS, che i siti non ammessi non superano. Oggi il backend non lo controlla e il frontend non lo invia; l'header va ammesso anche negli `allowedHeaders` di `CorsConfig`.

**Ordine di pubblicazione** — prima il frontend nuovo (compatibile con il backend precedente), poi questo backend: con il JWT a 30 minuti, un frontend senza il rinnovo farebbe uscire gli utenti dopo mezz'ora.

**Limiti noti**

- Il logout revoca il refresh token del browser da cui parte: un JWT già emesso resta valido fino alla sua scadenza (al massimo 30 minuti) e le sessioni aperte su altri dispositivi non vengono toccate.
- Non c'è rilevamento del riuso di un refresh token già ruotato né un «esci da tutti i dispositivi»: chi ruba il cookie e lo usa per primo ottiene una sessione che si rinnova finché non scade o non viene revocata.
- Il vecchio refresh token smette di valere appena il server lo ruota: se la risposta non arriva al browser (pagina chiusa o rete caduta durante il rinnovo), al rinnovo successivo si torna al login. Un periodo di grazia di qualche decina di secondi lo eviterebbe.
- Con `jwt.durata-minuti` sotto i 3 minuti il client rinnova a ogni richiesta, perché rinnova in anticipo quando mancano meno di 2 minuti alla scadenza.

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
├── security/     # SecurityConfig, JwtFilter, JWTtools, AuthCookies, CorsConfig, JsonAuthEntryPoint
└── services/     # logica: proprietà (AccessGuard), JSON delle tappe, proxy Groq, refresh token (RefreshTokenService)
```

## Dati di prova

Con `SEED_DEMO=true` il primo avvio carica il circuito Estathé 2025 (`resources/seed/estathe25.json`) intestandolo all'admin; gli avvii successivi non lo duplicano.
