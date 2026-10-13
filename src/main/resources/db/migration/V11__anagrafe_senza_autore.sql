-- ============================================================================
--  Hoop 3x3 — V11: le schede dell'anagrafe sopravvivono al loro autore
--  Con la V1 eliminare un utente cancellava anche i giocatori e le squadre dell'anagrafe che aveva inserito
--  (ON DELETE CASCADE). Ma le schede sono dati del circuito su persone terze, non dati dell'utente: stanno nei roster delle
--  squadre degli altri e nelle loro tappe, e la cascata sul roster toglieva il giocatore anche dalle squadre altrui. Ora,
--  come i campetti (V8), restano con autore_id NULL e da allora le modifica solo un ADMIN, perché nessun utente è il
--  proprietario di un autore nullo. Le righe esistenti non cambiano.
--  I nomi dei vincoli sono quelli che PostgreSQL ha dato ai REFERENCES in linea della V1.
-- ============================================================================

ALTER TABLE anagrafe_giocatori ALTER COLUMN autore_id DROP NOT NULL;
ALTER TABLE anagrafe_giocatori DROP CONSTRAINT anagrafe_giocatori_autore_id_fkey;
ALTER TABLE anagrafe_giocatori ADD CONSTRAINT anagrafe_giocatori_autore_id_fkey
    FOREIGN KEY (autore_id) REFERENCES utenti(id) ON DELETE SET NULL;

ALTER TABLE anagrafe_squadre ALTER COLUMN autore_id DROP NOT NULL;
ALTER TABLE anagrafe_squadre DROP CONSTRAINT anagrafe_squadre_autore_id_fkey;
ALTER TABLE anagrafe_squadre ADD CONSTRAINT anagrafe_squadre_autore_id_fkey
    FOREIGN KEY (autore_id) REFERENCES utenti(id) ON DELETE SET NULL;
