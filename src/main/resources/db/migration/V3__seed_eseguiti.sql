-- ============================================================================
--  Hoop 3x3 — V3: il segno dei seed già eseguiti
--  Il seed demo (SEED_DEMO=true) inserisce una volta sola i dati di prova, e un seed che finisce lascia qui il suo segno
--  (per ora solo «demo»): dura quanto il database e non dipende dai dati inseriti, né dai loro nomi né dal fatto che
--  esistano ancora. Perché serve: vedi il commento dell'entity SeedEseguito.
--  Per rifare il seed demo su un database che ha già il segno si cancellano il segno e la lega demo: finché c'è la sua
--  prima tappa il seed risulta fatto, e il seeder riscrive il segno. Giocatori e squadre demo restano: si cancellano se
--  non si vogliono doppi.
--      DELETE FROM seed_eseguiti WHERE nome = 'demo';
-- ============================================================================

CREATE TABLE seed_eseguiti (
    nome        VARCHAR(80) PRIMARY KEY,   -- il seed eseguito: «demo»
    eseguito_il TIMESTAMP   NOT NULL
);
