# Hoop 3x3 — Backend

API REST del gestionale [Hoop 3x3](https://github.com/Gabrieleleonardi21/Hoops-3x3): Spring Boot 4 (Java 17+), Spring Security + JWT, JPA/Hibernate, PostgreSQL. Porta `3001`.

## Avvio

**1. Database** — crea il DB `hoop3x3` ed esegui `db/schema.sql` (in pgAdmin: Query Tool → apri il file → Esegui), oppure:

```bash
createdb hoop3x3 && psql -d hoop3x3 -f db/schema.sql
```

**2. Configurazione** — copia `env.properties.example` in `env.properties` (ignorato da git) e compila password DB, `JWT_SECRET` e, facoltativa, `GROQ_API_KEY` per il Coach AI.

**3. Server**

```bash
mvn spring-boot:run
```

Il frontend in sviluppo inoltra `/api` verso `http://localhost:3001` tramite il proxy di Vite.

## Coach AI

Proxy verso [Groq](https://console.groq.com/) (`POST /api/coach/chat`, autenticato): la chiave resta sul server. Modello di default `openai/gpt-oss-120b`, sovrascrivibile con `GROQ_MODEL` in `env.properties`. Senza chiave il Coach è disattivato e il resto dell'app funziona.

## Dati di prova

Con `SEED_DEMO=true` il primo avvio carica il circuito Estathé 2025 (`resources/seed/estathe25.json`) intestandolo all'admin; gli avvii successivi non lo duplicano.
