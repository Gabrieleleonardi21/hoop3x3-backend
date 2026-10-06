-- ============================================================================
--  Hoop 3x3 — V4: la versione delle tappe
--  Il salvataggio di una tappa sostituisce la tappa intera, partite comprese: due dispositivi (o due schede) aperti sulla
--  stessa tappa si sovrascrivevano in silenzio, e l'ultimo a salvare cancellava il lavoro dell'altro. Ora ogni tappa ha un
--  numero di versione: il server lo aumenta quando un salvataggio cambia la tappa, il client rimanda quello che ha letto
--  e, se non è più quello del database, la tappa è cambiata altrove e il salvataggio è rifiutato invece di sovrascriverla.
--
--  DEFAULT 0: le tappe già presenti partono dalla versione 0, come una tappa nuova (con NOT NULL e senza un valore
--  predefinito la migrazione fallirebbe su un database che ha già delle tappe), e un inserimento che non nomina la colonna
--  (un import a mano, i test con JDBC) non deve conoscerla.
-- ============================================================================

ALTER TABLE tappe ADD COLUMN versione BIGINT NOT NULL DEFAULT 0;
