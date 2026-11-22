/**
 * Scambio di chiavi ECDH P-256 per ogni azione del diario e canale cifrato delle sue richieste.
 *
 * Il browser genera una coppia di chiavi effimera (la privata non è esportabile), invia a Payara solo la
 * pubblica e riceve la pubblica di Payara con un sale. Entrambi derivano lo stesso segreto ECDH e, con
 * HKDF-SHA-256, la chiave AES-GCM del canale. Ogni richiesta dell'azione porta una busta cifrata con
 * metodo, percorso, istante e nonce; corpo e risposta viaggiano cifrati con la stessa chiave.
 */
import {base64, base64Url, byteInTesto, cifra, daBase64, decifra, impronta, testoInByte} from "./cripto.js";

export const ALGORITMO = "ECDH P-256 · HKDF-SHA-256 · AES-GCM 256";

/** Errore di una richiesta: stato HTTP, codice applicativo e messaggio per l'utente. */
export class ErroreRichiesta extends Error {
  constructor(stato, codice, messaggio) {
    super(messaggio);
    this.name = "ErroreRichiesta";
    this.stato = stato;
    this.codice = codice;
  }
}

const ascoltatori = new Set();

/** Il pannello «Scambio di chiavi» riceve ogni aggiornamento delle tracce. */
export function osservaScambi(funzione) {
  ascoltatori.add(funzione);
  return () => ascoltatori.delete(funzione);
}

function notifica(traccia) {
  for (const funzione of ascoltatori) {
    try { funzione(traccia); } catch { /* Un pannello in errore non blocca l'azione. */ }
  }
}

function dimensione(byte) {
  return byte < 1024 ? byte + " B" : (byte / 1024).toLocaleString("it-IT", {maximumFractionDigits: 1}) + " KiB";
}

/** Legge una risposta in chiaro di Payara (apertura dello scambio o errore del canale). */
async function jsonInChiaro(risposta) {
  const testo = await risposta.text();
  let valore = null;
  try {
    valore = testo ? JSON.parse(testo) : null;
  } catch {
    throw new ErroreRichiesta(risposta.status, "RISPOSTA_INATTESA",
      "Risposta inattesa dal server. Verifica che Payara esegua il WAR aggiornato.");
  }
  if (!risposta.ok) {
    throw new ErroreRichiesta(risposta.status, valore?.codice ?? "HTTP_" + risposta.status, valore?.messaggio ?? "Richiesta non riuscita.");
  }
  return valore;
}

/**
 * Apre lo scambio di chiavi di un'azione del profilo e restituisce il canale da usare per tutte le sue
 * richieste. `signal` interrompe le richieste quando l'utente cambia profilo.
 */
export async function apriCanale(profilo, azione, signal) {
  const coppia = await crypto.subtle.generateKey({name: "ECDH", namedCurve: "P-256"}, false, ["deriveBits"]);
  const pubblica = new Uint8Array(await crypto.subtle.exportKey("raw", coppia.publicKey));
  const traccia = {
    id: crypto.randomUUID(), profilo, azione, inizio: new Date(), algoritmo: ALGORITMO,
    improntaBrowser: await impronta(pubblica), improntaServer: null, scambioId: null, scadeAlle: null,
    derivazione: null, messaggi: [], esito: "in corso", dettaglioEsito: ""
  };
  traccia.messaggi.push({verso: "uscita", testo: "POST /api/scambi · chiave pubblica del browser (65 B), profilo " + profilo + ", azione " + azione});
  notifica(traccia);
  let dati;
  try {
    const risposta = await fetch("api/scambi", {
      method: "POST", cache: "no-store", signal, headers: {"Content-Type": "application/json"},
      body: JSON.stringify({profiloId: profilo, azione, chiavePubblica: base64(pubblica)})
    });
    dati = await jsonInChiaro(risposta);
    const pubblicaServer = daBase64(dati.chiavePubblica);
    traccia.scambioId = dati.scambioId;
    traccia.scadeAlle = new Date(dati.scadeAlle);
    traccia.improntaServer = await impronta(pubblicaServer);
    traccia.messaggi.push({verso: "entrata", testo: "201 · chiave pubblica di Payara (65 B) e sale HKDF (32 B), scambio " + dati.scambioId.slice(0, 8)});
    if (dati.improntaBrowser !== traccia.improntaBrowser || dati.improntaServer !== traccia.improntaServer) {
      throw new ErroreRichiesta(0, "IMPRONTA_DIVERSA", "Le impronte delle chiavi pubbliche non coincidono: scambio interrotto.");
    }
    const chiaveServer = await crypto.subtle.importKey("raw", pubblicaServer, {name: "ECDH", namedCurve: "P-256"}, false, []);
    const segreto = await crypto.subtle.deriveBits({name: "ECDH", public: chiaveServer}, coppia.privateKey, 256);
    const materiale = await crypto.subtle.importKey("raw", segreto, "HKDF", false, ["deriveKey"]);
    const info = `DASS-scambio-v1|${dati.scambioId}|${profilo}|${azione}`;
    const chiave = await crypto.subtle.deriveKey({name: "HKDF", hash: "SHA-256", salt: daBase64(dati.sale), info: testoInByte(info)},
      materiale, {name: "AES-GCM", length: 256}, false, ["encrypt", "decrypt"]);
    traccia.derivazione = "Segreto ECDH (32 B) → HKDF-SHA-256 con sale di Payara e info «" + info + "» → chiave AES-GCM 256, non esportabile";
    notifica(traccia);
    return new Canale(traccia, chiave, signal);
  } catch (e) {
    chiudi(traccia, e);
    throw e;
  }
}

function chiudi(traccia, errore) {
  if (traccia.esito !== "in corso") return;
  if (!errore) {
    traccia.esito = "riuscita";
  } else if (errore.name === "AbortError" || errore.name === "AzioneAnnullata") {
    traccia.esito = "annullata";
    traccia.dettaglioEsito = "Profilo cambiato: richieste interrotte e risposte ignorate.";
  } else {
    traccia.esito = "errore";
    traccia.dettaglioEsito = (errore.codice ? errore.codice + " · " : "") + errore.message;
  }
  notifica(traccia);
}

/** Canale cifrato di un'azione: usa la chiave derivata dallo scambio per buste, corpi e risposte. */
export class Canale {
  #chiave;

  constructor(traccia, chiave, signal) {
    this.traccia = traccia;
    this.#chiave = chiave;
    this.signal = signal;
  }

  get id() {
    return this.traccia.scambioId;
  }

  /** Segna la fine dell'azione nel pannello. */
  chiudi(errore) {
    chiudi(this.traccia, errore);
  }

  /**
   * Invia una richiesta nel canale. `percorso` è relativo alla pagina (es. "api/profili/utente1/documenti");
   * `corpo`, se presente, è un oggetto JSON da cifrare.
   */
  async richiesta(metodo, percorso, corpo) {
    if (this.signal?.aborted) throw new DOMException("Azione interrotta", "AbortError");
    if (Date.now() > this.traccia.scadeAlle.getTime() - 2000) {
      throw new ErroreRichiesta(401, "SCAMBIO_SCADUTO", "Lo scambio di chiavi è scaduto: ripeti l'azione.");
    }
    const url = new URL(percorso, document.baseURI);
    const nonce = crypto.randomUUID();
    const descrittore = {metodo, percorso: url.pathname + url.search, nonce, istante: Date.now()};
    const busta = await cifra(this.#chiave, testoInByte(JSON.stringify(descrittore)), "DASS|richiesta|" + this.id);
    const intestazioni = {"X-DASS-Scambio": this.id, "X-DASS-Busta": base64Url(busta.iv) + "." + base64Url(busta.dati)};
    let body;
    let descrizione = metodo + " " + url.pathname.replace(/^.*?\/api\//, "/api/") + url.search + " · busta cifrata";
    if (corpo !== undefined) {
      const cifrato = await cifra(this.#chiave, testoInByte(JSON.stringify(corpo)), `DASS|corpo|${this.id}|${nonce}`);
      body = JSON.stringify({iv: base64(cifrato.iv), dati: base64(cifrato.dati)});
      intestazioni["Content-Type"] = "application/json";
      descrizione += ", corpo cifrato " + dimensione(cifrato.dati.length);
    }
    this.traccia.messaggi.push({verso: "uscita", testo: descrizione});
    notifica(this.traccia);
    const risposta = await fetch(url, {method: metodo, headers: intestazioni, body, cache: "no-store", signal: this.signal});
    if (risposta.headers.get("X-DASS-Cifrato") !== "1") {
      // Gli errori del canale (scambio scaduto, busta ripetuta…) arrivano in chiaro: non contengono dati.
      try {
        return await jsonInChiaro(risposta);
      } catch (e) {
        this.traccia.messaggi.push({verso: "entrata", testo: risposta.status + " in chiaro · " + (e.codice ?? "errore")});
        notifica(this.traccia);
        throw e;
      }
    }
    const testo = await risposta.text();
    let valore;
    try {
      const involucro = JSON.parse(testo);
      const chiaro = await decifra(this.#chiave, daBase64(involucro.iv), daBase64(involucro.dati), `DASS|risposta|${this.id}|${nonce}`);
      valore = chiaro.length > 0 ? JSON.parse(byteInTesto(chiaro)) : null;
      this.traccia.messaggi.push({verso: "entrata", testo: risposta.status + " · risposta cifrata " + dimensione(daBase64(involucro.dati).length) + ", decifrata nel browser"});
    } catch {
      this.traccia.messaggi.push({verso: "entrata", testo: risposta.status + " · risposta non autentica"});
      notifica(this.traccia);
      throw new ErroreRichiesta(risposta.status, "RISPOSTA_NON_AUTENTICA", "La risposta cifrata non supera la verifica AES-GCM.");
    }
    notifica(this.traccia);
    if (!risposta.ok) {
      throw new ErroreRichiesta(risposta.status, valore?.codice ?? "HTTP_" + risposta.status, valore?.messaggio ?? "Richiesta non riuscita.");
    }
    return valore;
  }
}
