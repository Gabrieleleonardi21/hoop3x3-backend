# Hoop 3x3 — Backend

API REST del gestionale [Hoop 3x3](https://github.com/Gabrieleleonardi21/Hoops-3x3): Spring Boot 4 (Java 25), Spring Security + JWT, JPA/Hibernate, PostgreSQL. Porta `3001`.

## Avvio

**1. Database** — crea un database vuoto, senza tabelle (in pgAdmin: clic destro su Databases → Create → Database, nome `hoop3x3`), oppure:

```bash
createdb hoop3x3
```

Le tabelle le crea il server al primo avvio con le migrazioni di [Flyway](https://flywaydb.org) (`src/main/resources/db/migration`): non c'è nessuno script da eseguire. Flyway segna le migrazioni applicate nella tabella `flyway_schema_history`, accanto alle altre. L'utente del database (`DB_USERNAME`) deve poter creare e modificare tabelle nello schema `public`, per esempio perché è il proprietario del database: Flyway crea `flyway_schema_history` e applica le migrazioni, e con un utente che può solo leggere e scrivere i dati il primo avvio fallisce.

Un database già esistente, creato a mano con il vecchio `db/schema.sql`, non va ricreato né toccato: al primo avvio Flyway trova le tabelle ma non lo storico e lo segna come versione 1 (`V1__schema_iniziale.sql` è quello schema) senza rieseguire niente, quindi i dati restano com'erano. Da quel momento ogni cambio di schema arriva come migrazione nuova (V2, V3…) e il server la applica da solo all'avvio: la V2 lega le pubblicazioni dell'archivio alle loro tappe e lascia dove sono le eventuali pubblicazioni orfane di un database già in uso (vedi «Archivio circuito»); la V3 aggiunge `seed_eseguiti`, il segno dei seed già eseguiti (vedi «Dati di prova»). Il database deve avere lo schema attuale, compresa la tabella `refresh_tokens`: se manca, il server non parte e dice quale tabella manca (`Schema validation: missing table [refresh_tokens]`). In quel caso riesegui il file intero, che è idempotente (tutto `IF NOT EXISTS`, i dati non si toccano, i messaggi `already exists, skipping` sono normali), e riavvia:

```bash
psql -d hoop3x3 -f src/main/resources/db/migration/V1__schema_iniziale.sql
```

Va bene sia prima del primo avvio sia dopo un primo avvio fallito.

**2. Configurazione** — copia `env.properties.example` in `env.properties` (ignorato da git) e compila i valori. I segreti si controllano all'avvio, perché un esempio lasciato com'è renderebbe nota a tutti la chiave dei token o la password dell'amministratore:

- `JWT_SECRET` (obbligatorio) — almeno 32 caratteri casuali, per esempio generati con `openssl rand -base64 48`. Se manca, è più corto o è ancora il valore d'esempio del vecchio `env.properties.example` (`cambia-questa-stringa-...`), il server non parte e spiega perché; il valore del secret non finisce mai nei log. Cambiarlo invalida i JWT già emessi: gli utenti rifanno il login.
- `ADMIN_EMAIL` e `ADMIN_PASSWORD` — l'ADMIN creato al primo avvio. La password deve avere almeno 8 caratteri ed essere diversa da `admin123`: altrimenti, anche se è vuota, l'admin non viene creato e nei log compare un avviso (senza admin neanche `SEED_DEMO` carica i dati di prova). Con l'email vuota il seeder è spento e una riga INFO nei log lo dice; se però la password c'è, compare un avviso, perché di solito è l'email dimenticata. Un admin già presente nel database non viene toccato (una riga INFO lo dice), quindi neanche il controllo sulla password lo riguarda.
- `DB_USERNAME`, `DB_PASSWORD` e, facoltativa, `GROQ_API_KEY` per il Coach AI.

**3. Server**

```bash
./mvnw spring-boot:run
```

Il frontend in sviluppo inoltra `/api` verso `http://localhost:3001` tramite il proxy di Vite.

## Test

- `./mvnw test`: test senza database (web con MockMvc, servizi con Mockito).
- `./mvnw verify -Pintegrazione`: anche i test di integrazione con PostgreSQL (classi `*IT`). Usano il database di prova `hoop3x3_test` sul PostgreSQL locale, da creare una volta, vuoto, con `createdb hoop3x3_test`: all'avvio dei test lo schema lo creano le migrazioni di Flyway (un database di prova che ha già le tabelle, create dal vecchio `db/schema.sql`, viene riconosciuto come versione 1) e prima di ogni test le tabelle vengono svuotate, tranne lo storico di Flyway. Per un altro database c'è `TEST_DB_URL`, con `TEST_DB_USERNAME` e `TEST_DB_PASSWORD`; altrimenti valgono `DB_USERNAME` e `DB_PASSWORD` di `env.properties`. Le variabili `SPRING_DATASOURCE_*` non hanno effetto sui test di integrazione. Lo script che svuota le tabelle si rifiuta di girare su un database il cui nome non contiene «test». Il profilo «test» alza i limiti di frequenza (vedi «Limiti di frequenza»): gli IT fanno più accessi insieme dallo stesso indirizzo e con i valori di produzione verrebbero respinti con 429.

## Migrazioni del database

Lo schema cambia solo con le migrazioni di Flyway in `src/main/resources/db/migration`: un file SQL per ogni modifica, chiamato `V<numero>__<descrizione>.sql` (due underscore dopo il numero, per esempio `V4__versione_tappe.sql`), con il numero successivo all'ultimo. All'avvio il server applica in ordine quelle che il database non ha ancora, poi Hibernate (`ddl-auto=validate`) controlla che le entity combacino con le tabelle: nessuno deve più applicare SQL a mano su un ambiente.

- **Una migrazione già applicata non si modifica**, nemmeno nei commenti: Flyway ne confronta il checksum e il server non parte (`Migration checksum mismatch`). Un errore si corregge con una migrazione nuova.
- **Il nome del file conta**: con un nome sbagliato (per esempio `V2_x.sql`, con un solo underscore) il server non parte e dice quale file è sbagliato, invece di ignorare in silenzio quella migrazione (`spring.flyway.validate-migration-naming`).
- **Niente nome dello schema**: dentro una migrazione si scrive `tappe`, non `public.tappe`. `MigrazioniIT` esegue le migrazioni anche su schemi temporanei, e un nome con `public.` colpirebbe lo schema vero e farebbe fallire quei test.
- **Tabella nuova**: il suo nome va aggiunto anche alla `TRUNCATE` di `src/test/resources/svuota.sql`, lo controlla `MigrazioniIT`. `flyway_schema_history`, lo storico di Flyway, non ci va mai.
- `spring.flyway.baseline-on-migrate=true` (in `application.properties`) serve ai database creati a mano prima di Flyway: Flyway li segna come versione 1 invece di rifiutarli. Su un database che ha già lo storico non cambia nulla.
- `MigrazioniIT` prova i due percorsi, database vuoto e database creato a mano prima di Flyway, su schemi temporanei e qualunque sia lo stato del database di prova: una migrazione nuova (V3, V4…) non richiede ritocchi a quei test.

## Coach AI

Proxy verso [Groq](https://console.groq.com/) (`POST /api/coach/chat`, autenticato): la chiave resta sul server. Modello di default `openai/gpt-oss-120b`, sovrascrivibile con `GROQ_MODEL` in `env.properties`. Senza chiave il Coach è disattivato e il resto dell'app funziona.

La richiesta è controllata prima di arrivare a Groq, altrimenti 400: `messages` da 1 a 60 messaggi, ognuno con ruolo `system`, `user`, `assistant` o `tool`, per al massimo 100.000 caratteri; `tools` al massimo 20 strumenti (50.000 caratteri). Modello e limite di token li fissa il server. Groq ha 5 secondi per accettare la connessione e 60 per mandare l'intera risposta.

Ogni utente può fare 20 richieste al minuto e 300 al giorno: oltre, il server risponde 429 (vedi «Limiti di frequenza»).

Gli errori di Groq arrivano al client senza dettagli interni: 429 se Groq limita le richieste, 400 se rifiuta la richiesta (anche perché troppo lunga), 502 per tutto il resto (errore di Groq, chiave non valida, rete, timeout, risposta che non è un oggetto JSON), 503 se la chiave manca. Nei log del server ogni 502 e 503 ha la sua riga `WARN` con la causa: stato e corpo della risposta di Groq (troncato a 500 caratteri e su una riga sola: a capo e caratteri di controllo sono scritti per esteso) oppure l'eccezione di rete. La chiave non viene mai scritta nei log.

## Archivio circuito

Le tappe concluse si pubblicano nell'archivio del circuito. Leggerlo è pubblico, senza account; pubblicare e ritirare chiedono il login.

- `GET /api/archivio` — l'elenco, dalla pubblicazione più recente, in forma sintetica: ogni voce è `{tappaId, nome, luogo, data, nSquadre, lega, autore, ts}`, cioè ciò che serve a mostrare l'elenco e ad aprire la tappa (`nSquadre` è il numero delle squadre iscritte, `autore` il nome visualizzato, `ts` i millisecondi della pubblicazione). Non contiene la tappa né l'id dell'autore: la tappa si legge con il dettaglio. I campi si estraggono dal JSONB con una sola query, senza leggere né interpretare il contenuto delle tappe nell'applicazione. Le pubblicazioni del vecchio endpoint avevano la tappa scelta dal client e possono avere forme strane: `nome`, `luogo` e `data` mancanti sono stringhe vuote, e un elenco di squadre che non è un array conta 0.
- `GET /api/archivio/{tappaId}` — la tappa per intero, `{tappa, lega, autore, autoreId, ts}`; 404 se non è in archivio.
- `PUT /api/archivio/{tappaId}` — pubblica la tappa, o la ripubblica aggiornando la copia. **Non ha corpo**: la copia pubblica (lo snapshot) la costruisce il server dalla tappa che ha salvato, nella forma delle API delle tappe, quindi nessuno può pubblicare risultati inventati; un corpo eventuale si ignora (non si legge né si valida). Risponde 200 con `{tappa, lega, autore, autoreId, ts}`. La copia ha i dati che il server ha in quel momento: il client salva la tappa e poi la pubblica. Il vecchio `PUT /api/archivio`, con la tappa nel corpo, non esiste più (405).
  - **404** se la tappa non esiste, **403** se non è di una lega dell'utente (un ADMIN può pubblicare qualsiasi tappa), **409** se la tappa non è conclusa («concludila prima di pubblicarla in archivio»). I controlli vanno in quest'ordine: chi non è il proprietario non scopre se la tappa è conclusa.
  - L'autore è sempre il proprietario della lega, anche quando pubblica un ADMIN o quando si ripubblica: così lui e gli ADMIN possono sempre ritirare la pubblicazione.
  - Se una tappa pubblicata viene riaperta (non più conclusa), la copia pubblica resta quella dell'ultima pubblicazione: ripubblicare dà 409 finché la tappa non torna conclusa, mentre ritirare si può sempre.
- `DELETE /api/archivio/{tappaId}` — 204, ritira la pubblicazione (l'autore o un ADMIN).

Eliminare una tappa, la sua lega o il suo proprietario elimina anche la pubblicazione: lo fa il database, con una chiave esterna da `archivio_tappe.tappa_id` a `tappe(id)` con `ON DELETE CASCADE` (migrazione V2).

**Database già in uso: pubblicazioni orfane.** Prima della V2 le pubblicazioni di una tappa eliminata restavano in archivio. La V2 non le tocca e non fallisce per colpa loro: il vincolo è `NOT VALID`, cioè vale per le pubblicazioni nuove ma non controlla quelle già presenti, perché una migrazione non deve cancellare dati. Si trovano con:

```sql
SELECT a.tappa_id, a.lega_nome, a.pubblicato_il, u.email AS autore
FROM archivio_tappe a JOIN utenti u ON u.id = a.autore_id
WHERE NOT EXISTS (SELECT 1 FROM tappe t WHERE t.id = a.tappa_id)
ORDER BY a.pubblicato_il;
```

Se non servono più le cancella chi gestisce il database, e dopo si può convalidare il vincolo:

```sql
DELETE FROM archivio_tappe a WHERE NOT EXISTS (SELECT 1 FROM tappe t WHERE t.id = a.tappa_id);
ALTER TABLE archivio_tappe VALIDATE CONSTRAINT archivio_tappe_tappa_id_fkey;
```

## Sessioni e refresh token

Il JWT di accesso dura poco e il client lo rinnova con un refresh token tenuto in un cookie httpOnly: chi torna dopo giorni non deve rifare il login, e il logout revoca il refresh token.

- **JWT** — 30 minuti di default, da 5 a 1440 con `jwt.durata-minuti` (fuori da questo intervallo il server non parte: con pochi minuti il client rinnoverebbe quasi a ogni richiesta, perché rinnova in anticipo quando mancano meno di 2 minuti alla scadenza). Il client lo manda nell'header `Authorization: Bearer`; con il token vuoto, scaduto o alterato la risposta è 401.
- **Cookie** `hoop3x3_refresh` — `HttpOnly`, `SameSite=Lax`, `Path=/api/auth` (il browser lo rimanda solo agli endpoint di autenticazione), 30 giorni (`auth.refresh-giorni`). In produzione con HTTPS va impostato `AUTH_COOKIE_SECURE=true` in `env.properties` o come variabile d'ambiente (di default è `false`).
- **Database** — la tabella `refresh_tokens` conserva solo l'hash SHA-256 del token, mai il token in chiaro.

Endpoint:

- `POST /api/auth/register` e `POST /api/auth/login` — rispondono `{token, user}` e impostano il cookie di refresh.
- `POST /api/auth/refresh` — pubblico, senza Bearer: ruota il refresh token (il vecchio smette di valere), imposta il nuovo cookie e risponde `{token, user}` con un nuovo JWT. Cookie assente, sconosciuto (anche se già ruotato) o scaduto: 401 «Sessione scaduta: accedi di nuovo». Due refresh contemporanei con lo stesso cookie: uno vince (200), l'altro riceve 409 «Sessione già rinnovata da un'altra richiesta: riprova».
- `POST /api/auth/logout` — 204: cancella il cookie e la riga in tabella.

`register`, `login`, `refresh` e `logout` non passano dal `JwtFilter`: un `Authorization` presente nella richiesta, anche scaduto o non valido, viene ignorato e non impedisce di accedere, rinnovare la sessione o uscire. `refresh` e `logout` si autenticano solo con il cookie, il client non ha bisogno di mandare il Bearer.

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

## Limiti dell'API

Una richiesta che sfora un limite risponde con un errore e non salva nulla.

- **Richiesta** — al massimo 2 MB, anche senza `Content-Length`: oltre, 413. Dove l'API legge un corpo lo vuole in JSON: un altro tipo, per esempio un form, risponde 415.
- **Blocchi JSON di una tappa** (`squadre`, `gironi`, `partite`, `bracket`, `video`) — al massimo 1 MB ciascuno, misurato in byte UTF-8. Vale quando la tappa si salva in una lega (creazione, import, aggiunta, modifica); la pubblicazione in archivio non riceve nulla dal client e copia i blocchi già salvati, quindi ha gli stessi tetti.
- **Import di una lega** — al massimo 100 tappe, con id tutti diversi.
- **Leghe e tappe** — nome della lega fino a 120 caratteri; nome della tappa fino a 120, luogo fino a 160, data nel formato `aaaa-mm-gg` oppure vuota.
- **Anagrafe** — roster di una squadra fino a 12 giocatori; note di giocatori e squadre fino a 2000 caratteri.
- **Account** — email fino a 255 caratteri; alla registrazione la password ha da 8 caratteri a 72 byte in UTF-8 (una lettera accentata ne occupa 2, un emoji 4).
- **Coach AI** — da 1 a 60 messaggi per al massimo 100.000 caratteri, fino a 20 strumenti (50.000 caratteri): vedi la sezione Coach AI. In più, per utente, 20 richieste al minuto e 300 al giorno: vedi «Limiti di frequenza».

**Errori** — ogni errore dell'applicazione ha lo stesso corpo JSON, `{message, timestamp}`, qualunque `Accept` mandi il client: `message` è in italiano e senza dettagli interni (SQL e stack restano nei log), `timestamp` è la data e l'ora locali del server, senza fuso. Gli stati:

- **400** — richiesta non valida: JSON malformato, campo oltre un limite o non valido, identificatore non valido nel percorso, richiesta al Coach AI rifiutata. Il messaggio dice che cosa non va, di solito con il nome del campo.
- **401** — token mancante, scaduto o non valido; email o password sbagliate; refresh token assente, sconosciuto o scaduto.
- **403** — ruolo insufficiente, oppure risorsa di un altro utente.
- **404** — risorsa o percorso inesistente.
- **405** — metodo non consentito per quell'indirizzo.
- **409** — conflitto con dati già salvati (email già registrata, tappa con lo stesso id, vincolo del database), tappa non ancora conclusa che si prova a pubblicare in archivio, oppure sessione già rinnovata da un'altra richiesta.
- **413** — richiesta oltre 2 MB.
- **415** — corpo che non è JSON.
- **429** — troppe richieste: il limite di frequenza di login, registrazione, rinnovo del token e Coach AI, con `Retry-After` (vedi «Limiti di frequenza»). Per il Coach AI è 429 anche quando è Groq a limitare le richieste, con un altro messaggio e senza `Retry-After`.
- **502, 503** — solo Coach AI: Groq non risponde o risponde con un errore (502), la chiave manca sul server (503).
- **500** — errore imprevisto, con un messaggio generico.

Fanno eccezione le richieste respinte prima di Spring MVC, dal container o dal firewall di Spring Security (un indirizzo malformato: 400 con il corpo di Spring Boot `{timestamp, status, error, path}` oppure con la pagina di errore di Tomcat), e il 403 «Invalid CORS request», che è solo testo (vedi Deploy).

## Limiti di frequenza

Il server conta le richieste e, oltre il limite, risponde 429 senza farle arrivare al servizio (niente BCrypt, niente chiamata a Groq). A che cosa serve, e perché il controllo sta dove sta, è scritto nel commento di `LimiteRichiesteFilter`.

- **Login, registrazione e rinnovo del token** (`POST /api/auth/login`, `/api/auth/register` e `/api/auth/refresh`) — 10 richieste al minuto per indirizzo IP. Ogni endpoint ha il suo contatore: chi sbaglia dieci volte la password non resta senza il rinnovo della sessione.
- **Coach AI** (`POST /api/coach/chat`) — 20 richieste al minuto e 300 al giorno per utente (l'id dell'account, da qualunque indirizzo arrivi). Valgono tutte e due. La quota conta ogni richiesta autenticata alla chat, anche quelle che poi falliscono (per esempio con 400 o 413, perché il limite sta prima della validazione e del tetto dei 2 MB); non la consumano solo quelle respinte dal limite del minuto. Senza token risponde il 401 e niente si conta. Lo stato del Coach (`GET /api/coach/status`), il logout e le altre API non sono limitati.
- **Finestre fisse**, allineate all'orologio: il minuto finisce al secondo 0, il giorno a mezzanotte UTC (le 2 in Italia d'estate, l'1 d'inverno). Chi insiste oltre il limite non allunga l'attesa, ma a cavallo di due finestre si possono fare fino al doppio delle richieste in pochi secondi.

**Risposta oltre il limite** — 429 con lo stesso corpo `{message, timestamp}` degli altri errori e l'intestazione `Retry-After`, cioè i secondi fino all'inizio della finestra successiva: da 1 alla lunghezza della finestra, più al massimo 60 se l'orologio del server è tornato indietro di poco. Se è tornato indietro di molto (una macchina virtuale ripristinata, la data cambiata a mano) il contatore riparte da una finestra vuota, invece di tenere gli indirizzi bloccati per ore. Il messaggio dice l'attesa a parole:

```http
HTTP/1.1 429
Retry-After: 40
Content-Type: application/json

{"message":"Troppi tentativi di accesso: riprova tra 40 secondi","timestamp":"2026-10-06T10:00:20.123456"}
```

Gli altri messaggi sono «Troppe richieste di registrazione», «Troppi rinnovi della sessione», «Troppe richieste al Coach AI» e, per la quota del giorno, «Quota giornaliera del Coach AI esaurita: riprova tra 14 ore». Con il frontend su un'altra origine (`CORS_ORIGINS`) `Retry-After` è tra gli header che `CorsConfig` espone al JavaScript (`Access-Control-Expose-Headers`): il client legge quanto aspettare anche da lì, senza ricavarlo dal messaggio.

**Proprietà** — in `application.properties`, validate all'avvio (un valore sotto 1 ferma il server e dice quale è sbagliato):

- `limite.auth-al-minuto=10` — login, registrazione e rinnovo, per indirizzo;
- `limite.coach-al-minuto=20` e `limite.coach-al-giorno=300` — Coach AI, per utente.

Si cambiano in `env.properties` o con le variabili d'ambiente `LIMITE_AUTH_AL_MINUTO`, `LIMITE_COACH_AL_MINUTO` e `LIMITE_COACH_AL_GIORNO` (utile per provare il server con molte richieste dallo stesso computer). Il profilo di test li alza a 100000 al minuto e 1000000 al giorno; i test dei limiti scelgono da sé valori bassi e usano un orologio che si sposta a comando.

**Indirizzo del client e reverse proxy** — il limite per indirizzo usa `request.getRemoteAddr()`, l'indirizzo della connessione. Dietro un reverse proxy (nginx, un hosting...) è quello del proxy: **se non si fa altro, tutti gli utenti condividono un contatore solo** (10 accessi al minuto in tutto il sito) e il login si chiude a tutti. Chi pubblica il server dietro un proxy deve:

1. impostare `server.forward-headers-strategy=native` (in `env.properties` o come variabile d'ambiente `SERVER_FORWARD_HEADERS_STRATEGY=native`);
2. far mandare al proxy l'indirizzo del cliente in `X-Forwarded-For`, accodandolo a ciò che c'è già (nginx: `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`).

Con `native` Tomcat sostituisce l'indirizzo del proxy con quello di `X-Forwarded-For` solo se la richiesta arriva da un proxy di cui si fida (`server.tomcat.remoteip.internal-proxies`: di base gli indirizzi locali e privati; un proxy con un indirizzo pubblico va elencato lì) e prende l'ultimo indirizzo che non è un proxy fidato, cioè quello che ha visto il proxy: ciò che il cliente ha scritto prima non conta. Il backend non legge mai `X-Forwarded-For` da sé, perché chi parla con il server può scriverci quello che vuole e basterebbe un valore diverso a ogni richiesta per non incontrare mai il limite. Per lo stesso motivo `server.forward-headers-strategy=framework` non basta: Spring prende il primo indirizzo dell'intestazione, quello che scrive il cliente, a meno che il proxy non la sostituisca invece di accodarsi. `LimiteRichiesteProxyIT` prova questi comportamenti su un server vero.

**Più istanze del server** — i contatori stanno nella memoria del processo: si azzerano a ogni riavvio e, con più istanze, ognuna conta per sé, quindi il limite vero diventa quello scritto moltiplicato per il numero di istanze. Oggi l'istanza è una sola e basta così; con più istanze servirebbe un contatore condiviso (per esempio nel database o in Redis).

Il limite per indirizzo rallenta chi prova da un computer solo, non un attacco distribuito: chi ha molti indirizzi (una rete di computer, un intero prefisso IPv6) ha un contatore per ognuno.

## Log

L'applicazione scrive nei log (console) ciò che serve a capire un problema in produzione. Le righe sono in italiano e non contengono mai password, token o chiavi. I valori scelti da chi manda la richiesta (l'email del login e della registrazione, il percorso di un errore 500, il messaggio del database quando un vincolo è violato, il corpo di una risposta di Groq) passano da `LogSupport.perLog`: a capo, separatori di riga di Unicode (U+2028, U+2029) e caratteri di controllo si scrivono per esteso (`\r`, `\n`, `\u2028`...), così nessun valore può chiudere una riga e inventarne una sua, e il tentativo si vede.

- **Login fallito** (WARN) — `Login fallito per mario@x.it`: l'email normalizzata, mai la password. Un'email con CR o LF dentro non arriva fin lì: la validazione la rifiuta con 400.
- **Registrazione** (INFO) — `Nuovo utente registrato: mario@x.it (id ...)`.
- **Intervento di un ADMIN su dati di un altro utente** (INFO) — `Intervento ADMIN: admin@x.it (id ...): modifica lega <id> di proprietà dell'utente <id>`, oppure `eliminazione`. Solo per le modifiche e le eliminazioni di leghe, tappe, schede dell'anagrafe e pubblicazioni, e solo se la richiesta è andata oltre i controlli (una 404, 409 o 400 non lascia niente). Né le letture, per esempio un ADMIN che apre la lega di un altro, né i dati propri lasciano una riga. Contiene l'email dell'ADMIN e gli id, mai nomi scritti da altri utenti.
- **Limite di frequenza superato** (WARN) — `Limite di richieste superato: Troppi tentativi di accesso (massimo 10 al minuto), indirizzo 203.0.113.9`, oppure `... Troppe richieste al Coach AI (massimo 20 al minuto), utente <id>`. Una riga sola per indirizzo (o utente) e per finestra, non una per richiesta: chi insiste non riempie i log. Il 429 stesso non lascia altre righe.
- **Errori 500** (ERROR) — `Errore non gestito su GET /api/leghe`, con lo stack sotto: metodo e percorso della richiesta (senza la query, che può contenere dati personali). Gli errori di Groq li scrive una volta sola il Coach AI (vedi la sezione omonima).
- **Seeder** — ogni volta che non creano i dati lo dicono, con il motivo: INFO se è la configurazione normale (`Admin non creato: ADMIN_EMAIL non è impostata`, `... esiste già un utente con l'email di ADMIN_EMAIL`, `Seed demo saltato: SEED_DEMO non è true`, `Seed demo saltato: già eseguito`), WARN se la configurazione non permette ciò che chi l'ha scritta si aspetta (password debole o mancante, `ADMIN_PASSWORD` impostata con `ADMIN_EMAIL` vuota, `SEED_DEMO` acceso senza `ADMIN_EMAIL` o senza l'admin nel database).

## Struttura

```
env.properties.example          # segreti: copiare in env.properties
src/main/resources/db/migration/  # migrazioni Flyway (V1 = schema iniziale, V2 = archivio legato alle tappe, V3 = segno dei seed eseguiti), applicate all'avvio
src/main/java/com/hoop3x3/backend/
├── controllers/  # REST (auth, utenti, leghe, tappe, anagrafe, archivio, coach)
├── dto/          # record con validazione Bean Validation
├── entities/     # JPA: Utente, RefreshToken, Lega, Tappa (+Regole), AnagrafeGiocatore/Squadra, ArchivioTappa, SeedEseguito
├── exceptions/   # eccezioni tipizzate + ExceptionsHandler (corpo uniforme {message, timestamp})
├── repositories/ # Spring Data JPA
├── runners/      # DataSeeder (admin iniziale), DemoSeeder (dati di prova da resources/seed/estathe25.json, una volta sola)
├── security/     # SecurityConfig, JwtFilter, JWTtools, JwtProperties (secret e durata del JWT, validati all'avvio), AuthCookies, CorsConfig, JsonAuthEntryPoint, LimiteDimensioneFilter (413 oltre 2 MB), LimiteRichiesteFilter (429 oltre i limiti di frequenza) con LimiteRichieste (il contatore) e LimiteRichiesteProperties
└── services/     # logica: proprietà (AccessGuard), JSON delle tappe, proxy Groq, refresh token (RefreshTokenService)
```

## Dati di prova

Con `SEED_DEMO=true` (e `ADMIN_EMAIL` di un admin che esiste) il primo avvio carica il circuito Estathé 2025 (`resources/seed/estathe25.json`) intestandolo all'admin. Si carica **una volta sola**: alla fine il seeder scrive il segno `demo` nella tabella `seed_eseguiti` (migrazione V3) e gli avvii successivi lo riconoscono da lì, anche se nel frattempo hai eliminato la lega demo (perché serve il segno lo spiega il commento di `SeedEseguito`). Il segno è il nome dell'operazione e non dipende dai dati inseriti, quindi vale anche se un giorno i nomi dei dati demo cambiano.

Un database seminato prima del segno non ce l'ha, ma ha ancora la prima tappa demo: al primo avvio **con `SEED_DEMO=true`** il seeder la riconosce, non inserisce niente e scrive il segno (con `SEED_DEMO=false` il seeder non fa niente e il segno non arriva). Quindi **prima di eliminare la lega demo, avvia una volta con `SEED_DEMO=true`**.

Per rifare il seed su un database che l'ha già eseguito si cancellano il segno **e la lega demo** (dall'app, come ogni lega dell'admin): finché c'è la sua prima tappa il seed risulta fatto, e il seeder riscrive il segno. I giocatori e le squadre demo restano nell'anagrafe: cancellali se non li vuoi doppi. Il segno si cancella così:

```sql
DELETE FROM seed_eseguiti WHERE nome = 'demo';
```

Se invece la lega demo era già stata eliminata prima di questa versione, nel database non resta niente da cui riconoscere il seed: con `SEED_DEMO=true` il primo avvio lo rifarebbe una volta (poi il segno lo protegge). Per evitarlo avvia la prima volta con `SEED_DEMO=false`, così la migrazione crea la tabella senza che il seed parta, e scrivi il segno a mano prima di riaccendere `SEED_DEMO`:

```sql
INSERT INTO seed_eseguiti (nome, eseguito_il) VALUES ('demo', now());
```
