-- ============================================================================
--  Hoop 3x3 — V3: il segno dei seed già eseguiti
--  Il seed demo (SEED_DEMO=true) inserisce una volta sola i dati di prova. Per riconoscere che era già stato fatto il
--  seeder guardava la prima tappa demo, ma eliminando la lega demo spariscono le sue tappe e l'archivio mentre i
--  giocatori e le squadre restano (hanno id generati dal database): al riavvio il seed non si riconosceva più e li
--  inseriva una seconda volta.
--  Ora un seed che finisce lascia qui il suo segno (per ora solo «demo»), che dura quanto il database e non dipende dai dati
--  inseriti: né dai loro nomi né dal fatto che esistano ancora. Per rifare il seed demo su un database che ha già il segno
--  si cancella la riga (e i dati demo vecchi, se non si vogliono doppi):
--      DELETE FROM seed_eseguiti WHERE nome = 'demo';
-- ============================================================================

CREATE TABLE seed_eseguiti (
    nome        VARCHAR(80) PRIMARY KEY,   -- il seed eseguito: «demo»
    eseguito_il TIMESTAMP   NOT NULL
);
