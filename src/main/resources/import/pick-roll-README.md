# Campetti importati da Pick-Roll

I campetti con `fonte = 'pick-roll'` nella tabella `campetti` vengono dall'app Pick-Roll, con il permesso del suo proprietario.

- **Fonte**: l'app Pick-Roll (campi da basket geolocalizzati), letta con l'account di chi ha fatto l'estrazione.
- **Permesso**: verbale, dato a voce dal proprietario di Pick-Roll il **9 ottobre 2026**, a chi gestisce HOOP 3X3, per riusare i dati dei campi (non degli utenti) dentro HOOP 3X3. Non copre la pubblicazione del file: **il file dei dati non entra nel repository** e sta sulla macchina di chi importa (su Render si copia una volta e si cancella dopo l'import, vedi il README principale).
- **Attribuzione** (da mostrare dove compaiono i campetti importati): **«Campetti: dati di Pick-Roll»**.
- **Chi li possiede nell'app**: i campetti importati sono intestati all'admin (`autore_id` = l'admin di `ADMIN_EMAIL`), quindi li modifica solo un ADMIN: così non divergono dalla fonte, e un import ripetuto li riallinea.

## Formato del file

Un array JSON di oggetti, uno per campo. Lo produce lo strumento di estrazione, che sta fuori da git; questo è il contratto tra lui e il comando `--importa-campetti` (`runners/ImportCampetti`):

```json
[
  {
    "fonteId": "abc123",
    "nome": "Campo Testaccio",
    "indirizzo": "Via Galvani",
    "citta": "Roma",
    "lat": 41.8765,
    "lng": 12.4775,
    "tipo": "campetto",
    "superficie": "Asfalto",
    "canestri": 2,
    "illuminato": true,
    "coperto": false,
    "gratuito": true,
    "retine": true,
    "linee": true,
    "fontanella": false,
    "stato": "buono",
    "note": ""
  }
]
```

| Campo del file | Dove finisce | Se manca |
|---|---|---|
| `fonteId` (id del campo in Pick-Roll) | `campetti.fonte_id`, con `fonte = 'pick-roll'`: è la chiave che evita i doppioni e permette l'aggiornamento | riga scartata |
| `nome` | `nome` (1-120 caratteri) | riga scartata |
| `indirizzo` | `indirizzo` (fino a 160) | `""` |
| `citta` | `citta` (fino a 120) | `""` |
| `lat`, `lng` | `lat`, `lng` (-90..90, -180..180) | riga scartata; fuori intervallo: riga scartata |
| `tipo` (`campetto`, `palestra`, `arena`) | solo `campetto` entra (D10: HOOP 3X3 è sui campi all'aperto) | riga scartata; `palestra` e `arena`: riga scartata |
| `superficie` (`Asfalto`, `Cemento`, `Sintetico`, `Altro`) | `superficie` | `Altro` |
| `canestri` (1..8) | `canestri` | `2` |
| `illuminato`, `coperto`, `gratuito`, `retine`, `linee`, `fontanella` | le sei colonne booleane | `false` |
| `stato` (`buono`, `discreto`, `da sistemare`) | `stato` | `discreto` |
| `note` | `note` (fino a 2000) | `""` |

Ogni riga passa dalla stessa validazione di `POST /api/campetti` (`CampettoRequestDTO`): una riga che non la supera viene scartata, con il motivo nel log, e le altre entrano. Qualsiasi altro campo del file viene ignorato.

## Che cosa si scarta di Pick-Roll

Tutto ciò che non descrive il campo: **foto, valutazioni, recensioni, eventi e partite organizzate, utenti** (chi ha inserito il campo, chi ci gioca, i profili). Lo strumento di estrazione non li scrive nel file, e se ci fossero il comando li ignora: nel database di HOOP 3X3 non entra nessun dato degli utenti di Pick-Roll.
