-- Svuota tutte le tabelle prima di ogni test di integrazione (vedi TestDiIntegrazione).
-- Viene eseguito come un'unica istruzione: se il controllo fallisce, la TRUNCATE non parte.
-- Il controllo protegge i dati veri: il nome del database deve contenere «test».
DO $$
BEGIN
  IF current_database() NOT LIKE '%test%' THEN
    RAISE EXCEPTION 'svuota.sql gira solo su un database di prova, non su %', current_database();
  END IF;
END $$;
TRUNCATE utenti, refresh_tokens, leghe, tappe, anagrafe_giocatori, anagrafe_squadre, anagrafe_squadre_roster, archivio_tappe CASCADE;
