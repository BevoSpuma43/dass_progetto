/**
 * Catalogo nutrizionale della demo statica: le schede della fixture più le schede complete di
 * macros_tables/catalogo_nutrizionale_it.json, come in it.unisi.dass.persistenza.CatalogoUsda.
 */
import {ErroreAnalisi} from "./errore.js";
import {VOCI_DEMO} from "./dati-demo.js";

const RISORSA = new URL("../../data/catalogo_nutrizionale_it.json", import.meta.url);
let caricamento = null;

/** Carica il catalogo una sola volta per pagina e lo restituisce ordinato per nome e ID. */
export function catalogo() {
  caricamento ??= carica().catch(e => {
    caricamento = null;
    throw e;
  });
  return caricamento;
}

async function carica() {
  let dati;
  try {
    const risposta = await fetch(RISORSA);
    if (!risposta.ok) throw new Error("HTTP " + risposta.status);
    dati = await risposta.json();
  } catch {
    throw new ErroreAnalisi(503, "CATALOGO_ASSENTE", "Catalogo nutrizionale non disponibile. Ricarica la pagina.");
  }
  const voci = new Map(VOCI_DEMO.map(v => [v.id, {...v, per100g: {...v.per100g}}]));
  let importati = 0;
  for (const scheda of dati.alimenti) {
    const valori = scheda.per100g;
    if (!validi(valori, "carboidratiG", "proteineG", "grassiG", "kcal")) continue;
    const esistente = voci.get(scheda.id);
    if (esistente) {
      // Come nel backend, una scheda già presente aggiorna solo nome e preparazione.
      esistente.nome = scheda.nome;
      esistente.preparazione = scheda.preparazione;
    } else {
      voci.set(scheda.id, {
        id: scheda.id, nome: scheda.nome, preparazione: scheda.preparazione, versione: scheda.versione,
        unita: "g", fonte: scheda.fonte, indiceGlicemico: "non_disponibile",
        per100g: {carboidratiG: valori.carboidratiG, proteineG: valori.proteineG, grassiG: valori.grassiG, kcal: valori.kcal}
      });
    }
    importati++;
  }
  if (importati === 0) throw new ErroreAnalisi(503, "CATALOGO_VUOTO", "Il catalogo non contiene schede complete.");
  return [...voci.values()].sort((a, b) => confronta(a.nome, b.nome) || confronta(a.id, b.id));
}

/** Valori nutrizionali presenti, numerici, finiti e non negativi. */
function validi(valori, ...chiavi) {
  return valori !== null && typeof valori === "object" &&
    chiavi.every(k => typeof valori[k] === "number" && Number.isFinite(valori[k]) && valori[k] >= 0);
}

/** Ordine per unità UTF-16, come il confronto delle stringhe in H2. */
function confronta(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}
