/**
 * Chiave Anthropic e modello per l'analisi facoltativa con Haiku nella demo statica.
 * Sostituiscono ANTHROPIC_API_KEY e DASS_HAIKU_MODEL del backend Java. La chiave resta nel browser:
 * per la sola scheda (sessionStorage) oppure, se l'utente lo sceglie, sul dispositivo (localStorage).
 */
export const MODELLO_PREDEFINITO = "claude-haiku-4-5-20251001";
const CHIAVE = "dass.anthropic.chiave";
const MODELLO = "dass.anthropic.modello";

function archivio(dispositivo) {
  try {
    return dispositivo ? window.localStorage : window.sessionStorage;
  } catch {
    return null;
  }
}

/** Restituisce la chiave salvata e indica se è ricordata sul dispositivo, oppure null. */
export function leggiChiave() {
  for (const dispositivo of [false, true]) {
    try {
      const valore = archivio(dispositivo)?.getItem(CHIAVE);
      if (valore) return {chiave: valore, ricordata: dispositivo};
    } catch { /* Storage non disponibile: l'analisi resta manuale. */ }
  }
  return null;
}

/** Salva la chiave per la scheda corrente o, se richiesto, sul dispositivo. */
export function salvaChiave(chiave, ricorda) {
  rimuoviChiave();
  const destinazione = archivio(ricorda);
  if (!destinazione) throw new Error("Il browser non consente di salvare la chiave per questo sito.");
  destinazione.setItem(CHIAVE, chiave.trim());
}

/** Cancella la chiave da entrambi gli archivi del browser. */
export function rimuoviChiave() {
  for (const dispositivo of [false, true]) {
    try {
      archivio(dispositivo)?.removeItem(CHIAVE);
    } catch { /* Niente da rimuovere. */ }
  }
}

/** Restituisce il modello scelto oppure quello predefinito del prototipo. */
export function leggiModello() {
  try {
    return archivio(true)?.getItem(MODELLO)?.trim() || MODELLO_PREDEFINITO;
  } catch {
    return MODELLO_PREDEFINITO;
  }
}

/** Memorizza un modello diverso da quello predefinito; un valore vuoto ripristina il predefinito. */
export function salvaModello(modello) {
  const valore = modello.trim();
  try {
    if (!valore || valore === MODELLO_PREDEFINITO) archivio(true)?.removeItem(MODELLO);
    else archivio(true)?.setItem(MODELLO, valore);
  } catch { /* Resta il modello predefinito. */ }
}
