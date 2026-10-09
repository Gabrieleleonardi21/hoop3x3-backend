-- ============================================================================
--  Hoop 3x3 — V8: i campetti geolocalizzati
--  I campetti da basket che l'app mostra su una mappa: chiunque li legge, chi ha un account li aggiunge, l'autore o un
--  ADMIN li corregge. Hanno coordinate vere (lat e lng, con un CHECK sugli intervalli: la validazione del DTO risponde 400
--  prima, il CHECK è la rete di sicurezza), una versione per il blocco ottimistico come le schede dell'anagrafe (V5) e le
--  date come le altre tabelle.
--
--  autore_id è ON DELETE SET NULL, a differenza dell'anagrafe: un campetto è un dato del territorio e sopravvive a chi
--  l'ha inserito. Senza autore lo modifica solo un ADMIN.
--
--  fonte e fonte_id servono ai campetti importati da una fonte esterna (NULL per quelli creati dall'app): l'indice unico
--  su (fonte, fonte_id), solo dove fonte non è NULL, fa sì che un import ripetuto non crei doppioni.
--
--  Indici: (lat, lng) per la ricerca per raggio (un riquadro di coordinate in SQL, poi la distanza vera in Java) e
--  lower(citta) per la ricerca per testo senza distinzione di maiuscole.
-- ============================================================================

CREATE TABLE campetti (
    id            UUID PRIMARY KEY,
    nome          VARCHAR(120) NOT NULL,
    indirizzo     VARCHAR(160) NOT NULL DEFAULT '',
    citta         VARCHAR(120) NOT NULL DEFAULT '',
    lat           DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng           DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180 AND 180),
    tipo          VARCHAR(16)  NOT NULL DEFAULT 'campetto' CHECK (tipo IN ('campetto', 'palestra', 'arena')),
    superficie    VARCHAR(16)  NOT NULL,                     -- Asfalto | Cemento | Sintetico | Altro
    canestri      SMALLINT     NOT NULL CHECK (canestri BETWEEN 1 AND 8),
    illuminato    BOOLEAN      NOT NULL DEFAULT FALSE,
    coperto       BOOLEAN      NOT NULL DEFAULT FALSE,
    gratuito      BOOLEAN      NOT NULL DEFAULT FALSE,
    retine        BOOLEAN      NOT NULL DEFAULT FALSE,
    linee         BOOLEAN      NOT NULL DEFAULT FALSE,
    fontanella    BOOLEAN      NOT NULL DEFAULT FALSE,
    stato         VARCHAR(16)  NOT NULL,                     -- buono | discreto | da sistemare
    note          TEXT         NOT NULL DEFAULT '',
    autore_id     UUID REFERENCES utenti(id) ON DELETE SET NULL,
    fonte         VARCHAR(32),                               -- NULL = creato dall'app
    fonte_id      VARCHAR(120),
    versione      BIGINT       NOT NULL DEFAULT 0,
    creato_il     TIMESTAMP    NOT NULL,
    modificato_il TIMESTAMP    NOT NULL
);
CREATE INDEX idx_campetti_posizione ON campetti(lat, lng);
CREATE INDEX idx_campetti_citta ON campetti(lower(citta));
CREATE UNIQUE INDEX idx_campetti_fonte ON campetti(fonte, fonte_id) WHERE fonte IS NOT NULL;
