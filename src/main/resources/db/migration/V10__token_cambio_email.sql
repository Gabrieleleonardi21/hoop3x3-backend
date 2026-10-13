-- ============================================================================
--  Hoop 3x3 — V10: i token del cambio email
--  Chi cambia l'email dal profilo riceve un link al NUOVO indirizzo, e l'email dell'account cambia solo quando lo apre: un
--  errore di battitura non lascia l'account con un indirizzo che nessuno legge (e senza recupero password).
--  L'email nuova sta qui, nel token, e non in utenti: fino alla conferma l'account resta quello di prima, il login vale con
--  l'email vecchia e nessun altro vede l'indirizzo nuovo come registrato.
--  Come i token del recupero password (V9): in tabella c'è solo lo SHA-256 del token in esadecimale, quindi chi legge il
--  database non può usare i link; un token vale 30 minuti (scadenza) e una volta sola (usato_il, segnato anche quando un
--  token più nuovo dello stesso utente lo sostituisce; NULL = ancora utilizzabile). creato_il serve al limite di 3 richieste
--  ogni 15 minuti per account. Un utente eliminato porta con sé i suoi token (ON DELETE CASCADE).
-- ============================================================================

CREATE TABLE token_cambio_email (
    id           UUID PRIMARY KEY,
    utente_id    UUID NOT NULL REFERENCES utenti(id) ON DELETE CASCADE,
    email_nuova  VARCHAR(255) NOT NULL,                       -- già normalizzata (minuscole, senza spazi), come utenti.email
    token_hash   VARCHAR(64) NOT NULL UNIQUE,                 -- SHA-256 esadecimale del token, mai il token in chiaro
    scadenza     TIMESTAMP NOT NULL,
    usato_il     TIMESTAMP,
    creato_il    TIMESTAMP NOT NULL
);
CREATE INDEX idx_token_cambio_email_utente ON token_cambio_email(utente_id);
