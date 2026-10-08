-- ============================================================================
--  Hoop 3x3 — V7: gli indirizzi web delle squadre fino a 2048 caratteri
--  Logo, sito e Instagram di una squadra ora sono validati come indirizzi (http://, https:// o un percorso del sito, vedi
--  validation/IndirizzoWeb) e possono essere lunghi fino a 2048 caratteri, il limite che i browser garantiscono per un URL:
--  le colonne erano da 500. Allargare una colonna VARCHAR non riscrive le righe e non tocca i dati.
-- ============================================================================

ALTER TABLE anagrafe_squadre
    ALTER COLUMN logo      TYPE VARCHAR(2048),
    ALTER COLUMN website   TYPE VARCHAR(2048),
    ALTER COLUMN instagram TYPE VARCHAR(2048);
