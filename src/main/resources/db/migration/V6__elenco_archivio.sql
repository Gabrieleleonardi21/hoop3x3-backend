-- ============================================================================
--  Hoop 3x3 — V6: l'elenco dell'archivio legge delle colonne, non il JSONB
--  L'elenco pubblico (GET /api/archivio) mostra di ogni pubblicazione nome, luogo, data e numero di squadre della tappa.
--  Fino alla V5 li estraeva dal JSONB del contenuto a ogni richiesta: PostgreSQL decomprimeva ogni snapshot (anche di
--  centinaia di KB) per leggerne quattro campi, e l'elenco è pubblico e senza cache. Ora i quattro campi sono colonne,
--  scritte alla pubblicazione (ArchivioService.pubblica), e l'elenco non tocca più il contenuto.
--
--  Le righe già presenti si riempiono qui una volta sola, con le stesse regole che usava la query dell'elenco: le
--  pubblicazioni del vecchio endpoint avevano la tappa scelta dal client e possono avere forme strane, quindi nome, luogo
--  e data mancanti diventano vuoti, un valore più lungo della colonna si tronca (left) invece di far fallire la migrazione,
--  e un elenco di squadre che non è un array conta 0. Un contenuto che non è un oggetto (un array, un testo) dà null a
--  ogni ->> e quindi vuoti e 0.
--
--  L'indice sul giocatore del roster: eliminare un giocatore cerca le squadre che lo contengono (findByRosterContains) e la
--  tabella ponte aveva solo la chiave primaria (squadra_id, posizione), quindi la ricerca leggeva tutta la tabella.
-- ============================================================================

ALTER TABLE archivio_tappe
    ADD COLUMN nome           VARCHAR(120) NOT NULL DEFAULT '',
    ADD COLUMN luogo          VARCHAR(160) NOT NULL DEFAULT '',
    ADD COLUMN data           VARCHAR(10)  NOT NULL DEFAULT '',
    ADD COLUMN numero_squadre INTEGER      NOT NULL DEFAULT 0;

UPDATE archivio_tappe SET
    nome  = coalesce(left(contenuto ->> 'nome', 120), ''),
    luogo = coalesce(left(contenuto ->> 'luogo', 160), ''),
    data  = coalesce(left(contenuto ->> 'data', 10), ''),
    numero_squadre = CASE WHEN jsonb_typeof(contenuto -> 'squadre') = 'array'
                          THEN jsonb_array_length(contenuto -> 'squadre') ELSE 0 END;

CREATE INDEX IF NOT EXISTS idx_anagrafe_squadre_roster_giocatore ON anagrafe_squadre_roster(giocatore_id);
