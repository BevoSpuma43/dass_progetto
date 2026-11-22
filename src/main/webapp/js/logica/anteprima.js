/**
 * Totali nutrizionali del pasto dalle righe confermate e dalle schede del catalogo (valori per 100 g).
 * Il calcolo avviene nel browser: Payara non riceve ingredienti né valori nutrizionali in chiaro.
 */

/** Elimina il rumore dei numeri in virgola mobile senza toccare le cifre significative. */
export function arrotonda(valore) {
  return Math.round(valore * 1e9) / 1e9;
}

/** Scala i valori per 100 g in base al peso confermato di una singola riga. */
function contributo(per100g, peso) {
  return {
    carboidratiG: arrotonda(per100g.carboidratiG * peso / 100),
    proteineG: arrotonda(per100g.proteineG * peso / 100),
    grassiG: arrotonda(per100g.grassiG * peso / 100),
    kcal: arrotonda(per100g.kcal * peso / 100)
  };
}

function somma(a, b) {
  return {
    carboidratiG: arrotonda(a.carboidratiG + b.carboidratiG),
    proteineG: arrotonda(a.proteineG + b.proteineG),
    grassiG: arrotonda(a.grassiG + b.grassiG),
    kcal: arrotonda(a.kcal + b.kcal)
  };
}

/**
 * Calcola contributi e totali. Se manca una scheda del catalogo o un peso, restituisce lo stato
 * "incompleta" con l'elenco di ciò che va completato.
 */
export function calcola(ingredienti, catalogo) {
  const voci = new Map(catalogo.map(v => [v.id, v]));
  const righe = [];
  const mancanti = [];
  let totale = {carboidratiG: 0, proteineG: 0, grassiG: 0, kcal: 0};
  if (ingredienti.length === 0) mancanti.push("Aggiungi almeno un ingrediente.");

  for (const ingrediente of ingredienti) {
    const nome = ingrediente.nome;
    const voce = voci.get(typeof ingrediente.voceCatalogoId === "string" ? ingrediente.voceCatalogoId : "");
    const peso = ingrediente.quantitaG;
    const riga = {...ingrediente};
    if (!voce) mancanti.push(nome + ": scegli l’alimento e la preparazione dal catalogo.");
    if (peso === null) mancanti.push(nome + ": indica il peso in grammi.");
    if (voce) riga.catalogo = voce;
    if (voce && typeof peso === "number") {
      const parte = contributo(voce.per100g, peso);
      totale = somma(totale, parte);
      riga.contributo = parte;
    } else {
      riga.contributo = null;
    }
    righe.push(riga);
  }
  if (mancanti.length > 0) return {stato: "incompleta", righe, daCompletare: mancanti, totali: null};
  return {stato: "completa", righe, daCompletare: [], totali: totale};
}
