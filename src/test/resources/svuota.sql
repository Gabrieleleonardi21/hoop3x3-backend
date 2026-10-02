-- Svuota tutte le tabelle prima di ogni test di integrazione (vedi TestDiIntegrazione).
-- Il controllo protegge i dati veri: il nome del database deve contenere «test».
-- Controllo e TRUNCATE stanno nello stesso blocco: se il controllo fallisce la TRUNCATE non parte,
-- anche lanciando lo script a mano con psql, che dopo un errore passerebbe all'istruzione successiva.
DO $$
BEGIN
  IF current_database() NOT LIKE '%test%' THEN
    RAISE EXCEPTION 'svuota.sql gira solo su un database di prova, non su %', current_database();
  END IF;
  TRUNCATE utenti, refresh_tokens, leghe, tappe, anagrafe_giocatori, anagrafe_squadre, anagrafe_squadre_roster, archivio_tappe CASCADE;
END $$;
