/**
 * Dati validati di una conferma, usati per la registrazione e per riconoscere i retry.
 * Porting di it.unisi.dass.servizi.RichiestaPasto.
 */
import {ErroreAnalisi} from "./errore.js";

const CAMPI = new Set(["richiestaId", "bozzaId", "revisione", "consumatoAlle", "piattoId", "nomePiatto", "ingredienti"]);
const ISO_CON_OFFSET = /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(?:([Zz])|([+-])(\d{2}):(\d{2})(?::(\d{2}))?)$/;

/**
 * Valida il corpo JSON, gli ID, la revisione e la scelta tra piatto nuovo e abituale.
 * Restituisce i dati normalizzati della conferma.
 */
export function leggi(json) {
  if (json === null || typeof json !== "object" || Array.isArray(json) ||
      !Object.keys(json).every(k => CAMPI.has(k)) || !Number.isInteger(json.revisione) ||
      !Array.isArray(json.ingredienti) || json.ingredienti.length > 30) {
    throw errore("PASTO_NON_VALIDO", "Invia revisione, ingredienti e dati di registrazione del pasto.");
  }
  const richiestaId = identificativo(json, "richiestaId", true);
  const bozzaId = identificativo(json, "bozzaId", true);
  const piattoId = identificativo(json, "piattoId", false);
  const nomePiatto = testo(json, "nomePiatto", null, 200);
  if ((piattoId === null) === (nomePiatto === null)) {
    throw errore("PIATTO_NON_VALIDO", "Scegli un piatto abituale oppure indica il nome del nuovo piatto.");
  }
  if (json.revisione < 0 || !Number.isSafeInteger(json.revisione)) {
    throw errore("REVISIONE_NON_VALIDA", "Indica la revisione corrente della bozza.");
  }
  return {
    richiestaId, bozzaId, revisione: json.revisione, consumatoAlleMs: orario(json),
    piattoId, nomePiatto, ingredienti: preparaRighe(json.ingredienti)
  };
}

/** Legge data e ora ISO 8601 con offset e restituisce l'istante in millisecondi, indipendente dal fuso. */
export function orario(json) {
  const valore = testo(json, "consumatoAlle", "", 64);
  const parti = ISO_CON_OFFSET.exec(valore);
  const nonValido = () => errore("ORARIO_NON_VALIDO", "Indica data e ora del pasto con fuso orario in formato ISO 8601.");
  if (!parti) throw nonValido();
  const [anno, mese, giorno, ore, minuti] = parti.slice(1, 6).map(Number);
  const secondi = Number(parti[6] ?? 0);
  const frazione = Number(("0." + (parti[7] ?? "0")));
  const offsetOre = Number(parti[10] ?? 0);
  const offsetMinuti = Number(parti[11] ?? 0);
  const offsetSecondi = Number(parti[12] ?? 0);
  const giorniNelMese = new Date(Date.UTC(anno, mese, 0)).getUTCDate();
  if (mese < 1 || mese > 12 || giorno < 1 || giorno > giorniNelMese || ore > 23 || minuti > 59 || secondi > 59 ||
      offsetOre > 18 || offsetMinuti > 59 || offsetSecondi > 59) throw nonValido();
  const segno = parti[9] === "-" ? -1 : 1;
  const offsetMs = segno * ((offsetOre * 60 + offsetMinuti) * 60 + offsetSecondi) * 1000;
  const utc = new Date(0);
  utc.setUTCFullYear(anno, mese - 1, giorno);
  utc.setUTCHours(ore, minuti, secondi, Math.floor(frazione * 1000));
  return utc.getTime() - offsetMs;
}

/** Righe normalizzate da usare nel calcolo e nell'impronta, con pesi positivi quando presenti. */
function preparaRighe(righe) {
  return righe.map(riga => {
    if (riga === null || typeof riga !== "object" || Array.isArray(riga)) throw rigaNonValida();
    const id = identificativo(riga, "rigaId", false);
    const nome = testo(riga, "nome", null, 500);
    const voce = identificativo(riga, "voceCatalogoId", false);
    const stato = testo(riga, "stato", "non_noto", 20);
    if (nome === null || !["non_noto", "crudo", "cotto"].includes(stato)) throw rigaNonValida();
    const pulita = {nome, stato, rigaId: id ?? "", voceCatalogoId: voce};
    const peso = riga.quantitaG ?? null;
    if (peso === null) pulita.quantitaG = null;
    else if (typeof peso === "number" && peso > 0 && Number.isFinite(peso)) pulita.quantitaG = peso;
    else throw rigaNonValida();
    return pulita;
  });
}

/** Controlla formato e lunghezza di un ID, distinguendo tra riferimenti obbligatori e facoltativi. */
function identificativo(json, campo, obbligatorio) {
  const valore = testo(json, campo, null, 128);
  if (valore === null && !obbligatorio) return null;
  if (valore === null || !/^[A-Za-z0-9_-]+$/.test(valore)) {
    throw errore("PASTO_NON_VALIDO", "Controlla il campo " + campo + ".");
  }
  return valore;
}

/** Legge una stringa, elimina gli spazi ai bordi e applica limite di lunghezza e valore predefinito. */
function testo(json, campo, predefinito, massimo) {
  const valore = json[campo] ?? null;
  if (valore === null) return predefinito;
  if (typeof valore !== "string" || valore.length > massimo) {
    throw errore("PASTO_NON_VALIDO", "Controlla il campo " + campo + ".");
  }
  const pulito = valore.trim();
  return pulito === "" ? predefinito : pulito;
}

/** Ordine delle proprietà, cifre decimali finali e offset equivalenti non cambiano la conferma. */
export async function impronta(richiesta) {
  const contenuto = {
    bozzaId: richiesta.bozzaId, revisione: richiesta.revisione,
    consumatoAlle: new Date(richiesta.consumatoAlleMs).toISOString(), ingredienti: richiesta.ingredienti,
    ...(richiesta.piattoId === null ? {nomePiatto: richiesta.nomePiatto} : {piattoId: richiesta.piattoId})
  };
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(JSON.stringify(contenuto)));
  return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function rigaNonValida() {
  return errore("RIGA_NON_VALIDA", "Controlla nome, preparazione e peso positivo degli ingredienti.");
}

function errore(codice, messaggio) {
  return new ErroreAnalisi(400, codice, messaggio);
}
