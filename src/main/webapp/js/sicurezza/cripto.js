/**
 * Primitive crittografiche del diario, tutte con Web Crypto.
 *
 * Ogni profilo ha una chiave AES-GCM a 256 bit generata nel browser come non esportabile e conservata
 * in IndexedDB: il codice della pagina può usarla per cifrare e decifrare, ma non leggerne i byte né
 * inviarla. Payara riceve e conserva solo i documenti già cifrati.
 */
const DB_CHIAVI = "dass-chiavi";
const ARCHIVIO = "chiavi";
const codifica = new TextEncoder();
const decodifica = new TextDecoder();

/* ---------- Codifiche ---------- */

export function base64(bytes) {
  const dati = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binario = "";
  for (let i = 0; i < dati.length; i += 0x8000) binario += String.fromCharCode(...dati.subarray(i, i + 0x8000));
  return btoa(binario);
}

export function daBase64(testo) {
  const binario = atob(testo);
  const dati = new Uint8Array(binario.length);
  for (let i = 0; i < binario.length; i++) dati[i] = binario.charCodeAt(i);
  return dati;
}

export function base64Url(bytes) {
  return base64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function testoInByte(testo) {
  return codifica.encode(testo);
}

export function byteInTesto(bytes) {
  return decodifica.decode(bytes);
}

/** Impronta leggibile: i primi 64 bit dello SHA-256, in quattro gruppi esadecimali. */
export async function impronta(bytes) {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  const esadecimale = [...digest.subarray(0, 8)].map(b => b.toString(16).padStart(2, "0")).join("");
  return esadecimale.match(/.{4}/g).join(" ");
}

/* ---------- AES-GCM ---------- */

export async function cifra(chiave, chiaro, aad) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const dati = new Uint8Array(await crypto.subtle.encrypt({name: "AES-GCM", iv, additionalData: testoInByte(aad)}, chiave, chiaro));
  return {iv, dati};
}

export async function decifra(chiave, iv, dati, aad) {
  return new Uint8Array(await crypto.subtle.decrypt({name: "AES-GCM", iv, additionalData: testoInByte(aad)}, chiave, dati));
}

/* ---------- Chiavi del diario in IndexedDB ---------- */

let apertura = null;

function apriDb() {
  apertura ??= new Promise((risolvi, rifiuta) => {
    const richiesta = indexedDB.open(DB_CHIAVI, 1);
    richiesta.onupgradeneeded = () => richiesta.result.createObjectStore(ARCHIVIO, {keyPath: "profilo"});
    richiesta.onsuccess = () => risolvi(richiesta.result);
    richiesta.onerror = () => rifiuta(richiesta.error);
  }).catch(e => {
    apertura = null;
    throw new Error("Il browser non consente di conservare le chiavi del diario (IndexedDB): " + e.message);
  });
  return apertura;
}

function operazione(db, modalita, lavoro) {
  return new Promise((risolvi, rifiuta) => {
    const richiesta = lavoro(db.transaction(ARCHIVIO, modalita).objectStore(ARCHIVIO));
    richiesta.onsuccess = () => risolvi(richiesta.result);
    richiesta.onerror = () => rifiuta(richiesta.error);
  });
}

/**
 * Restituisce la chiave del diario del profilo, creandola la prima volta. La chiave è generata con
 * extractable=false: nemmeno questa pagina può esportarla.
 */
export async function chiaveDiario(profilo) {
  const db = await apriDb();
  const esistente = await operazione(db, "readonly", s => s.get(profilo));
  if (esistente) return esistente;
  const chiave = await crypto.subtle.generateKey({name: "AES-GCM", length: 256}, false, ["encrypt", "decrypt"]);
  const record = {profilo, chiave, id: crypto.randomUUID(), creataAlle: new Date().toISOString()};
  try {
    await operazione(db, "readwrite", s => s.add(record));
    return record;
  } catch {
    // Un'altra scheda ha creato la chiave nello stesso momento: vale la sua.
    return operazione(db, "readonly", s => s.get(profilo));
  }
}

/** Dati aggiuntivi autenticati: legano il testo cifrato al profilo, al tipo e all'id del documento. */
function aadDocumento(profilo, tipo, id) {
  return `DASS|diario|v1|${profilo}|${tipo}|${id}`;
}

/** Cifra il contenuto di un documento con la chiave del diario del profilo. */
export async function cifraDocumento(profilo, tipo, id, contenuto) {
  const {chiave} = await chiaveDiario(profilo);
  const {iv, dati} = await cifra(chiave, contenuto, aadDocumento(profilo, tipo, id));
  return {iv: base64(iv), dati: base64(dati)};
}

/**
 * Decifra un documento salvato in H2. Con `chiaveDi` si può provare la chiave di un altro profilo:
 * la verifica GCM fallisce e la funzione lancia un errore, come serve alla dimostrazione.
 */
export async function decifraDocumento(profilo, documento, chiaveDi = profilo) {
  const {chiave} = await chiaveDiario(chiaveDi);
  return decifra(chiave, daBase64(documento.iv), daBase64(documento.dati), aadDocumento(profilo, documento.tipo, documento.id));
}

/** Identificativo della chiave del diario, da mostrare al posto dei byte che non sono esportabili. */
export async function idChiaveDiario(profilo) {
  return (await chiaveDiario(profilo)).id.slice(0, 8);
}
