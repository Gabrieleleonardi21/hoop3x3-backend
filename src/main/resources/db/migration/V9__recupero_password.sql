-- ============================================================================
--  Hoop 3x3 — V9: i token del recupero password
--  Chi ha dimenticato la password riceve per email un link con un token casuale (32 byte, Base64 URL): in tabella c'è solo
--  il suo SHA-256 in esadecimale, come per i refresh token (V1), quindi chi legge il database non può usare i link.
--  Un token vale 30 minuti (scadenza) e una volta sola: usato_il lo segna quando la password viene reimpostata, e anche
--  quando un token più nuovo dello stesso utente lo sostituisce. NULL = ancora utilizzabile.
--  creato_il serve anche al limite di 3 richieste ogni 15 minuti per email, che si conta su queste righe.
--  Un utente eliminato porta con sé i suoi token (ON DELETE CASCADE).
-- ============================================================================

CREATE TABLE token_recupero_password (
    id          UUID PRIMARY KEY,
    utente_id   UUID NOT NULL REFERENCES utenti(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,                 -- SHA-256 esadecimale del token, mai il token in chiaro
    scadenza    TIMESTAMP NOT NULL,
    usato_il    TIMESTAMP,
    creato_il   TIMESTAMP NOT NULL
);
CREATE INDEX idx_token_recupero_password_utente ON token_recupero_password(utente_id);
