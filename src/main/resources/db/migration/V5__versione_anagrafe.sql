-- ============================================================================
--  Hoop 3x3 — V5: la versione delle schede dell'anagrafe
--  Come per le tappe (V4): salvare una scheda (giocatore o squadra) la sostituisce per intero, e due dispositivi dello
--  stesso autore, o l'autore e un ADMIN, aperti sulla stessa scheda si sovrascrivevano in silenzio. Ora ogni scheda ha un
--  numero di versione che Hibernate aumenta a ogni salvataggio che la cambia; il client può rimandarlo con la PUT e, se non
--  è più quello del database, il salvataggio è rifiutato (409) invece di sovrascrivere il lavoro dell'altro.
--
--  DEFAULT 0: le schede già presenti partono dalla versione 0, come una scheda nuova (con NOT NULL e senza un valore
--  predefinito la migrazione fallirebbe su un database che ha già delle schede), e un inserimento che non nomina la colonna
--  (i test con JDBC) non deve conoscerla.
-- ============================================================================

ALTER TABLE anagrafe_giocatori ADD COLUMN versione BIGINT NOT NULL DEFAULT 0;
ALTER TABLE anagrafe_squadre ADD COLUMN versione BIGINT NOT NULL DEFAULT 0;
