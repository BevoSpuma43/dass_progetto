/**
 * Cerca corrispondenze nel catalogo tenendo conto della preparazione a cui si riferiscono i grammi.
 * Porting di it.unisi.dass.analisi.AbbinamentoCatalogo.
 */
const SINONIMI = new Map([
  ["pasta di semola", "pasta"],
  ["fusilli", "pasta"], ["pasta tipo fusilli", "pasta"],
  ["spaghetti", "pasta"], ["penne", "pasta"],
  ["salsa di pomodoro", "sugo di pomodoro"],
  ["olio d oliva", "olio di oliva"], ["olio extravergine di oliva", "olio di oliva"],
  ["olio extravergine d oliva", "olio di oliva"], ["olio evo", "olio di oliva"],
  ["farina tipo 00", "farina 00"], ["farina di tipo 00", "farina 00"],
  ["farina di grano tenero tipo 00", "farina 00"], ["farina di grano tenero 00", "farina 00"],
  ["flour 00", "farina 00"],
  ["farina tipo 0", "farina 0"], ["farina di tipo 0", "farina 0"],
  ["farina di grano tenero tipo 0", "farina 0"], ["farina di frumento tipo 0", "farina 0"],
  ["farina 0 forte", "farina 0"], ["farina forte tipo 0", "farina 0"],
  ["farina di grano tenero tipo 0 forte", "farina 0"], ["farina tipo 0 forte", "farina 0"],
  ["latte", "latte intero"], ["latte vaccino", "latte intero"],
  ["latte di mucca", "latte intero"], ["latte intero vaccino", "latte intero"],
  ["latte intero 3 25 di grassi con vitamina d aggiunta", "latte intero"],
  ["uovo", "uovo intero"], ["uova", "uovo intero"], ["uova intere", "uovo intero"],
  ["uovo intero grande grado a", "uovo intero"],
  ["burro classico", "burro"], ["burro non salato", "burro"],
  ["zucchero di canna", "zucchero di canna grezzo"],
  ["zucchero di canna chiaro", "zucchero di canna grezzo"],
  ["zucchero grezzo di canna", "zucchero di canna grezzo"],
  ["zucchero semolato bianco", "zucchero semolato"], ["zucchero bianco", "zucchero semolato"],
  ["lievito di birra secco", "lievito di birra secco attivo"],
  ["sale", "sale da cucina"], ["sale fino", "sale da cucina"]
]);
const INGREDIENTI_DA_IMPASTO = new Set(["farina 0", "farina 00", "latte intero", "burro",
  "uovo intero", "zucchero di canna grezzo", "zucchero semolato", "lievito di birra secco attivo", "sale da cucina"]);
const RUOLO = /\s*\((?:per\s+(?:l['’])?)?(?:impasto|sfogliatura|lamelle|superficie|guarnizione|spennellatura|finitura|decorazione)\b[^()]*\)/giu;
const PESO = /(?<![\p{L}\d.,-])(\d+(?:[.,]\d+)?)\s*(kg|grammi|grammo|gr|g)\b/gu;
const PREPARAZIONE_FINALE = / (?:a |da )?(?:crud[oaie]|cott[oaie])$/u;

/** Minuscole senza accenti, con la punteggiatura ridotta a spazi. */
export function normalizza(testo) {
  return testo.toLowerCase().normalize("NFD").replace(/\p{M}+/gu, "")
    .replace(/[^\p{L}\p{N}]+/gu, " ").trim();
}

/** Rimuove i ruoli come «guarnizione» e il suffisso crudo o cotto: la preparazione è confrontata a parte. */
function nomeSenzaPreparazione(nome) {
  return normalizza(nome.replace(RUOLO, " ")).replace(PREPARAZIONE_FINALE, "");
}

/** Nome canonico dell'ingrediente, con i sinonimi riconosciuti dal catalogo. */
function alimento(nome) {
  const base = nomeSenzaPreparazione(nome);
  return SINONIMI.get(base) ?? base;
}

/** Filtro del catalogo: i numeri devono coincidere per intero, così farina 0 e farina 00 restano distinte. */
export function corrispondeAllaRicerca(nome, preparazione, filtro) {
  const cerca = normalizza(filtro);
  const testo = " " + normalizza(nome + " " + preparazione) + " ";
  if (alimento(filtro) === alimento(nome)) return true;
  return cerca.split(" ").every(parola => /^\d+$/.test(parola)
    ? testo.includes(" " + parola + " ") : testo.includes(parola));
}

/**
 * Conserva gli abbinamenti esistenti e le scelte manuali. Propone una voce compatibile per nome e
 * preparazione solo se è univoca e segnala le possibili approssimazioni.
 */
export function suggerisci(riga, catalogo) {
  if (riga.sceltaCatalogoManuale === true || typeof riga.voceCatalogoId === "string") return riga;
  const originale = riga.nome;
  const nome = alimento(originale);
  const stato = typeof riga.stato === "string" ? riga.stato : "non_noto";
  const esito = {...riga};
  delete esito.notaAbbinamento;
  let candidati = catalogo.filter(v => alimento(v.nome) === nome);
  if (candidati.length === 0 && nome !== "") {
    candidati = catalogo.filter(v => alimento(v.nome).startsWith(nome + " "));
  }
  let compatibili = candidati.filter(v => v.preparazione === stato);
  let nota = notaVariante(originale);
  if (compatibili.length === 0 && INGREDIENTI_DA_IMPASTO.has(nome) &&
      (stato === "non_noto" || riga.origineQuantita === "ricetta")) {
    compatibili = candidati.filter(v => v.preparazione === "crudo");
    if (compatibili.length > 0) {
      nota += "La scheda si riferisce all’ingrediente prima della cottura. Verifica che i grammi si riferiscano alla dose della ricetta.";
    }
  }
  if (compatibili.length !== 1) {
    return {...esito, voceCatalogoId: null, statoAbbinamento: candidati.length === 0 ? "sconosciuto" : "da_chiarire"};
  }
  const voce = compatibili[0];
  if (nota === "" && alimento(voce.nome) !== nome) nota = "Verifica la variante proposta dal catalogo.";
  if (nota !== "") esito.notaAbbinamento = nota.trim();
  return {...esito, voceCatalogoId: voce.id, statoAbbinamento: "abbinato"};
}

/** Spiega le assunzioni quando il nome indica una variante più specifica o più generica della scheda. */
function notaVariante(originale) {
  switch (nomeSenzaPreparazione(originale)) {
    case "latte": case "latte vaccino": case "latte di mucca":
      return "È stato proposto latte intero. Verifica la variante. ";
    case "farina 0 forte": case "farina forte tipo 0": case "farina tipo 0 forte": case "farina di grano tenero tipo 0 forte":
      return "La scheda è della farina tipo 0 generica. La forza della farina può variare. ";
    case "zucchero di canna chiaro":
      return "È stato proposto zucchero di canna grezzo. Verifica la variante. ";
    default:
      return "";
  }
}

/**
 * Assegna un ID a ogni riga proposta, cerca la scheda del catalogo e lascia da chiarire i pesi che
 * non si possono attribuire alla descrizione.
 */
export function collega(proposta, catalogo, caption) {
  const righe = proposta.ingredienti;
  const ingredienti = righe.map(riga => {
    const r = {...suggerisci(riga, catalogo), unita: "g", rigaId: crypto.randomUUID()};
    if (riga.origineQuantita === "caption" && !pesoAttribuibile(caption, riga, righe)) {
      r.quantitaG = null;
      r.origineQuantita = "da_chiarire";
      r.motivazione = "Il peso dichiarato non è attribuibile con certezza a questo ingrediente.";
    }
    return r;
  });
  return {...proposta, origineAnalisi: proposta.origineAnalisi ?? "haiku", ingredienti};
}

/** Converte il numero letto nella descrizione in grammi senza errori di arrotondamento. */
function grammiDescritti(numero, unita) {
  const [intero, decimali = ""] = numero.replace(",", ".").split(".");
  if (unita !== "kg") return Number(decimali ? intero + "." + decimali : intero);
  const cifre = decimali.padEnd(3, "0");
  return Number(intero + cifre.slice(0, 3) + (cifre.length > 3 ? "." + cifre.slice(3) : ""));
}

// Un numero presente nella descrizione, da solo, non dimostra a quale alimento appartenga.
/**
 * Controlla che il peso dichiarato compaia accanto al nome o a un sinonimo di un solo ingrediente
 * e corrisponda alla quantità proposta.
 */
function pesoAttribuibile(caption, riga, righe) {
  const nome = alimento(riga.nome);
  for (const frase of caption.toLowerCase().split(/[;\n]|,(?!\d)|\b(?:e|con)\b/u)) {
    const pesi = [...frase.matchAll(PESO)];
    if (pesi.length === 0) continue;
    const peso = pesi[0];
    const grammi = grammiDescritti(peso[1], peso[2]);
    const prima = normalizza(frase.slice(0, peso.index));
    const dopo = normalizza(frase.slice(peso.index + peso[0].length)).replace(/^di /u, "");
    if (pesi.length > 1 || grammi !== Number(riga.quantitaG)) continue;
    const parole = " " + normalizza(frase) + " ";
    const citate = righe.filter(i => {
      const base = alimento(i.nome);
      return parole.includes(" " + base + " ") || parole.includes(" " + normalizza(i.nome) + " ")
        || [...SINONIMI].some(([alias, valore]) => valore === base && parole.includes(" " + alias + " "));
    });
    if (citate.length !== 1 || alimento(citate[0].nome) !== nome) continue;
    const nomi = new Set([nome, normalizza(riga.nome)]);
    for (const [alias, base] of SINONIMI) if (base === nome) nomi.add(alias);
    const primaDelPeso = prima.replace(PREPARAZIONE_FINALE, "");
    for (const forma of nomi) {
      if ((" " + primaDelPeso).endsWith(" " + forma) || (dopo + " ").startsWith(forma + " ")) return true;
    }
  }
  return false;
}
