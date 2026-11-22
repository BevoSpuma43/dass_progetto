/**
 * Bolo prandiale con la formula
 *   bolo = carboidrati / ICR + (glicemia attuale − glicemia obiettivo) / ISF
 * ICR: grammi di carboidrati coperti da 1 unità di insulina. ISF: mg/dL di glicemia abbassati da 1 unità.
 * Il calcolo è didattico: non considera l'insulina ancora attiva, l'attività fisica o le indicazioni del
 * diabetologo, e non è una prescrizione.
 */
export const GLICEMIA_OBIETTIVO_PREDEFINITA = 120;
export const FORMULA = "bolo = carboidrati / ICR + (glicemia attuale − glicemia obiettivo) / ISF";

export const LIMITI = {
  icr: {min: 1, max: 150, nome: "ICR", unita: "g/U"},
  isf: {min: 5, max: 500, nome: "ISF", unita: "mg/dL per U"},
  glicemiaAttuale: {min: 20, max: 600, nome: "Glicemia attuale", unita: "mg/dL"},
  glicemiaObiettivo: {min: 70, max: 250, nome: "Glicemia obiettivo", unita: "mg/dL"}
};

/** Arrotonda al millesimo per conservare il risultato senza rumore in virgola mobile. */
function arrotonda(valore) {
  return Math.round(valore * 1000) / 1000;
}

/** Messaggio di errore per un valore fuori dai limiti, oppure null se è valido. */
export function controlla(campo, valore) {
  const limite = LIMITI[campo];
  if (typeof valore !== "number" || !Number.isFinite(valore)) return "Indica " + limite.nome + " in " + limite.unita + ".";
  if (valore < limite.min || valore > limite.max) {
    return limite.nome + " deve essere tra " + limite.min + " e " + limite.max + " " + limite.unita + ".";
  }
  return null;
}

/** Controlla ICR, ISF e glicemia obiettivo salvati nella Home. */
export function controllaParametri({icr, isf, glicemiaObiettivo}) {
  return [controlla("icr", icr), controlla("isf", isf), controlla("glicemiaObiettivo", glicemiaObiettivo)].filter(Boolean);
}

/**
 * Applica la formula. Restituisce le due quote, il totale della formula e il bolo calcolato, che non
 * scende sotto zero e vale zero con la glicemia sotto 70 mg/dL.
 */
export function calcolaBolo({carboidratiG, icr, isf, glicemiaAttuale, glicemiaObiettivo}) {
  const errori = [controlla("icr", icr), controlla("isf", isf), controlla("glicemiaAttuale", glicemiaAttuale),
    controlla("glicemiaObiettivo", glicemiaObiettivo)].filter(Boolean);
  if (!(carboidratiG >= 0)) errori.push("I carboidrati del pasto non sono disponibili.");
  if (errori.length > 0) return {valido: false, errori};

  const quotaPasto = carboidratiG / icr;
  const correzione = (glicemiaAttuale - glicemiaObiettivo) / isf;
  const totale = quotaPasto + correzione;
  const avvisi = [];
  const ipoglicemia = glicemiaAttuale < 70;
  if (ipoglicemia) avvisi.push("Glicemia sotto 70 mg/dL: prima va trattata l’ipoglicemia. Il prototipo non calcola un bolo.");
  if (!ipoglicemia && correzione < 0) avvisi.push("La glicemia è sotto l’obiettivo: la correzione è negativa e riduce il bolo.");
  if (!ipoglicemia && totale <= 0) avvisi.push("Il risultato della formula non è positivo: il bolo calcolato è 0 U.");
  if (glicemiaAttuale > 250) avvisi.push("Glicemia sopra 250 mg/dL: segui le indicazioni del diabetologo (chetoni, correzioni).");
  return {
    valido: true, formula: FORMULA, carboidratiG: arrotonda(carboidratiG), icr, isf, glicemiaAttuale, glicemiaObiettivo,
    quotaPasto: arrotonda(quotaPasto), correzione: arrotonda(correzione), totale: arrotonda(totale),
    boloCalcolato: ipoglicemia ? 0 : Math.max(0, arrotonda(totale)), avvisi
  };
}
