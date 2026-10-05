-- ============================================================================
--  Hoop 3x3 — V2: le pubblicazioni dell'archivio seguono le loro tappe
--  Una pubblicazione (archivio_tappe) è la copia pubblica di una tappa. Con V1 non era legata alla tappa: eliminando la
--  tappa, la sua lega o il suo proprietario la pubblicazione restava in archivio, orfana e non più ritirabile dall'app.
--  Ora la elimina il database insieme alla tappa (ON DELETE CASCADE): nessun codice dei servizi da ricordare.
--
--  NOT VALID: il vincolo vale per le pubblicazioni nuove e per le eliminazioni da ora in poi, ma PostgreSQL non controlla
--  le righe già presenti. Un database già in uso può avere pubblicazioni orfane (di tappe eliminate con V1): con un
--  vincolo convalidato questa migrazione fallirebbe e il server non partirebbe, e cancellarle da qui toglierebbe dati
--  senza che nessuno l'abbia deciso. Le orfane restano dove sono. Si trovano con
--      SELECT a.tappa_id, a.lega_nome, a.pubblicato_il, u.email AS autore
--      FROM archivio_tappe a JOIN utenti u ON u.id = a.autore_id
--      WHERE NOT EXISTS (SELECT 1 FROM tappe t WHERE t.id = a.tappa_id);
--  e, dopo averle cancellate, il vincolo si convalida con
--      ALTER TABLE archivio_tappe VALIDATE CONSTRAINT archivio_tappe_tappa_id_fkey;
-- ============================================================================

ALTER TABLE archivio_tappe
    ADD CONSTRAINT archivio_tappe_tappa_id_fkey
    FOREIGN KEY (tappa_id) REFERENCES tappe(id) ON DELETE CASCADE NOT VALID;
