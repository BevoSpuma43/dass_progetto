/**
 * API REST di DASS eseguita nel browser per la versione GitHub Pages.
 *
 * Su GitHub Pages non c'è Payara: questo modulo intercetta le chiamate fetch verso api/... e le
 * risolve con lo stesso contratto delle risorse Jakarta REST (percorsi, metodi, codici HTTP e JSON
 * descritti in openapi.json). Le altre richieste, comprese quelle ad api.anthropic.com, passano
 * invariate alla fetch del browser. Va caricato prima degli script delle pagine.
 */
import {ErroreAnalisi} from "./locale/errore.js";
import * as archivio from "./locale/archivio.js";
import {MAX_BYTES, prepara as preparaFoto} from "./locale/foto.js";
import {analizza} from "./locale/haiku-client.js";
import {leggiChiave, leggiModello} from "./locale/impostazioni.js";
import {leggi as leggiRichiestaPasto, orario} from "./locale/richiesta-pasto.js";
import {REGOLA} from "./locale/anteprima.js";
import {PROPOSTA_ESEMPIO} from "./locale/dati-demo.js";

const fetchDelBrowser = window.fetch.bind(window);
const radiceApi = new URL("api/", document.baseURI);
const CAMPI_ANTEPRIMA = new Set(["revisione", "ingredienti", "scenarioId", "piattoId", "consumatoAlle"]);

window.fetch = function (input, init) {
  let url;
  try {
    url = new URL(input instanceof Request ? input.url : String(input), document.baseURI);
  } catch {
    return fetchDelBrowser(input, init);
  }
  if (!url.href.startsWith(radiceApi.href)) return fetchDelBrowser(input, init);
  return gestisci(input instanceof Request ? new Request(input, init) : new Request(url, init), url);
};

/** Instrada la richiesta e converte gli errori applicativi nella risposta JSON del backend. */
async function gestisci(richiesta, url) {
  try {
    const segmenti = url.pathname.slice(radiceApi.pathname.length).split("/").map(decodeURIComponent);
    return await instrada(richiesta.method.toUpperCase(), segmenti, url.searchParams, richiesta);
  } catch (e) {
    if (e instanceof ErroreAnalisi) return errore(e.statoHttp, e.codice, e.message);
    if (e instanceof URIError) return errore(400, "PERCORSO_NON_VALIDO", "Percorso non valido.");
    console.error("API DASS nel browser:", e);
    return errore(500, "ERRORE_INTERNO", "Errore imprevisto dell’API nel browser. Ricarica la pagina e riprova.");
  }
}

async function instrada(metodo, [a, b, c, ...resto], query, richiesta) {
  const n = 1 + (b !== undefined) + (c !== undefined) + resto.length;
  if (n === 1 && a === "stato") return solo(metodo, "GET", () => json(200, {stato: "ok"}));
  if (n === 1 && a === "catalogo") {
    return solo(metodo, "GET", async () => json(200, await archivio.catalogo(query.get("q") ?? "")));
  }
  if (n === 2 && a === "demo" && b === "archivio") {
    return solo(metodo, "GET", async () => json(200, await archivio.riepilogoArchivio()));
  }
  if (n === 1 && a === "analisi-piatto") return solo(metodo, "POST", () => analisiPiatto(richiesta));
  if (a === "bozze" && b) {
    if (n === 2 && metodo === "GET") return bozzaOppure404(await archivio.leggiBozza(b));
    if (n === 2 && metodo === "DELETE") return rimuoviBozza(b, query.get("revisione"));
    if (n === 2) return nonConsentito("GET, DELETE");
    if (n === 3 && c === "foto") {
      return solo(metodo, "GET", async () => {
        const foto = await archivio.fotoBozza(b);
        return foto ? immagine(foto) : bozzaAssente();
      });
    }
    if (n === 3 && c === "ingredienti") return solo(metodo, "PUT", () => elaboraRighe(b, richiesta, false));
    if (n === 3 && c === "anteprima") return solo(metodo, "POST", () => elaboraRighe(b, richiesta, true));
  }
  if (a === "pasti") {
    if (n === 1) return solo(metodo, "POST", () => registraPasto(richiesta));
    if (n === 2 && b) return solo(metodo, "GET", async () => json(200, await archivio.dettaglioPasto(b)));
    if (n === 3 && b && c === "foto") {
      return solo(metodo, "GET", () => fotoPasto(b, (query.get("miniatura") ?? "").toLowerCase() === "true"));
    }
  }
  if (n === 3 && a === "piatti" && b && c === "pasti") {
    return solo(metodo, "GET", async () => json(200, await archivio.pastiDelPiatto(b)));
  }
  if (n === 3 && a === "pazienti" && b && c === "piatti") {
    return solo(metodo, "GET", async () => json(200, await archivio.piattiDelPaziente(b)));
  }
  if (n === 3 && a === "pazienti" && b && c === "pasti") {
    return solo(metodo, "GET", async () => json(200, await archivio.pastiDelPaziente(b)));
  }
  return errore(404, "RISORSA_ASSENTE", "Risorsa inesistente nell’API DASS.");
}

/* ---------- Risposte ---------- */

function json(stato, dati, intestazioni = {}) {
  return new Response(JSON.stringify(dati), {
    status: stato,
    headers: {"Content-Type": "application/json", "Cache-Control": "no-store", ...intestazioni}
  });
}

function errore(stato, codice, messaggio) {
  return json(stato, {codice, messaggio});
}

function immagine(blob) {
  return new Response(blob, {status: 200, headers: {"Content-Type": blob.type || "image/jpeg", "Cache-Control": "no-store"}});
}

function bozzaAssente() {
  return errore(404, "BOZZA_ASSENTE", "Bozza scaduta o inesistente.");
}

function bozzaOppure404(dati) {
  return dati === null ? bozzaAssente() : json(200, dati);
}

function nonConsentito(metodi) {
  return json(405, {codice: "METODO_NON_CONSENTITO", messaggio: "Metodo non supportato da questa risorsa."}, {Allow: metodi});
}

function solo(metodo, atteso, azione) {
  return metodo === atteso ? azione() : nonConsentito(atteso);
}

/** Corpo JSON della richiesta: null se vuoto, errore 400 se non è un oggetto JSON. */
async function corpoJson(richiesta) {
  const testo = await richiesta.text();
  if (testo.trim() === "") return null;
  let valore;
  try {
    valore = JSON.parse(testo);
  } catch {
    throw new ErroreAnalisi(400, "JSON_NON_VALIDO", "Il corpo della richiesta non è un JSON valido.");
  }
  if (valore !== null && (typeof valore !== "object" || Array.isArray(valore))) {
    throw new ErroreAnalisi(400, "JSON_NON_VALIDO", "Il corpo della richiesta deve essere un oggetto JSON.");
  }
  return valore;
}

/* ---------- POST /api/analisi-piatto (ProvaHaikuServlet) ---------- */

/**
 * Valida l'upload, normalizza il JPEG e salva la bozza prima dell'analisi. In caso di errore
 * applicativo successivo restituisce comunque la bozza, così l'utente può compilarla a mano.
 */
async function analisiPiatto(richiesta) {
  let bozzaId = null;
  try {
    // Il modulo della pagina imposta questo header. Un form HTML di un altro sito non può inviarlo.
    if (richiesta.headers.get("X-DASS-Analisi") !== "1") {
      throw new ErroreAnalisi(403, "ORIGINE", "Apri la prova dalla pagina DASS.");
    }
    if (!(richiesta.headers.get("Content-Type") ?? "").toLowerCase().startsWith("multipart/form-data")) {
      throw new ErroreAnalisi(415, "FORMATO", "Invia foto e descrizione come multipart/form-data.");
    }
    let modulo;
    try {
      modulo = await richiesta.formData();
    } catch {
      throw new ErroreAnalisi(400, "MULTIPART_NON_VALIDO", "Caricamento illeggibile. Seleziona di nuovo la foto.");
    }
    const foto = modulo.get("foto");
    if (!(foto instanceof Blob) || foto.size === 0) throw new ErroreAnalisi(400, "FOTO_ASSENTE", "Seleziona la foto di un piatto.");
    if (foto.size > MAX_BYTES) throw new ErroreAnalisi(413, "UPLOAD_GRANDE", "La foto deve pesare al massimo 5 MiB.");
    const descrizione = modulo.get("caption");
    let caption = "";
    if (descrizione !== null) {
      const testo = typeof descrizione === "string" ? descrizione : await descrizione.text();
      if (new TextEncoder().encode(testo).length > 2000) throw captionLunga();
      caption = testo.trim();
      if (caption.length > 500) throw captionLunga();
    }
    const jpeg = await preparaFoto(foto);
    bozzaId = await archivio.creaBozza(jpeg, caption);
    const proposta = await proponi(jpeg, caption, modulo.get("esempio") === "1");
    return bozzaOppure404(await archivio.completaBozza(bozzaId, proposta));
  } catch (e) {
    if (!(e instanceof ErroreAnalisi) || bozzaId === null) throw e;
    const bozza = await archivio.leggiBozza(bozzaId);
    if (bozza === null) throw e;
    return json(e.statoHttp, {...bozza, codice: e.codice, messaggio: e.message});
  }
}

/** Con una chiave chiama Haiku; senza chiave usa la proposta di esempio o lascia la bozza da compilare. */
async function proponi(jpeg, caption, fotoEsempio) {
  const impostazione = leggiChiave();
  if (impostazione) return analizza(jpeg, caption, {chiave: impostazione.chiave, modello: leggiModello()});
  if (fotoEsempio) return structuredClone(PROPOSTA_ESEMPIO);
  throw new ErroreAnalisi(503, "CHIAVE_ASSENTE",
    "Nessuna chiave Anthropic impostata, quindi Haiku non ha analizzato la foto.");
}

function captionLunga() {
  return new ErroreAnalisi(400, "CAPTION_LUNGA", "Usa al massimo 500 caratteri per la descrizione.");
}

/* ---------- Bozze (BozzeResource) ---------- */

async function rimuoviBozza(id, revisione) {
  let attesa = null;
  if (revisione !== null) {
    if (!/^[+-]?\d+$/.test(revisione) || Number(revisione) < 0) {
      return errore(400, "REVISIONE_NON_VALIDA", "Revisione non valida.");
    }
    attesa = Number(revisione);
  }
  await archivio.rimuoviBozza(id, attesa);
  return new Response(null, {status: 204, headers: {"Cache-Control": "no-store"}});
}

/** Valida il corpo comune di correzione e anteprima e inoltra all'archivio l'operazione richiesta. */
async function elaboraRighe(id, richiesta, anteprima) {
  const corpo = await corpoJson(richiesta);
  if (anteprima && corpo !== null && !Object.keys(corpo).every(k => CAMPI_ANTEPRIMA.has(k))) {
    return errore(400, "CAMPI_NON_VALIDI", "Invia solo revisione, ingredienti, scenarioId, piattoId e consumatoAlle.");
  }
  if (anteprima && corpo !== null && "scenarioId" in corpo && corpo.scenarioId !== REGOLA) {
    return errore(400, "SCENARIO_NON_VALIDO", "Scegli lo scenario dimostrativo.");
  }
  if (corpo === null || !Number.isInteger(corpo.revisione) || !Array.isArray(corpo.ingredienti)) {
    return errore(400, "BOZZA_NON_VALIDA", "Invia revisione e ingredienti.");
  }
  if (!anteprima) return bozzaOppure404(await archivio.correggiBozza(id, corpo.revisione, corpo.ingredienti));
  const piattoId = corpo.piattoId ?? null;
  if (piattoId !== null && (typeof piattoId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(piattoId))) {
    return errore(400, "PIATTO_NON_VALIDO", "Scegli un piatto abituale.");
  }
  const consumatoAlle = "consumatoAlle" in corpo ? orario(corpo) : null;
  const dati = await archivio.anteprimaBozza(id, corpo.revisione, corpo.ingredienti, piattoId);
  if (dati !== null && consumatoAlle !== null) {
    return json(200, {...dati, consumatoAlle: new Date(consumatoAlle).toISOString().replace(".000Z", "Z")});
  }
  return bozzaOppure404(dati);
}

/* ---------- Pasti (PastiResource) ---------- */

async function registraPasto(richiesta) {
  const dati = await archivio.registraPasto(leggiRichiestaPasto(await corpoJson(richiesta)));
  return json(201, dati, {Location: new URL(encodeURIComponent(dati.pastoId), new URL("pasti/", radiceApi)).href});
}

async function fotoPasto(id, miniatura) {
  const foto = await archivio.fotoPasto(id, miniatura);
  if (foto.blob) return immagine(foto.blob);
  const risposta = await fetchDelBrowser(new URL(foto.url, document.baseURI));
  if (!risposta.ok) throw new ErroreAnalisi(404, "PASTO_ASSENTE", "Pasto inesistente.");
  return immagine(await risposta.blob());
}
