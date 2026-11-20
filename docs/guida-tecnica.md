# DASS · Guida tecnica

La [homepage del diario](http://127.0.0.1:8080/dass/) collega l'analisi del piatto, lo storico degli inserimenti, il catalogo alimenti, Swagger UI e lo stato del servizio. Il catalogo e lo stato restituiscono JSON. Nella scheda in cui è stata aperta una bozza, la home permette di riprenderla fino alla scadenza. L'editor ha un collegamento **Home** per tornare all'indice.

Per una panoramica e le schermate del prototipo, torna al [README](../README.md).

## Compilare e avviare

Aprire Windows PowerShell 5.1 nella radice del workspace. I comandi usano i percorsi relativi alla cartella corrente.

```powershell
.\Prepara-DASS.ps1
mvn clean verify
if ($LASTEXITCODE -eq 0) { .\scripts\Avvia-DASS.ps1 }
```

Al primo avvio Maven richiede accesso a Maven Central per plugin e dipendenze. Il tipo di pacchetto è WAR. Le API Jakarta EE 10 sono disponibili durante la compilazione e Payara fornisce la loro implementazione durante l’esecuzione. Lo script avvia il server in primo piano, usa il WAR in `target/dass.war` e configura il listener HTTP su `127.0.0.1:8080`. Attendere il completamento del deploy.

## Verificare e riavviare

L’archivio dello step 05 include `target/dass.war` prodotto da Maven. Con gli strumenti già preparati si può avviare direttamente `.\scripts\Avvia-DASS.ps1` nella stessa finestra PowerShell.

Aprire [la pagina locale](http://127.0.0.1:8080/dass/). Il messaggio atteso è «Il backend risponde correttamente». In una seconda PowerShell, dalla radice, eseguire il controllo di pagina, tipo di contenuto e stato JSON.

```powershell
.\scripts\Verifica-DASS.ps1
```

Il risultato atteso è HTTP 200 su entrambi i percorsi e `{"stato":"ok"}` sull’API. Premere Ctrl+C nella finestra del server. Rieseguire `.\scripts\Avvia-DASS.ps1` nella stessa finestra e ripetere la verifica. In caso di errore di connessione controllare nel log l’esito del deploy e la disponibilità della porta 8080.

Per architettura, modello dati e riferimenti al codice, consulta il [manuale del progetto](../DASS_Manuale_Progetto.html).

## Step 02 · Prova con una foto

Eseguire una volta il comando seguente. Quando appare il prompt, copiare il valore completo dalla Claude Console e incollarlo nel campo mascherato.

```powershell
.\scripts\Salva-ChiaveHaiku.ps1
```

Lo script cifra la chiave con DPAPI nel profilo Windows corrente, in `%LOCALAPPDATA%\DASS\anthropic-key.dpapi`. Per sostituirla ripetere il salvataggio. A ogni nuova PowerShell eseguire `.\Prepara-DASS.ps1` e poi `.\scripts\Avvia-DASS.ps1`. Il secondo script decifra il valore per avviare Payara, dando precedenza al file salvato. Se il file manca, resta disponibile la modalità con variabile di sessione. A server fermo la variabile nella shell può risultare vuota. Un Payara già in esecuzione carica la chiave aggiornata al riavvio.

1. Aprire [la prova locale](http://127.0.0.1:8080/dass/haiku.html). Si può raggiungere anche dalla pagina iniziale.
2. Selezionare una foto di cibo JPEG o PNG (massimo 5 MiB). Per un piatto di pasta si può usare la descrizione “Pasta al pomodoro, 80 g di pasta a crudo”. Il campo è facoltativo.
3. Premere **Invia a Haiku**. Ogni invio esplicito effettua una richiesta API. La pagina mostra il modello restituito, gli ingredienti proposti, le quantità da chiarire e il JSON del backend. Si può leggere il risultato senza registrare un pasto.
4. Per chiudere il collaudo annotare lo stato mostrato, il modello effettivo e un esempio di ingrediente o dubbio. In caso di errore annotare il messaggio, senza includere la chiave.

Il backend usa Anthropic Messages e come default `claude-haiku-4-5-20251001`. L’eventuale override `DASS_HAIKU_MODEL` deve indicare un modello Haiku accessibile alla chiave e va impostato prima di avviare Payara. Il backend ricodifica la foto in JPEG e invia soltanto l’immagine normalizzata e la caption insieme alle istruzioni di estrazione. La prova usa `POST /dass/prove/haiku`, multipart `foto` e `caption` con header `X-DASS-Prova: 1`; il modulo della pagina usa ora il contratto persistente dello step 04.

### Ripetere il collaudo offline

Eseguire `mvn clean verify` e poi `.\scripts\Verifica-HaikuOffline.ps1` nella PowerShell preparata. Il secondo comando avvia la suite esplicita con il provider JSON di Payara. L’immagine sintetica e le risposte controllate coprono validazione del file, payload con e senza caption, successo, chiave mancante, timeout, guasti di rete, risposte malformate ed errori del fornitore. La chiamata reale richiede il percorso browser descritto sopra.

## Step 03 · Archivio dei pasti

Il launcher crea `var/demo/dass.mv.db` nella cartella del progetto al primo avvio. Una build Maven o il riavvio di Payara conservano i pasti. Per usare un archivio diverso impostare `$env:DASS_DATA_DIR` prima di avviare il server. I percorsi relativi partono dalla radice del progetto. La porta predefinita è 8080 e il parametro facoltativo `-Porta` è disponibile sia per l’avvio sia per la verifica.

Dal browser aprire [il riepilogo dell’archivio](http://127.0.0.1:8080/dass/api/demo/archivio). Su un archivio appena creato si vedono un paziente, due piatti, cinque episodi e tre feedback. `pasta-04` è l’episodio della ricetta ricorrente in attesa di lettura. Il piatto di controllo ha l’episodio `controllo-01`.

Per ripetere il collaudo di persistenza, dalla radice in una PowerShell preparata:

```powershell
mvn verify
if ($LASTEXITCODE -eq 0) { .\scripts\Verifica-Persistenza.ps1 }
```

Lo script usa tre processi Java consecutivi, il driver H2 e il provider EclipseLink inclusi in Payara e un database temporaneo esclusivo. Verifica identità, foto e miniature, riferimenti, letture, stime, vincoli di unicità, annullamento della transazione e sopravvivenza di una modifica al riavvio. Con Payara già avviato, `.\scripts\Verifica-DASS.ps1` controlla anche il riepilogo HTTP. Per ripetere la prova sul server, fermarlo con Ctrl+C nella sua finestra, riavviarlo con la stessa directory dati e ripetere la verifica nell’altra finestra.

Le cinque foto sono immagini sintetiche della fixture. Le cinque voci originarie del catalogo contengono dati di test. La voce aggiuntiva della pasta cotta riporta fonte e valori CREA. Il caso da 50 a 52 g è un esito precaricato per i test di integrazione. L’anteprima di prima occorrenza è descritta allo step 05.

## Step 04 · Analizzare e correggere una bozza

Aprire [Analizza un piatto](http://127.0.0.1:8080/dass/haiku.html). Selezionare un JPEG o PNG e aggiungere, se utile, una descrizione come «80 g di pasta a crudo con sugo». Premere **Invia a Haiku**. Verificare alimento, preparazione, abbinamento al catalogo e grammi. Un campo vuoto indica un peso da chiarire. Con **Aggiungi ingrediente**, **Rimuovi** e **Salva bozza** si può correggere la proposta o compilare manualmente una bozza anche quando il fornitore è indisponibile. Lo stesso indirizzo con `?bozza=…` riapre foto e correzioni entro 24 ore dalla creazione.

| Richiesta relativa a `/dass/api` | Contratto |
|---|---|
| `POST /analisi-piatto` | Multipart `foto`, `caption` facoltativa e header `X-DASS-Analisi: 1`. Foto normalizzata e bozza vengono salvate prima della chiamata esterna. Un errore del fornitore include `bozzaId`, `fotoUrl`, codice e messaggio per il recupero manuale. |
| `GET /catalogo?q=pasta` | Filtro facoltativo. Ogni voce contiene ID versionato, unità, preparazione, fonte, IG e valori per 100 g. |
| `GET /bozze/{id}` e `GET /bozze/{id}/foto` | Restituiscono JSON e JPEG. Una bozza scaduta o assente restituisce 404. |
| `PUT /bozze/{id}/ingredienti` | JSON con `revisione` e `ingredienti`. Per una riga esistente conservare `rigaId`. Sono ammessi `voceCatalogoId` e `quantitaG` nulli. Il server valida gli ID e i pesi positivi e risponde 409 per una revisione superata. |

Dopo una build Maven, il comando `python .\scripts\Verifica-AnalisiPiatto.py` esegue un collaudo locale su Payara con database temporaneo e trasporto Haiku simulato. Richiede una PowerShell preparata e Python 3.9 o successivo per il solo test. Aggiungere `--da-step03 .\target\DASS_Step_03.zip` per provare anche l’aggiornamento dal WAR precedente.

## Step 05 · Anteprima nutrizionale e simulazione iniziale

Nell’editor controllare alimento, voce cruda o cotta e peso. Scegliere **Continua con queste quantità**. Il backend calcola contributi e totali di carboidrati, proteine, grassi e kcal con i valori per 100 g del catalogo. I dettagli mostrano la fonte, la versione e l’IG disponibile per ogni voce. La modifica di una riga nasconde l’anteprima precedente. **Salva bozza** conserva gli ingredienti. L’anteprima da sola lascia intatti bozza e diario.

`POST /dass/api/bozze/{id}/anteprima` accetta JSON con `revisione` e `ingredienti` nello stesso formato della correzione. Il campo facoltativo `scenarioId` ammette `demo-sintetica-v1`. L’API seleziona sul server il profilo fisso `paz-demo`. Il risultato comprende righe con provenienza, fonte, valori per 100 g e contributi, totali, profilo e versione della regola. Se manca un dato essenziale, HTTP 200 con `stato=incompleta`, elenco dei problemi, totali e simulazione nulli. Input non valido: 400. Bozza assente o scaduta: 404. Revisione superata: 409.

Per ripetere il caso di prova, selezionare 80 g di pasta cruda, 40 g di sugo cotto e 6 g di olio crudo. Il catalogo della demo dà 50 g di carboidrati, 7 g di proteine, 8 g di grassi e 300 kcal. Per il profilo sintetico il risultato iniziale è 5 unità simulate. È un dato didattico. Per rieseguire il collaudo dalla PowerShell preparata, compilare il WAR con `mvn verify` ed eseguire `python .\scripts\Verifica-AnalisiPiatto.py`.

## Porzione al ristorante

Caricare la foto e descrivere il piatto, ad esempio «fusilli al ragù di triglia, porzione normale». Il peso a crudo è facoltativo. Haiku cerca ricette con ingredienti, dosi e numero di persone, quindi propone grammi per una sola porzione, inclusi condimenti e guarnizioni. La tabella permette di modificare ogni riga. Aprire **Stima e scheda nutrizionale** per leggere le assunzioni e la ricetta citata. **Continua con queste quantità** salva la bozza e apre l’anteprima anche se mancano schede nutrizionali. I totali richiedono un abbinamento per ogni ingrediente.

Per la ricerca servono la chiave Anthropic già configurata e la funzione Web search abilitata per l'organizzazione. L’analisi usa due richieste Messages, con un turno aggiuntivo se la ricerca viene sospesa, e la ricerca può comportare costi API aggiuntivi. Se mancano fonti citabili, la pagina lo segnala e il modello usa foto e porzioni tipiche. Le bozze già salvate conservano i loro valori. Per ottenere una nuova proposta inviare nuovamente la foto.

[Documentazione della ricerca Anthropic](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool) · [Scheda CREA per la pasta cotta](https://www.alimentinutrizione.it/tabelle-nutrizionali/000805)

## Step 06 · Registrazione del pasto e storico REST

Nell'editor completare pesi e abbinamenti e scegliere **Continua con queste quantità**. Sotto l'anteprima, scegliere un piatto abituale oppure **Nuovo piatto**, inserire il nome se necessario e confermare la data e l'ora. Per un piatto abituale la pagina aggiorna l'anteprima usando la sua memoria corrente. Premere **Registra pasto**. La conferma mostra i collegamenti al dettaglio e allo storico del piatto.

Foto, miniatura, orario, quantità, provenienza, fonti, totali e simulazione restano consultabili dopo la scadenza della bozza. L'interfaccia assegna la stessa chiave di invio a una bozza anche in due schede. Se l'esito non arriva, **Riprova registrazione** usa gli stessi dati della conferma. Per registrare un altro episodio si parte da una nuova bozza.

| Richiesta relativa a `/dass/api` | Risposta |
|---|---|
| `POST /pasti` | `201` con `pastoId`, `piattoId` e header `Location`. Un retry con la stessa chiave e gli stessi dati restituisce gli stessi identificativi. Una conferma diversa con la stessa chiave restituisce `409`. |
| `GET /pazienti/{id}/piatti` | Piatti del paziente con l'ultimo episodio, dal più recente. L'ID del paziente dimostrativo è `paz-demo`. |
| `GET /piatti/{id}/pasti` | Episodi del piatto dal più recente, con orario, foto, miniatura e stato del feedback. |
| `GET /pasti/{id}` | Dettaglio con ingredienti, totali, stima originale, versione della memoria e feedback, se presente. |
| `GET /pasti/{id}/foto` | Foto JPEG dell'episodio. Il parametro `?miniatura=true` restituisce la miniatura. |

Per provare la registrazione da PowerShell, aprire una bozza completa e copiare il suo ID dalla barra degli indirizzi.

```powershell
$base = 'http://127.0.0.1:8080/dass/api'
$bozza = Invoke-RestMethod "$base/bozze/ID-BOZZA"
$conferma = @{
    richiestaId   = [guid]::NewGuid().ToString()
    bozzaId       = $bozza.bozzaId
    revisione     = $bozza.revisione
    consumatoAlle = [DateTimeOffset]::Now.ToString('o')
    nomePiatto    = 'Pasta di prova'
    ingredienti   = $bozza.ingredienti
} | ConvertTo-Json -Depth 10
$pasto = Invoke-RestMethod "$base/pasti" -Method Post -ContentType 'application/json; charset=utf-8' -Body $conferma
Invoke-RestMethod "$base/pasti/$($pasto.pastoId)"
```

Per un piatto abituale inviare `piattoId` al posto di `nomePiatto`. Conservare la stessa richiesta durante un retry, compreso l'orario. `consumatoAlle` richiede un offset ISO 8601, per esempio `2026-11-19T13:10:00+01:00`. L'archivio espone gli istanti in UTC a risoluzione di un secondo. Una bozza scaduta o un riferimento sconosciuto produce `404`, una revisione superata produce `409`, una richiesta incompleta o malformata produce `400`. Per ottenere anche l'anteprima della ricorrenza, passare `piattoId` a `POST /bozze/{id}/anteprima`.

Dopo `mvn verify`, `./scripts/Verifica-Persistenza.ps1 -SoloPastiInMemoria` esegue il collaudo dello step 06 in H2 in memoria. `python ./scripts/Verifica-AnalisiPiatto.py` ripete le prove via HTTP e verifica la lettura dell'episodio dopo il riavvio di Payara con un database temporaneo.

## Catalogo nutrizionale in italiano

`mvn verify` include nel WAR `macros_tables/catalogo_nutrizionale_it.json`. Il file contiene i 424 alimenti dell'export USDA del 29 ottobre 2026, con nome tradotto, nome originale, ID e macronutrienti. Cinque schede aggiuntive coprono farina 0, burro, zucchero di canna grezzo, lievito di birra secco e sale. Payara importa le 369 schede complete, aggiorna i nomi delle schede USDA già presenti e conserva ID e valori associati ai dati precedenti.

DASS precompila **Voce del catalogo** per gli ingredienti con una corrispondenza univoca. La scheda selezionata compare accanto all'ingrediente. In **Stima e scheda nutrizionale** si possono leggere le note, cercare una scheda o cambiare la scelta. Il sistema riprova a collegare le righe prive di scheda quando si riapre una vecchia bozza. Le selezioni salvate, anche **Da chiarire** scelto manualmente, vengono rispettate.

Nelle ricette da forno, una dose di farina, latte o burro divisa per il numero di porzioni si riferisce di norma all'ingrediente prima della cottura. Per le vecchie bozze in cui Haiku la marcava come cotta, DASS propone la scheda corrispondente e chiede di verificare la base del peso. Il nome e i grammi della proposta restano disponibili per la correzione.

I valori sono per 100 g. Le schede USDA riportano carboidrati totali per differenza, fibra inclusa. Le schede CREA aggiuntive riportano carboidrati disponibili. L'importazione salta le righe con valori necessari mancanti o negativi. [Documentazione USDA Foundation Foods](https://fdc.nal.usda.gov/Foundation_Foods_Documentation/)

## Storico inserimenti e rimozione delle bozze

Dalla home scegliere **Storico inserimenti**. I pasti del profilo dimostrativo sono ordinati dal più recente e mostrano foto, nome, data e ora, macro, energia e insulina simulata salvati al momento della registrazione. Aprire **Ingredienti e quantità** per vedere il dettaglio del pasto.

In una bozza aperta, **Rimuovi bozza** chiede conferma e torna alla home. I pasti registrati conservano foto, ingredienti e valori anche dopo la rimozione della bozza.

`GET /dass/api/pazienti/{id}/pasti` restituisce lo storico cronologico. `DELETE /dass/api/bozze/{id}?revisione=N` restituisce 204 anche se la bozza è già assente. Se la revisione è stata superata e la bozza è ancora aperta, restituisce 409. Il parametro `revisione` è facoltativo per i client REST.

## Swagger UI e OpenAPI

Dalla home aprire **Swagger UI**, oppure visitare [Swagger UI](http://127.0.0.1:8080/dass/swagger.html). Per una prima prova espandere `GET /api/stato`, premere **Try it out** e poi **Execute**. Il server delle richieste è il DASS locale sulla stessa porta della pagina.

La [specifica OpenAPI in JSON](http://127.0.0.1:8080/dass/openapi.json) è inclusa nel WAR e usata anche da MicroProfile OpenAPI di Payara su `/openapi`. Il sorgente da aggiornare quando cambia un endpoint è `src/main/webapp/openapi.json`. La pagina interattiva carica Swagger UI 5.33.3 da jsDelivr. Il JSON resta disponibile dal server locale.
