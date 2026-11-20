/**
 * Controlla e ricodifica le foto caricate e genera le miniature del diario.
 * Porting di it.unisi.dass.analisi.FotoPiatto: il browser ridisegna i pixel in un nuovo JPEG,
 * senza nome del file né metadati EXIF.
 */
import {ErroreAnalisi} from "./errore.js";

export const MAX_BYTES = 5 * 1024 * 1024;
const LATO = 1280;
const LATO_MINIATURA = 120;

/** Normalizza la foto del piatto in un JPEG con lato massimo di 1280 pixel. */
export function prepara(file) {
  return ridimensiona(file, LATO);
}

/** Crea una miniatura con lato massimo di 120 pixel, mantenendo le proporzioni. */
export function miniatura(jpeg) {
  return ridimensiona(jpeg, LATO_MINIATURA);
}

/**
 * Accetta JPEG e PNG leggibili, scarta risoluzioni eccessive prima di decodificare i pixel e
 * ricodifica l'immagine in JPEG su sfondo bianco.
 */
async function ridimensiona(blob, latoMassimo) {
  const byte = new Uint8Array(await blob.arrayBuffer());
  const dimensioni = dimensioniPng(byte) ?? dimensioniJpeg(byte);
  if (!dimensioni) throw formato();
  if (dimensioni.larghezza * dimensioni.altezza > 40_000_000 || Math.max(dimensioni.larghezza, dimensioni.altezza) > 20000) {
    throw new ErroreAnalisi(413, "FOTO_GRANDE", "Riduci la risoluzione della foto.");
  }
  let immagine;
  try {
    immagine = await createImageBitmap(blob);
  } catch {
    throw formato();
  }
  try {
    const scala = Math.min(1, latoMassimo / Math.max(immagine.width, immagine.height));
    const tela = document.createElement("canvas");
    tela.width = Math.max(1, Math.round(immagine.width * scala));
    tela.height = Math.max(1, Math.round(immagine.height * scala));
    const grafica = tela.getContext("2d");
    grafica.fillStyle = "#fff";
    grafica.fillRect(0, 0, tela.width, tela.height);
    grafica.imageSmoothingQuality = "high";
    grafica.drawImage(immagine, 0, 0, tela.width, tela.height);
    return await new Promise((risolvi, rifiuta) =>
      tela.toBlob(jpeg => (jpeg ? risolvi(jpeg) : rifiuta(formato())), "image/jpeg", 0.85));
  } finally {
    immagine.close();
  }
}

/** Larghezza e altezza dall'intestazione IHDR di un PNG. */
function dimensioniPng(b) {
  const firma = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (b.length < 24 || !firma.every((v, i) => b[i] === v)) return null;
  const vista = new DataView(b.buffer, b.byteOffset, b.byteLength);
  return {larghezza: vista.getUint32(16), altezza: vista.getUint32(20)};
}

/** Larghezza e altezza dal primo segmento SOF di un JPEG. */
function dimensioniJpeg(b) {
  if (b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) return null;
  let i = 2;
  while (i + 9 < b.length) {
    if (b[i] !== 0xff) return null;
    const marcatore = b[i + 1];
    if (marcatore === 0xff) { i++; continue; }
    if (marcatore === 0x01 || (marcatore >= 0xd0 && marcatore <= 0xd8)) { i += 2; continue; }
    const lunghezza = (b[i + 2] << 8) | b[i + 3];
    if (marcatore >= 0xc0 && marcatore <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marcatore)) {
      return {altezza: (b[i + 5] << 8) | b[i + 6], larghezza: (b[i + 7] << 8) | b[i + 8]};
    }
    i += 2 + lunghezza;
  }
  return null;
}

/** Errore HTTP 415 per un'immagine che non può essere letta come JPEG o PNG. */
function formato() {
  return new ErroreAnalisi(415, "FOTO_ILLEGGIBILE", "Scegli una foto JPEG o PNG leggibile.");
}
