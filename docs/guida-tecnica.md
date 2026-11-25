# DASS · Guida tecnica

La [Home del diario](http://127.0.0.1:8080/dass/) mostra il profilo attivo, i parametri del bolo, le bozze da completare e i collegamenti ad analisi, storico, catalogo, Swagger UI e stato del servizio. Ogni pagina ha il selettore del profilo e il pannello **Scambio di chiavi**.

Per una panoramica e le schermate torna al [README](../README.md). Architettura, dati e API sono descritti anche nel [manuale del progetto](../DASS_Manuale_Progetto.html).

## Compilare e avviare

Aprire Windows PowerShell 5.1 nella radice del workspace.

```powershell
.\Prepara-DASS.ps1
mvn clean verify
if ($LASTEXITCODE -eq 0) { .\scripts\Avvia-DASS.ps1 }
```

Usare sempre `clean`: Maven non rimuove da `target/classes` le classi eliminate e un WAR con le vecchie entità non supera il deploy. `Prepara-DASS.ps1` imposta per Maven l'archivio certificati di Windows (`-Djavax.net.ssl.trustStoreType=Windows-ROOT`), utile dove un antivirus ispeziona le connessioni HTTPS; `Avvia-DASS.ps1` fa lo stesso per Payara, che deve raggiungere Anthropic.

L'archivio predefinito è `var/dati/dass.mv.db`; per usarne un altro impostare `$env:DASS_DATA_DIR` prima dell'avvio. Il parametro `-Porta` vale per l'avvio e per gli script di verifica.

## Verificare

Con il server avviato, da una seconda PowerShell preparata:

```powershell
.\scripts\Verifica-DASS.ps1
.\scripts\Verifica-Servizio.ps1
```

`Verifica-DASS.ps1` controlla pagina, `api/stato`, l'elenco dei profili (`utente1` e `utente2`, senza `paz-demo`) e che i documenti del diario rispondano 401 senza scambio di chiavi. `Verifica-Servizio.ps1` esegue in Java lo stesso protocollo del browser: scambio ECDH, buste cifrate, salvataggio e lettura di un documento cifrato con una chiave che Payara non riceve, isolamento tra profili, busta ripetuta, analisi senza consenso, vista H2 e prova di decifratura con un'altra chiave. Il documento di prova viene eliminato.

Senza server, dopo `mvn clean verify`:

| Script | Collaudo |
| --- | --- |
| `.\scripts\Verifica-Sicurezza.ps1` | HKDF con il vettore della RFC 5869, accordo ECDH, buste valide, ripetute, alterate, scadute o di un'altra richiesta, punti fuori curva, chiavi distinte per scambio |
| `.\scripts\Verifica-Persistenza.ps1` | Migrazione di un archivio con `paz-demo`, profili vuoti, revisioni, conflitti, limiti, isolamento tra profili, vista H2 e riavvio, su un H2 temporaneo |
| `.\scripts\Verifica-ClaudeOffline.ps1` | Richieste a Sonnet 5.5 con trasporto simulato: ricerca, output strutturato, override per Haiku, correzioni della proposta ed errori del fornitore |

## Chiave Anthropic e modello

Eseguire una volta `.\scripts\Salva-ChiaveClaude.ps1` e incollare la chiave nel campo mascherato. Lo script la cifra con DPAPI nel profilo Windows corrente (`%LOCALAPPDATA%\DASS\anthropic-key.dpapi`); `Avvia-DASS.ps1` la decifra solo per il processo di Payara. Un Payara già avviato carica la chiave nuova al riavvio.

Il modello predefinito è `claude-sonnet-5-5`; `DASS_CLAUDE_MODEL` lo sostituisce (`DASS_HAIKU_MODEL` resta accettato per compatibilità). La richiesta usa l'output strutturato con lo schema [`proposta-ingredienti.json`](../src/main/resources/analisi/proposta-ingredienti.json), `effort` (basso per la ricerca, medio per la proposta) e il fallback lato server; della risposta si leggono solo i blocchi di testo. La ricerca delle ricette usa lo strumento web search in modalità diretta (`allowed_callers: ["direct"]`, al massimo due ricerche), perché con il filtro dinamico predefinito il testo finale non porta le citazioni `web_search_result_location` che DASS accetta come fonti; richiede **Web search** abilitato per l'organizzazione e un turno sospeso (`pause_turn`) viene ripreso fino a tre volte. Dopo ogni analisi il log di Payara riporta ricerche eseguite, risultati, errori dello strumento e fonti citate, e il motivo di un eventuale rifiuto di Anthropic. `InterpreteProposta` corregge le righe incoerenti (pesi non validi, ricette non citate dalla ricerca, pesi attribuiti a una descrizione assente) e le segnala nei dubbi.

## Profili e archivio cifrato

All'avvio `AvvioArchivio` elimina una sola volta le tabelle del diario in chiaro della versione precedente (`PAZIENTE`, `PIATTO`, `EPISODIO`, `BOZZA`, `INGREDIENTE`, `FEEDBACK`, `ESITO`, `MEMORIAPIATTO`), importa il catalogo e crea i profili `utente1` e `utente2` con diario vuoto. Il catalogo resta pubblico e in chiaro: le schede non sono dati personali.

La tabella `DOCUMENTO_CIFRATO` conserva per ogni documento solo id, profilo, tipo, revisione, data di aggiornamento, IV e testo cifrato. Payara controlla profilo, tipo, revisione e dimensioni ma non legge mai il contenuto.

| Tipo | Id | Contenuto cifrato nel browser |
| --- | --- | --- |
| `impostazioni` | `impostazioni-<profilo>` | ICR, ISF, glicemia obiettivo |
| `bozza` | `b-<uuid>` | Descrizione, proposta, ingredienti corretti, glicemie |
| `foto` | `f-<uuid>` | JPEG normalizzato del piatto (lato massimo 1280 px) |
| `miniatura` | `m-<uuid>` | JPEG di 120 px per lo storico |
| `pasto` | `p-<uuid>` | Ingredienti con le schede usate, totali, bolo, riferimenti a foto e miniatura |

Gli id sono unici in tutto l'archivio. `PUT` con revisione 0 crea un documento, altrimenti la revisione attesa deve coincidere con quella salvata (409 `REVISIONE_SUPERATA`); un documento di un altro profilo risulta inesistente. Il testo cifrato va da 16 byte a 8 MiB.

## Scambio di chiavi e canale cifrato

Ogni azione che legge o scrive H2 apre un nuovo scambio: apertura della Home, dell'analisi, di una bozza, dello storico o di un pasto, caricamento, correzione della bozza, salvataggio del pasto, rimozione, vista H2 e prova di decifratura. Tutte le richieste dell'azione, miniature comprese, usano quel canale.

1. Il browser genera una coppia ECDH P-256 effimera (privata non esportabile) e invia `POST /api/scambi` con `profiloId`, `azione` e la chiave pubblica (65 byte, Base64).
2. Payara genera la sua coppia effimera e un sale di 32 byte, calcola il segreto ECDH e risponde con `scambioId`, chiave pubblica, sale, scadenza e le impronte delle due chiavi (primi 64 bit dello SHA-256).
3. Entrambi derivano la chiave del canale con HKDF-SHA-256, info `DASS-scambio-v1|scambioId|profiloId|azione`, e ne ricavano una chiave AES-GCM a 256 bit. Lo scambio vale 5 minuti e al massimo 500 richieste.
4. Ogni richiesta porta `X-DASS-Scambio` e `X-DASS-Busta`: `base64url(iv).base64url(cifrato)` di `{metodo, percorso, nonce, istante}`, con AAD `DASS|richiesta|scambioId`. Il filtro rifiuta buste che descrivono un'altra richiesta, più vecchie di 2 minuti o con un nonce già usato.
5. Il corpo è `{iv, dati}` con AAD `DASS|corpo|scambioId|nonce`; la risposta della risorsa arriva cifrata con AAD `DASS|risposta|scambioId|nonce` e l'intestazione `X-DASS-Cifrato: 1`.
6. Sui percorsi `/api/profili/{profilo}/…` lo scambio deve appartenere allo stesso profilo (403 `SCAMBIO_DI_ALTRO_PROFILO`). Gli errori del canale arrivano in chiaro: senza uno scambio valido non c'è una chiave per cifrarli e non contengono dati del diario.

Il codice è in [`sicurezza/`](../src/main/java/it/unisi/dass/sicurezza/) lato Java e in [`js/sicurezza/scambio.js`](../src/main/webapp/js/sicurezza/scambio.js) lato browser.

## Chiave del diario nel browser

[`js/sicurezza/cripto.js`](../src/main/webapp/js/sicurezza/cripto.js) genera per ogni profilo una chiave AES-GCM a 256 bit con `extractable=false` e la conserva in IndexedDB (database `dass-chiavi`). La pagina può usarla per cifrare e decifrare ma non può leggerne i byte né inviarla. Ogni documento ha un IV casuale di 12 byte e l'AAD `DASS|diario|v1|profilo|tipo|id`, che lega il testo cifrato al suo profilo, tipo e id. Il pannello mostra solo un identificativo casuale della chiave.

La chiave del diario è distinta da quella del canale: il canale protegge il trasporto della singola azione, la chiave del diario protegge i dati conservati in H2.

## Analisi del piatto

Il modulo richiede foto, glicemia attuale, glicemia obiettivo (predefinita dai parametri del profilo, altrimenti 120 mg/dL) e il consenso all'invio di foto e descrizione a Payara e ad Anthropic. Il browser ricodifica la foto in JPEG senza metadati, crea la miniatura e, nella stessa azione `caricamento`:

1. invia foto e descrizione a `POST /api/analisi-piatto` dentro il canale cifrato; Payara le inoltra a Claude e restituisce la proposta senza salvare nulla;
2. usa per ogni ingrediente la scheda del catalogo scelta da Claude (o la cerca per nome se Claude non ne indica una) e prepara la bozza;
3. cifra e salva foto, miniatura e bozza.

Se Claude non è disponibile (chiave assente, errore del fornitore) la bozza viene creata vuota con la foto, e gli ingredienti si inseriscono a mano. Se il salvataggio fallisce dopo l'analisi, la proposta resta sullo schermo con **Salva di nuovo la bozza cifrata**. Una bozza si riapre con `analisi.html?bozza=<uuid>` o dalla Home.

## Calcolo del bolo

Dopo **Calcola carboidrati e bolo** il browser salva la bozza, calcola contributi e totali con i valori per 100 g del catalogo e applica

```text
bolo = carboidrati / ICR + (glicemia attuale − glicemia obiettivo) / ISF
```

ICR (1–150 g/U) e ISF (5–500 mg/dL per U) arrivano dai parametri del profilo, salvati cifrati dalla Home. La pagina mostra la formula con i valori sostituiti e aggiorna il risultato mentre si modificano le glicemie. Il bolo calcolato non scende sotto 0 U; con glicemia sotto 70 mg/dL vale 0 U e la pagina segnala l'ipoglicemia. Sono segnalati anche la correzione negativa e la glicemia sopra 250 mg/dL. Il calcolo è didattico: non considera l'insulina attiva e non è una prescrizione. La logica è in [`js/logica/bolo.js`](../src/main/webapp/js/logica/bolo.js).

**Registra pasto cifrato** salva il documento `p-<uuid>` con ingredienti, schede, totali e bolo e chiude la bozza nella stessa azione; foto e miniatura restano per lo storico. Lo stesso pasto non si può registrare due volte.

## Cambio di profilo

Il selettore dell'intestazione cambia il profilo in tutte le schede aperte. [`js/profilo.js`](../src/main/webapp/js/profilo.js) interrompe le richieste aperte con un `AbortController`, incrementa la generazione e chiede alla pagina di svuotare lo schermo: dati, foto decifrate, campi del modulo, calcoli e pannello. Ogni azione ricorda la generazione in cui è partita e scarta le risposte arrivate dopo il cambio, quindi una risposta tardiva non può mostrare i dati del profilo precedente.

## Verifica della cifratura e demo

Nello storico, **Mostra i record di H2** apre l'azione `vista-h2`: `GET /api/archivio-h2` restituisce gli ultimi record della tabella `DOCUMENTO_CIFRATO` di tutti i profili, con metadati, lunghezza ed estratto del testo cifrato. **Prova a decifrare** scarica il record completo (`GET /api/archivio-h2/{profilo}/{id}`) e prova ad aprirlo con la chiave del diario del profilo attivo: riesce solo con i documenti di quel profilo, altrimenti la verifica del tag AES-GCM fallisce. La vista passa dal canale cifrato come ogni lettura di H2 ed espone solo ciò che il server conserva.

Sequenza per l'orale:

1. Con `utente1` impostare ICR e ISF nella Home, analizzare un piatto, calcolare il bolo e registrare il pasto, mostrando nel pannello gli scambi `caricamento`, `correzione-bozza` e `salvataggio-pasto`.
2. Passare a `utente2`: lo schermo si svuota, lo storico è vuoto.
3. Aprire **Archivio H2**: il pasto di `utente1` compare come testo cifrato; **Prova a decifrare** con la chiave di `utente2` fallisce.
4. Tornare a `utente1` e riaprire il pasto con **Apri il pasto**: foto e valori si decifrano nel browser.

## Limiti noti

- La scelta del profilo non è un'autenticazione: chiunque usi il browser può selezionare un profilo. La protezione dei dati sta nella cifratura con chiavi che restano nel browser.
- Lo scambio ECDH non autentica Payara e il prototipo gira in HTTP su `127.0.0.1`: un intermediario attivo potrebbe sostituire le chiavi pubbliche. Le impronte del pannello servono al confronto manuale; in produzione servirebbero HTTPS e una chiave del server firmata o fissata.
- Payara vede in chiaro foto e descrizione durante l'analisi (con consenso) e conosce i metadati di ogni documento: profilo, id, tipo, dimensione e date.
- Le chiavi del diario esistono solo nel browser che le ha create: cancellando i dati del sito o usando un altro browser i documenti già salvati non si aprono più e vengono segnalati come non decifrabili.
- Il bolo è un calcolo didattico.

## Catalogo nutrizionale

`mvn clean verify` include nel WAR [`macros_tables/catalogo_nutrizionale_it.json`](../macros_tables/catalogo_nutrizionale_it.json), versione `catalogo-it-v3`: 555 schede, di cui 505 complete che Payara importa. Le fonti sono 424 alimenti di USDA Foundation Foods (export del 29 ottobre 2026), 117 di USDA SR Legacy (aprile 2018: alimenti cotti, pane, pasta, legumi lessati, formaggi, salumi, oli di semi, latte di cocco, molluschi, condimenti, erbe, bevande), 13 schede CREA per alimenti italiani che USDA non ha (gnocchi di patate, mascarpone, prosciutto crudo, bresaola, speck, stracchino, grissini, ricotta di vacca, scamorza) e lo zucchero di canna di Italia Zuccheri. Ogni scheda indica nella fonte l'ID dell'alimento e come sono calcolati i carboidrati.

I carboidrati sono **disponibili, fibra esclusa**, come nelle etichette europee e nel conteggio dei carboidrati per il bolo: per USDA carboidrati per differenza meno fibra; per le poche schede vegetali di Foundation senza dato di fibra, la fibra è stimata con il rapporto fibra/carboidrati dell'alimento equivalente di SR Legacy; per CREA amido più zuccheri, perché CREA pubblica i carboidrati disponibili in equivalenti di monosaccaridi (amido × 1,10); per i salumi CREA che non separano amido e zuccheri, i carboidrati pubblicati, sempre sotto 1,5 g. Esempi: pasta di semola secca 71,5 g, pasta cotta 34,0 g, riso bollito 28,3 g, pane comune 46,0 g per 100 g.

Le cinque schede sintetiche delle versioni precedenti (`pasta-v1`, `sugo-v1`, `olio-v1`, `riso-v1`, `piselli-v1`) mantengono l'id, così le bozze già salvate restano valide, ma hanno ora valori USDA reali. A ogni nuova versione del file `CatalogoUsda` inserisce le schede nuove e aggiorna nome, preparazione, fonte e valori di quelle già presenti.

La richiesta della proposta contiene il catalogo come elenco numerato (numero, nome, preparazione, fonte) in un secondo blocco del prompt di sistema, con cache dei prompt perché resta identico tra un'analisi e l'altra. Per ogni ingrediente Claude indica in `voceCatalogo` il numero della scheda che descrive lo stesso alimento nella preparazione dei grammi. Se il catalogo non ha l'alimento indica la scheda più simile per carboidrati, proteine e grassi, per esempio l'olio di semi di girasole per l'olio di semi vari, con `schedaApprossimata` a true e la spiegazione nella motivazione; usa null solo se nessuna scheda è simile. `InterpreteProposta` riconduce il numero all'id della scheda e scarta i numeri inesistenti. Nel browser la riga mostra «Scheda scelta da Claude» o «Scheda più simile scelta da Claude» e resta da verificare se la scheda è approssimata o se è cruda e i grammi cotti, o viceversa.

Erbe aromatiche, spezie, sale, pepe e acqua non apportano carboidrati, proteine o grassi rilevanti: Claude le segna con `trascurabile` a true e `InterpreteProposta` le toglie dalla proposta prima dell'editor, senza contarle nel limite di 30 righe. Nel log del server compare solo il numero delle righe escluse, non i nomi degli alimenti. Per le righe senza scelta e per quelle aggiunte a mano il browser precompila **Voce del catalogo** quando la corrispondenza per nome e preparazione è univoca e segnala le approssimazioni. I valori sono per 100 g. [Documentazione USDA Foundation Foods](https://fdc.nal.usda.gov/Foundation_Foods_Documentation/) · [Dataset USDA FoodData Central](https://fdc.nal.usda.gov/download-datasets) · [CREA, presentazione dei dati](https://www.alimentinutrizione.it/presentazione-dati) · [Scheda CREA della pasta cotta](https://www.alimentinutrizione.it/tabelle-nutrizionali/000805)

## Swagger UI e OpenAPI

[Swagger UI](http://127.0.0.1:8080/dass/swagger.html) carica la [specifica OpenAPI](http://127.0.0.1:8080/dass/openapi.json) dal sorgente `src/main/webapp/openapi.json`. Si possono provare direttamente `GET /api/stato`, `GET /api/catalogo`, `GET /api/profili` e `POST /api/scambi`. Gli endpoint del diario, dell'analisi e della vista H2 richiedono buste cifrate che Swagger UI non sa preparare: la specifica ne descrive intestazioni, buste e contenuto in chiaro, e `Verifica-Servizio.ps1` ne mostra un client completo.
