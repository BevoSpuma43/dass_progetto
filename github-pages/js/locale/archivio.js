/**
 * Archivio della demo statica in IndexedDB: bozze, piatti, memorie, pasti, letture e foto restano nel
 * browser di chi visita il sito. Porting di ArchivioBozze, ArchivioPasti e ArchivioDemo; la fixture
 * viene caricata al primo accesso, come fa Payara con H2.
 */
import {ErroreAnalisi} from "./errore.js";
import {catalogo as caricaCatalogo} from "./catalogo.js";
import {collega, corrispondeAllaRicerca, suggerisci} from "./abbinamento.js";
import {prepara} from "./correzione.js";
import {calcola, VARIANZA_INIZIALE} from "./anteprima.js";
import {impronta as improntaDi} from "./richiesta-pasto.js";
import {miniatura as creaMiniatura} from "./foto.js";
import {PAZIENTE_ID, VERSIONE, fixture} from "./dati-demo.js";

const NOME_DB = "dass-github-pages";
const ARCHIVI = ["pazienti", "piatti", "memorie", "episodi", "feedback", "esiti", "bozze", "foto", "caricamenti"];
const DURATA_BOZZA_S = 24 * 60 * 60;
let apertura = null;
const urlFoto = new Map();

/* ---------- IndexedDB ---------- */

function apri() {
  apertura ??= aperturaDb().catch(e => {
    apertura = null;
    throw e;
  });
  return apertura;
}

async function aperturaDb() {
  let db;
  try {
    db = await new Promise((risolvi, rifiuta) => {
      const richiesta = indexedDB.open(NOME_DB, 1);
      richiesta.onupgradeneeded = () => {
        for (const nome of ARCHIVI) {
          if (!richiesta.result.objectStoreNames.contains(nome)) {
            richiesta.result.createObjectStore(nome, {keyPath: nome === "foto" ? "chiave" : "id"});
          }
        }
      };
      richiesta.onsuccess = () => risolvi(richiesta.result);
      richiesta.onerror = () => rifiuta(richiesta.error);
      richiesta.onblocked = () => rifiuta(new Error("Apertura bloccata da un'altra scheda."));
    });
  } catch {
    throw new ErroreAnalisi(503, "ARCHIVIO_NON_DISPONIBILE",
      "Il browser non consente di salvare dati per questo sito. Esci dalla navigazione privata o abilita i dati dei siti, poi ricarica la pagina.");
  }
  db.onversionchange = () => db.close();
  // Carica la fixture una sola volta, come DatiDemo.inizializza.
  await transazioneSu(db, ARCHIVI, "readwrite", async a => {
    if (!(await a.leggi("caricamenti", VERSIONE))) await scriviFixture(a);
  });
  return db;
}

function esito(richiesta) {
  return new Promise((risolvi, rifiuta) => {
    richiesta.onsuccess = () => risolvi(richiesta.result);
    richiesta.onerror = () => rifiuta(richiesta.error);
  });
}

/**
 * Esegue il lavoro in un'unica transazione. Dentro `lavoro` si attendono solo richieste IndexedDB,
 * così la transazione resta attiva fino al commit; un errore annulla tutte le scritture.
 */
async function transazioneSu(db, nomi, modalita, lavoro) {
  const t = db.transaction(nomi, modalita);
  const fine = new Promise((risolvi, rifiuta) => {
    t.oncomplete = () => risolvi();
    t.onabort = () => rifiuta(t.error ?? new DOMException("Transazione annullata", "AbortError"));
  });
  fine.catch(() => { /* Gestito sotto: prevale l'errore del lavoro. */ });
  const archivio = nome => t.objectStore(nome);
  const accesso = {
    leggi: (nome, chiave) => esito(archivio(nome).get(chiave)),
    tutti: nome => esito(archivio(nome).getAll()),
    scrivi: (nome, valore) => esito(archivio(nome).put(valore)),
    elimina: (nome, chiave) => esito(archivio(nome).delete(chiave)),
    svuota: nome => esito(archivio(nome).clear())
  };
  let risultato;
  try {
    risultato = await lavoro(accesso);
  } catch (e) {
    try { t.abort(); } catch { /* Transazione già conclusa. */ }
    throw e;
  }
  await fine;
  return risultato;
}

async function transazione(nomi, modalita, lavoro) {
  return transazioneSu(await apri(), nomi, modalita, lavoro);
}

async function scriviFixture(a) {
  for (const [nome, righe] of Object.entries(fixture())) {
    for (const riga of righe) await a.scrivi(nome, riga);
  }
  await a.scrivi("caricamenti", {id: VERSIONE, versione: VERSIONE});
}

/** Cancella bozze e pasti di questo browser e ricarica la fixture originale. */
export async function ripristinaDatiDemo() {
  await transazione(ARCHIVI, "readwrite", async a => {
    for (const nome of ARCHIVI) await a.svuota(nome);
    await scriviFixture(a);
  });
  for (const url of urlFoto.values()) {
    url.then(u => { if (u?.startsWith("blob:")) URL.revokeObjectURL(u); }, () => {});
  }
  urlFoto.clear();
}

/* ---------- Utilità ---------- */

function adesso() {
  return Math.floor(Date.now() / 1000);
}

/** Istante ISO 8601 in UTC senza millisecondi nulli, come Instant.toString. */
export function istante(secondi) {
  return new Date(secondi * 1000).toISOString().replace(".000Z", "Z");
}

/** Indirizzo utilizzabile in <img>: file statico per la fixture, blob: per le foto salvate nel browser. */
function urlDaFoto(chiave) {
  if (!urlFoto.has(chiave)) {
    const url = transazione(["foto"], "readonly", a => a.leggi("foto", chiave))
      .then(foto => (foto ? URL.createObjectURL(new Blob([foto.dati], {type: foto.tipo})) : null));
    url.catch(() => urlFoto.delete(chiave));
    urlFoto.set(chiave, url);
  }
  return urlFoto.get(chiave);
}

async function urlEpisodio(e) {
  if (e.foto) return {foto: e.foto, miniatura: e.miniatura};
  return {foto: await urlDaFoto("pasto/" + e.id), miniatura: await urlDaFoto("pasto/" + e.id + "/miniatura")};
}

function valida(bozza) {
  return bozza && bozza.scadeEpochSecond > adesso() ? bozza : null;
}

function verificaRevisione(bozza, revisione) {
  if (bozza.revisione !== revisione) {
    throw new ErroreAnalisi(409, "REVISIONE_SUPERATA", "La bozza è stata aggiornata in un’altra scheda. Riaprila prima di modificare.");
  }
}

function piattoAssente() {
  return new ErroreAnalisi(404, "PIATTO_ASSENTE", "Piatto inesistente.");
}

function profilo(paziente) {
  return {
    id: paziente.id, nome: paziente.nome, versione: paziente.versioneProfilo, targetMgDl: paziente.targetMgDl,
    glicemiaInizialeMgDl: paziente.glicemiaInizialeMgDl, insulinaAttivaIniziale: paziente.insulinaAttivaIniziale
  };
}

function cronologicoInverso(a, b) {
  return b.istantePastoEpochSecond - a.istantePastoEpochSecond || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
}

/* ---------- Catalogo e bozze (ArchivioBozze) ---------- */

/** Schede ordinate per nome e ID, con il filtro facoltativo usato da GET /api/catalogo?q=. */
export async function catalogo(filtro = "") {
  const voci = await caricaCatalogo();
  return filtro.trim() === "" ? voci : voci.filter(v => corrispondeAllaRicerca(v.nome, v.preparazione, filtro));
}

/** Rimuove le bozze scadute e salva una nuova bozza vuota con foto e descrizione, valida per 24 ore. */
export async function creaBozza(jpeg, caption) {
  const ora = adesso();
  const id = crypto.randomUUID();
  const dati = await jpeg.arrayBuffer();
  await transazione(["bozze", "foto"], "readwrite", async a => {
    for (const vecchia of await a.tutti("bozze")) {
      if (vecchia.scadeEpochSecond <= ora) {
        await a.elimina("bozze", vecchia.id);
        await a.elimina("foto", "bozza/" + vecchia.id);
      }
    }
    await a.scrivi("bozze", {
      id, caption, scadeEpochSecond: ora + DURATA_BOZZA_S, revisione: 0,
      proposta: {statoAnalisi: "da_compilare", origineAnalisi: "manuale", ingredienti: [], dubbi: []}
    });
    await a.scrivi("foto", {chiave: "bozza/" + id, tipo: "image/jpeg", dati});
  });
  return id;
}

/** Abbina la proposta al catalogo, la salva e restituisce il dettaglio aggiornato. */
export async function completaBozza(id, proposta) {
  const voci = await caricaCatalogo();
  const bozza = await transazione(["bozze"], "readwrite", async a => {
    const b = valida(await a.leggi("bozze", id));
    if (!b) return null;
    b.proposta = collega(proposta, voci, b.caption);
    b.revisione++;
    await a.scrivi("bozze", b);
    return b;
  });
  return bozza && dettaglio(bozza);
}

/** Dettaglio di una bozza valida, oppure null se è scaduta o assente. */
export async function leggiBozza(id) {
  const bozza = valida(await transazione(["bozze"], "readonly", a => a.leggi("bozze", id)));
  return bozza && dettaglio(bozza);
}

/** Verifica la revisione attesa e salva le righe validate incrementando la revisione. */
export async function correggiBozza(id, revisione, righe) {
  const voci = await caricaCatalogo();
  const bozza = await transazione(["bozze"], "readwrite", async a => {
    const b = valida(await a.leggi("bozze", id));
    if (!b) return null;
    verificaRevisione(b, revisione);
    b.proposta = {...b.proposta, ingredienti: prepara(righe, b.proposta.ingredienti, voci), statoAnalisi: "da_confermare"};
    b.revisione++;
    await a.scrivi("bozze", b);
    return b;
  });
  return bozza && dettaglio(bozza);
}

/** Foto JPEG di una bozza valida, oppure null. */
export async function fotoBozza(id) {
  const foto = await transazione(["bozze", "foto"], "readonly", async a => {
    const b = valida(await a.leggi("bozze", id));
    return b ? a.leggi("foto", "bozza/" + id) : null;
  });
  return foto ? new Blob([foto.dati], {type: foto.tipo}) : null;
}

/** Rimuove la bozza; se è ancora valida ed è indicata una revisione, questa deve essere quella salvata. */
export async function rimuoviBozza(id, revisione) {
  await transazione(["bozze", "foto"], "readwrite", async a => {
    const b = await a.leggi("bozze", id);
    if (!b) return;
    if (revisione !== null && b.scadeEpochSecond > adesso()) verificaRevisione(b, revisione);
    await a.elimina("bozze", id);
    await a.elimina("foto", "bozza/" + id);
  });
}

/** Calcolo in sola lettura per il profilo dimostrativo e, se indicato, la memoria del piatto abituale. */
export async function anteprimaBozza(id, revisione, righe, piattoId) {
  const voci = await caricaCatalogo();
  return transazione(["bozze", "pazienti", "piatti", "memorie"], "readonly", async a => {
    const b = valida(await a.leggi("bozze", id));
    if (!b) return null;
    const paziente = await leggiPaziente(a);
    const memoria = piattoId === null ? null : await memoriaCorrente(a, piattoId);
    return calcolaBozza(b, revisione, righe, paziente, memoria, voci);
  });
}

/** Calcolo condiviso da anteprima e registrazione, come ArchivioBozze.calcola. */
function calcolaBozza(bozza, revisione, righe, paziente, memoria, voci) {
  verificaRevisione(bozza, revisione);
  const confermate = prepara(righe, bozza.proposta.ingredienti, voci);
  const risultato = calcola(confermate, voci, profilo(paziente), memoria && perSimulazione(memoria));
  return {...risultato, origineAnalisi: bozza.proposta.origineAnalisi ?? "manuale"};
}

/** Proposta salvata con ID, foto, revisione e scadenza; ricalcola i suggerimenti delle righe senza scheda. */
async function dettaglio(bozza) {
  const proposta = bozza.proposta;
  const dati = {...proposta};
  if (proposta.ingredienti.some(r => typeof r.voceCatalogoId !== "string" && r.sceltaCatalogoManuale !== true)) {
    const voci = await caricaCatalogo();
    dati.ingredienti = proposta.ingredienti.map(r => suggerisci(r, voci));
  }
  return {
    ...dati, bozzaId: bozza.id, caption: bozza.caption, revisione: bozza.revisione,
    scadeAlle: istante(bozza.scadeEpochSecond), fotoUrl: await urlDaFoto("bozza/" + bozza.id)
  };
}

/* ---------- Pasti (ArchivioPasti) ---------- */

async function leggiPaziente(a) {
  const paziente = await a.leggi("pazienti", PAZIENTE_ID);
  if (!paziente) throw new ErroreAnalisi(503, "PROFILO_ASSENTE", "Profilo dimostrativo non disponibile.");
  return paziente;
}

/** Il piatto deve appartenere al profilo demo; restituisce la memoria con la revisione più alta. */
async function memoriaCorrente(a, piattoId) {
  const piatto = await a.leggi("piatti", piattoId);
  if (!piatto || piatto.pazienteId !== PAZIENTE_ID) throw piattoAssente();
  const memorie = (await a.tutti("memorie")).filter(m => m.piattoId === piattoId).sort((x, y) => y.numero - x.numero);
  if (memorie.length === 0) throw new ErroreAnalisi(409, "MEMORIA_ASSENTE", "Il piatto abituale non ha una memoria iniziale.");
  return {record: memorie[0], piatto};
}

function perSimulazione({record, piatto}) {
  return {id: record.id, revisione: record.numero, inizialeG: piatto.memoriaInizialeG,
    correnteG: record.carboidratiEfficaciG, varianza: record.varianza};
}

/**
 * Riconosce i retry dalla chiave e dall'impronta. Per una conferma nuova ricalcola e salva episodio,
 * foto, istantanea e ingredienti nella stessa transazione.
 */
export async function registraPasto(richiesta) {
  const firma = await improntaDi(richiesta);
  const voci = await caricaCatalogo();
  const lettura = await transazione(["episodi", "bozze", "foto"], "readonly", async a => {
    const precedente = (await a.tutti("episodi")).find(e => e.chiaveInvio === richiesta.richiestaId);
    if (precedente) return {precedente};
    const b = valida(await a.leggi("bozze", richiesta.bozzaId));
    return {foto: b ? await a.leggi("foto", "bozza/" + b.id) : null};
  });
  if (lettura.precedente) return registrato(lettura.precedente, firma);
  // La miniatura usa il canvas, quindi si prepara fuori dalla transazione di scrittura.
  const miniatura = lettura.foto
    ? await (await creaMiniatura(new Blob([lettura.foto.dati], {type: lettura.foto.tipo}))).arrayBuffer() : null;

  return transazione(["pazienti", "piatti", "memorie", "episodi", "bozze", "foto"], "readwrite", async a => {
    const paziente = await leggiPaziente(a);
    const precedente = (await a.tutti("episodi")).find(e => e.chiaveInvio === richiesta.richiestaId);
    if (precedente) return registrato(precedente, firma);
    const b = valida(await a.leggi("bozze", richiesta.bozzaId));
    const foto = b ? await a.leggi("foto", "bozza/" + b.id) : null;
    if (!b || !foto || !miniatura) throw new ErroreAnalisi(404, "BOZZA_ASSENTE", "Bozza scaduta o inesistente.");
    let memoria = richiesta.piattoId === null ? null : await memoriaCorrente(a, richiesta.piattoId);
    const calcolo = calcolaBozza(b, richiesta.revisione, richiesta.ingredienti, paziente, memoria, voci);
    if (calcolo.stato !== "completa") throw new ErroreAnalisi(400, "PASTO_INCOMPLETO", calcolo.daCompletare.join(" "));

    if (memoria === null) memoria = await nuovoPiatto(a, richiesta.nomePiatto, calcolo.totali.carboidratiG);
    const simulazione = {...calcolo.simulazione, memoriaId: memoria.record.id, revisioneMemoria: memoria.record.numero};
    const episodio = {
      id: crypto.randomUUID(), piattoId: memoria.piatto.id, memoriaOriginaleId: memoria.record.id,
      chiaveInvio: richiesta.richiestaId, improntaInvio: firma,
      istantePastoEpochSecond: Math.floor(richiesta.consumatoAlleMs / 1000), caption: b.caption,
      formatoFoto: "image/jpeg", foto: null, miniatura: null, totali: calcolo.totali,
      stimaOriginale: simulazione.unitaSimulate, versioneProfilo: paziente.versioneProfilo,
      versioneRegola: calcolo.versioneRegola, indiceGlicemico: "non_disponibile",
      anteprimaOriginale: {...calcolo, simulazione},
      ingredienti: calcolo.righe.map(r => ({
        id: crypto.randomUUID(), voceCatalogoId: r.voceCatalogoId, quantitaG: r.quantitaG, origineQuantita: r.origineQuantita
      }))
    };
    await a.scrivi("episodi", episodio);
    await a.scrivi("foto", {chiave: "pasto/" + episodio.id, tipo: foto.tipo, dati: foto.dati});
    await a.scrivi("foto", {chiave: "pasto/" + episodio.id + "/miniatura", tipo: "image/jpeg", dati: miniatura});
    return {pastoId: episodio.id, piattoId: episodio.piattoId};
  });
}

/** Ricevuta di un retry: stessa chiave e stessa conferma restituiscono gli stessi identificativi. */
function registrato(episodio, firma) {
  if (episodio.improntaInvio !== firma) {
    throw new ErroreAnalisi(409, "RICHIESTA_GIA_USATA", "La stessa chiave identifica un'altra conferma.");
  }
  return {pastoId: episodio.id, piattoId: episodio.piattoId};
}

/** Crea un piatto e la prima memoria usando i carboidrati confermati come valore iniziale. */
async function nuovoPiatto(a, nome, carboidratiG) {
  const piatto = {id: crypto.randomUUID(), pazienteId: PAZIENTE_ID, nome, memoriaInizialeG: carboidratiG};
  const record = {
    id: piatto.id + "-v0", piattoId: piatto.id, numero: 0, carboidratiEfficaciG: carboidratiG,
    varianza: VARIANZA_INIZIALE, segnaliPositiviConsecutivi: 0, ultimoSegno: 0
  };
  await a.scrivi("piatti", piatto);
  await a.scrivi("memorie", record);
  return {record, piatto};
}

/** Riga sintetica del diario con data, foto, stima salvata e stato del feedback. */
async function riepilogo(e, nomePiatto, conFeedback) {
  const url = await urlEpisodio(e);
  return {
    pastoId: e.id, piattoId: e.piattoId, nomePiatto, consumatoAlle: istante(e.istantePastoEpochSecond),
    fotoUrl: url.foto, miniaturaUrl: url.miniatura, stimaOriginale: e.stimaOriginale,
    statoFeedback: conFeedback ? "registrato" : "in_attesa"
  };
}

async function letturaDiario() {
  return transazione(["pazienti", "piatti", "episodi", "feedback"], "readonly", async a => ({
    pazienti: await a.tutti("pazienti"),
    piatti: await a.tutti("piatti"),
    episodi: (await a.tutti("episodi")).sort(cronologicoInverso),
    conFeedback: new Set((await a.tutti("feedback")).map(f => f.episodioId))
  }));
}

function verificaPaziente(dati, id) {
  if (!dati.pazienti.some(p => p.id === id)) throw new ErroreAnalisi(404, "PAZIENTE_ASSENTE", "Paziente inesistente.");
}

/** Episodi di un piatto dal più recente, con lo stato del feedback. */
export async function pastiDelPiatto(id) {
  const dati = await letturaDiario();
  const piatto = dati.piatti.find(p => p.id === id);
  if (!piatto) throw piattoAssente();
  return Promise.all(dati.episodi.filter(e => e.piattoId === id)
    .map(e => riepilogo(e, piatto.nome, dati.conFeedback.has(e.id))));
}

/** Piatti del profilo: prima quelli con un pasto, dal più recente, poi gli altri in ordine di nome. */
export async function piattiDelPaziente(id) {
  const dati = await letturaDiario();
  verificaPaziente(dati, id);
  const piatti = new Map(dati.piatti.filter(p => p.pazienteId === id).map(p => [p.id, p]));
  const ultimi = new Map();
  for (const e of dati.episodi) if (piatti.has(e.piattoId) && !ultimi.has(e.piattoId)) ultimi.set(e.piattoId, e);
  const conPasto = await Promise.all([...ultimi.values()].map(async e => ({
    piattoId: e.piattoId, nome: piatti.get(e.piattoId).nome,
    ultimoPasto: await riepilogo(e, piatti.get(e.piattoId).nome, dati.conFeedback.has(e.id))
  })));
  const senzaPasto = [...piatti.values()].filter(p => !ultimi.has(p.id))
    .sort((a, b) => (a.nome < b.nome ? -1 : a.nome > b.nome ? 1 : a.id < b.id ? -1 : 1))
    .map(p => ({piattoId: p.id, nome: p.nome, ultimoPasto: null}));
  return [...conPasto, ...senzaPasto];
}

/** Storico del profilo con i totali e la stima salvati alla registrazione, dal più recente. */
export async function pastiDelPaziente(id) {
  const dati = await letturaDiario();
  verificaPaziente(dati, id);
  const piatti = new Map(dati.piatti.filter(p => p.pazienteId === id).map(p => [p.id, p]));
  return Promise.all(dati.episodi.filter(e => piatti.has(e.piattoId)).map(async e => ({
    pastoId: e.id, nomePiatto: piatti.get(e.piattoId).nome, consumatoAlle: istante(e.istantePastoEpochSecond),
    totali: e.totali, stimaOriginale: e.stimaOriginale, miniaturaUrl: (await urlEpisodio(e)).miniatura
  })));
}

/**
 * Istantanea originale del pasto. Per gli episodi della fixture, che la precedono, ricostruisce il
 * dettaglio dalle righe e dalla memoria salvate.
 */
export async function dettaglioPasto(id) {
  const dati = await transazione(["piatti", "memorie", "episodi", "feedback"], "readonly", async a => {
    const e = await a.leggi("episodi", id);
    if (!e) return null;
    return {
      e, piatto: await a.leggi("piatti", e.piattoId), memoria: await a.leggi("memorie", e.memoriaOriginaleId),
      feedback: (await a.tutti("feedback")).find(f => f.episodioId === id) ?? null
    };
  });
  if (!dati) throw new ErroreAnalisi(404, "PASTO_ASSENTE", "Pasto inesistente.");
  const {e, piatto, memoria, feedback} = dati;
  const risultato = {
    ...(await riepilogo(e, piatto.nome, feedback !== null)), caption: e.caption, versioneRegola: e.versioneRegola,
    versioneProfilo: e.versioneProfilo, indiceGlicemico: e.indiceGlicemico,
    feedback: feedback && {
      glicemiaMgDl: feedback.glicemiaMgDl, minutiDopoPasto: feedback.minutiDopoPasto,
      istanteLettura: istante(feedback.istanteLetturaEpochSecond), ammissibile: feedback.ammissibile, motivo: feedback.motivo
    }
  };
  if (e.anteprimaOriginale) {
    const originale = e.anteprimaOriginale;
    return {
      ...risultato, ingredienti: originale.righe, totali: originale.totali, profilo: originale.profilo,
      simulazioneOriginale: originale.simulazione, origineAnalisi: originale.origineAnalisi
    };
  }
  // Gli episodi della fixture conservano solo i riferimenti alle schede e le quantità.
  const voci = new Map((await caricaCatalogo()).map(v => [v.id, v]));
  const righe = [...e.ingredienti].sort((x, y) => (x.id < y.id ? -1 : 1)).map(i => {
    const v = voci.get(i.voceCatalogoId);
    return {nome: v.nome, stato: v.preparazione, quantitaG: i.quantitaG, origineQuantita: i.origineQuantita,
      unita: v.unita, voceCatalogoId: v.id, catalogo: v};
  });
  return {
    ...risultato, ingredienti: righe, totali: e.totali, origineAnalisi: "fixture",
    profilo: {id: piatto.pazienteId, versione: e.versioneProfilo},
    simulazioneOriginale: {
      scenario: "fixture", memoriaId: memoria.id, revisioneMemoria: memoria.numero,
      memoriaInizialeG: piatto.memoriaInizialeG, memoriaCorrenteG: memoria.carboidratiEfficaciG,
      varianzaCorrente: memoria.varianza, unitaSimulate: e.stimaOriginale
    }
  };
}

/** Foto o miniatura del pasto: un file statico per la fixture, un Blob per i pasti registrati nel browser. */
export async function fotoPasto(id, miniatura) {
  const e = await transazione(["episodi"], "readonly", a => a.leggi("episodi", id));
  if (!e) throw new ErroreAnalisi(404, "PASTO_ASSENTE", "Pasto inesistente.");
  if (e.foto) return {url: miniatura ? e.miniatura : e.foto};
  const chiave = "pasto/" + id + (miniatura ? "/miniatura" : "");
  const foto = await transazione(["foto"], "readonly", a => a.leggi("foto", chiave));
  if (!foto) throw new ErroreAnalisi(404, "PASTO_ASSENTE", "Pasto inesistente.");
  return {blob: new Blob([foto.dati], {type: foto.tipo})};
}

/** Conteggi dell'archivio e ID dei pasti in ordine alfabetico, come GET /api/demo/archivio. */
export async function riepilogoArchivio() {
  return transazione(["pazienti", "piatti", "episodi", "feedback"], "readonly", async a => {
    const episodi = await a.tutti("episodi");
    return {
      pazienti: (await a.tutti("pazienti")).length, piatti: (await a.tutti("piatti")).length,
      episodi: episodi.length, feedback: (await a.tutti("feedback")).length,
      episodiId: episodi.map(e => e.id).sort()
    };
  });
}
