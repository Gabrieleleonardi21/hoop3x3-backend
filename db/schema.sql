-- ============================================================================
--  Hoop 3x3 — schema PostgreSQL
--  Da eseguire in pgAdmin (Query Tool) sul database `hoop3x3`, oppure:
--    createdb hoop3x3 && psql -d hoop3x3 -f backend/db/schema.sql
--  Hibernate gira con ddl-auto=validate: controlla che le entity combacino
--  con queste tabelle e si rifiuta di partire se qualcosa non torna.
-- ============================================================================

-- ── Utenti ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS utenti (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password      VARCHAR(255) NOT NULL,           -- hash BCrypt, mai in chiaro
    nome          VARCHAR(80)  NOT NULL,           -- nome utente mostrato nell'app
    ruolo         VARCHAR(20)  NOT NULL,           -- USER | ADMIN
    creato_il     TIMESTAMP    NOT NULL,
    modificato_il TIMESTAMP    NOT NULL
);

-- ── Refresh token: sessioni lunghe senza ripetere il login ──────────────────
-- In tabella c'è solo l'hash SHA-256: il token in chiaro vive nel cookie httpOnly del browser.
-- Ogni token vale per un solo rinnovo (rotazione) e scade dopo auth.refresh-giorni.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id            UUID PRIMARY KEY,
    utente_id     UUID NOT NULL REFERENCES utenti(id) ON DELETE CASCADE,
    token_hash    VARCHAR(64) NOT NULL UNIQUE,
    scade_il      TIMESTAMP NOT NULL,
    creato_il     TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_utente ON refresh_tokens(utente_id);

-- ── Leghe: contenitore di tappe, ogni lega ha un proprietario ───────────────
CREATE TABLE IF NOT EXISTS leghe (
    id            UUID PRIMARY KEY,
    nome          VARCHAR(120) NOT NULL,
    owner_id      UUID NOT NULL REFERENCES utenti(id) ON DELETE CASCADE,
    creato_il     TIMESTAMP NOT NULL,
    modificato_il TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_leghe_owner ON leghe(owner_id);

-- ── Tappe ───────────────────────────────────────────────────────────────────
-- I dati "di gioco" (squadre iscritte, gironi, partite con statistiche ed
-- eventi, bracket, video) sono JSONB: il motore torneo del frontend li legge
-- e scrive sempre come blocco unico. Le regole FIBA e i dati anagrafici della
-- tappa sono colonne normali così restano filtrabili.
CREATE TABLE IF NOT EXISTS tappe (
    id            UUID PRIMARY KEY,                -- generato dal client (crypto.randomUUID)
    lega_id       UUID NOT NULL REFERENCES leghe(id) ON DELETE CASCADE,
    posizione     INTEGER NOT NULL DEFAULT 0,      -- ordine nella lega
    nome          VARCHAR(120) NOT NULL,
    luogo         VARCHAR(160) NOT NULL DEFAULT '',
    data          VARCHAR(10)  NOT NULL DEFAULT '', -- ISO yyyy-mm-dd o vuota (input date del frontend)
    n_gironi      INTEGER NOT NULL DEFAULT 1,
    conclusa      BOOLEAN NOT NULL DEFAULT FALSE,
    -- regole FIBA 3x3 (@Embeddable Regole)
    regole_target INTEGER NOT NULL DEFAULT 21,
    regole_durata INTEGER NOT NULL DEFAULT 10,
    regole_ot     INTEGER NOT NULL DEFAULT 2,
    regole_shot   INTEGER NOT NULL DEFAULT 12,
    -- contenuto di gioco
    squadre       JSONB NOT NULL DEFAULT '[]',
    gironi        JSONB,                            -- NULL = non ancora sorteggiati
    partite       JSONB NOT NULL DEFAULT '[]',
    bracket       JSONB,                            -- NULL = fase finale non generata
    video         JSONB NOT NULL DEFAULT '[]',
    creato_il     TIMESTAMP NOT NULL,
    modificato_il TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tappe_lega ON tappe(lega_id, posizione);

-- ── Anagrafe circuito (condivisa tra tutti gli utenti) ──────────────────────
CREATE TABLE IF NOT EXISTS anagrafe_giocatori (
    id            UUID PRIMARY KEY,
    nome          VARCHAR(80)  NOT NULL,
    cognome       VARCHAR(80)  NOT NULL,
    soprannome    VARCHAR(80)  NOT NULL DEFAULT '',
    nascita       VARCHAR(10)  NOT NULL DEFAULT '',
    citta         VARCHAR(120) NOT NULL DEFAULT '',
    nazionalita   VARCHAR(80)  NOT NULL DEFAULT '',
    altezza       VARCHAR(10)  NOT NULL DEFAULT '',
    peso          VARCHAR(10)  NOT NULL DEFAULT '',
    ruolo         VARCHAR(40)  NOT NULL DEFAULT '',
    numero        VARCHAR(5)   NOT NULL DEFAULT '',
    squadra       VARCHAR(120) NOT NULL DEFAULT '', -- nome libero, non FK: il giocatore può essere svincolato
    esperienza    VARCHAR(40)  NOT NULL DEFAULT '',
    note          TEXT         NOT NULL DEFAULT '',
    autore_id     UUID NOT NULL REFERENCES utenti(id) ON DELETE CASCADE,
    creato_il     TIMESTAMP NOT NULL,
    modificato_il TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_anagrafe_giocatori_cognome ON anagrafe_giocatori(cognome, nome);

CREATE TABLE IF NOT EXISTS anagrafe_squadre (
    id            UUID PRIMARY KEY,
    nome          VARCHAR(120) NOT NULL,
    citta         VARCHAR(120) NOT NULL DEFAULT '',
    anno          VARCHAR(4)   NOT NULL DEFAULT '',
    rank          VARCHAR(10)  NOT NULL DEFAULT '',  -- punti ranking circuito (stringa: il form lo lascia vuoto)
    referente     VARCHAR(120) NOT NULL DEFAULT '',
    logo          VARCHAR(500) NOT NULL DEFAULT '',
    website       VARCHAR(500) NOT NULL DEFAULT '',
    instagram     VARCHAR(500) NOT NULL DEFAULT '',
    note          TEXT         NOT NULL DEFAULT '',
    autore_id     UUID NOT NULL REFERENCES utenti(id) ON DELETE CASCADE,
    creato_il     TIMESTAMP NOT NULL,
    modificato_il TIMESTAMP NOT NULL
);

-- Roster: relazione many-to-many squadra ↔ giocatore, con ordine di inserimento
CREATE TABLE IF NOT EXISTS anagrafe_squadre_roster (
    squadra_id    UUID NOT NULL REFERENCES anagrafe_squadre(id) ON DELETE CASCADE,
    giocatore_id  UUID NOT NULL REFERENCES anagrafe_giocatori(id) ON DELETE CASCADE,
    posizione     INTEGER NOT NULL,
    PRIMARY KEY (squadra_id, posizione)
);

-- ── Archivio circuito: snapshot delle tappe concluse e pubblicate ────────────
-- La chiave è l'id della tappa: ripubblicare (es. dopo aver aggiunto un video)
-- sovrascrive lo snapshot. Il contenuto è la tappa completa in JSONB.
CREATE TABLE IF NOT EXISTS archivio_tappe (
    tappa_id      UUID PRIMARY KEY,
    lega_nome     VARCHAR(120) NOT NULL,
    autore_id     UUID NOT NULL REFERENCES utenti(id) ON DELETE CASCADE,
    contenuto     JSONB NOT NULL,
    pubblicato_il TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_archivio_pubblicato ON archivio_tappe(pubblicato_il DESC);
