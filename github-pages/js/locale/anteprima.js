/**
 * Calcolo in sola lettura per lo scenario didattico. Porting di it.unisi.dass.servizi.AnteprimaPasto.
 * Il risultato è una simulazione su un profilo sintetico, non una dose di insulina.
 */
export const REGOLA = "demo-sintetica-v1";
export const VARIANZA_INIZIALE = 100;

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
 * Calcola contributi e totali quando tutte le righe sono complete. Per la simulazione usa i
 * carboidrati confermati o la memoria del piatto abituale.
 * @param memoria null per un piatto nuovo, altrimenti {id, revisione, inizialeG, correnteG, varianza}
 */
export function calcola(ingredienti, catalogo, profilo, memoria) {
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
    delete riga.rigaId;
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

  const risultato = {righe, daCompletare: mancanti, profilo, versioneRegola: REGOLA};
  if (mancanti.length > 0) return {...risultato, stato: "incompleta", totali: null, simulazione: null};
  // Un piatto nuovo parte dai carboidrati confermati. Per un piatto abituale
  // il calcolo usa la versione corrente della memoria, senza modificarla.
  const corrente = memoria === null ? totale.carboidratiG : memoria.correnteG;
  const simulazione = {
    scenario: memoria === null ? "prima_occorrenza" : "piatto_abituale",
    memoriaInizialeG: memoria === null ? corrente : memoria.inizialeG,
    memoriaCorrenteG: corrente,
    varianzaIniziale: VARIANZA_INIZIALE,
    varianzaCorrente: memoria === null ? VARIANZA_INIZIALE : memoria.varianza,
    unitaSimulate: arrotonda(corrente / 10)
  };
  if (memoria !== null) {
    simulazione.memoriaId = memoria.id;
    simulazione.revisioneMemoria = memoria.revisione;
  }
  return {...risultato, stato: "completa", totali: totale, simulazione};
}
