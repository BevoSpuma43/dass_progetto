/**
 * Profilo attivo e cambio di profilo.
 *
 * Il cambio di profilo interrompe le richieste aperte (AbortController), incrementa la generazione e
 * chiede a ogni pagina di svuotare lo schermo. Le risposte arrivate in ritardo appartengono a una
 * generazione precedente e vengono scartate prima di mostrare qualunque dato.
 */
const CHIAVE = "dass.profilo";
const PREDEFINITI = [{id: "utente1", nome: "Utente 1"}, {id: "utente2", nome: "Utente 2"}];

let profili = PREDEFINITI;
let attivo = leggiSalvato();
let generazione = 0;
let controllo = new AbortController();
const ascoltatori = new Set();

/** Errore di un'azione superata dal cambio di profilo: non va mostrato all'utente. */
export class AzioneAnnullata extends Error {
  constructor() {
    super("Azione annullata dal cambio di profilo.");
    this.name = "AzioneAnnullata";
  }
}

function leggiSalvato() {
  try {
    const salvato = localStorage.getItem(CHIAVE);
    if (/^[A-Za-z0-9_-]{1,64}$/.test(salvato ?? "")) return salvato;
  } catch { /* Senza storage vale il profilo predefinito. */ }
  return PREDEFINITI[0].id;
}

export function profiloAttivo() {
  return attivo;
}

export function nomeProfilo(id = attivo) {
  return profili.find(p => p.id === id)?.nome ?? id;
}

/** Contesto di un'azione: profilo, generazione e segnale di interruzione al momento dell'avvio. */
export function contesto() {
  return {profilo: attivo, generazione, signal: controllo.signal};
}

export function valido(ctx) {
  return ctx.generazione === generazione;
}

/** Lancia AzioneAnnullata se nel frattempo il profilo è cambiato. */
export function verifica(ctx) {
  if (!valido(ctx)) throw new AzioneAnnullata();
}

/** true per gli errori che derivano dal cambio di profilo e non vanno mostrati. */
export function annullata(errore) {
  return errore?.name === "AzioneAnnullata" || errore?.name === "AbortError";
}

export function osservaProfilo(funzione) {
  ascoltatori.add(funzione);
  return () => ascoltatori.delete(funzione);
}

function attiva(id, salva) {
  if (id === attivo) return;
  controllo.abort();
  controllo = new AbortController();
  generazione++;
  attivo = id;
  if (salva) {
    try { localStorage.setItem(CHIAVE, id); } catch { /* Il cambio vale comunque per questa scheda. */ }
  }
  const selettore = document.querySelector("#profilo");
  if (selettore) selettore.value = id;
  for (const funzione of ascoltatori) funzione(id);
}

export function cambiaProfilo(id) {
  attiva(id, true);
}

/** Collega il selettore dell'intestazione e sincronizza le altre schede aperte. */
export async function montaSelettore() {
  const selettore = document.querySelector("#profilo");
  try {
    const risposta = await fetch("api/profili", {cache: "no-store"});
    if (risposta.ok) {
      const elenco = await risposta.json();
      if (Array.isArray(elenco) && elenco.length > 0) profili = elenco.map(p => ({id: p.id, nome: p.nome}));
    }
  } catch { /* Con il server spento restano i profili predefiniti. */ }
  if (!profili.some(p => p.id === attivo)) attiva(profili[0].id, true);
  if (!selettore) return;
  selettore.replaceChildren(...profili.map(p => new Option(p.nome, p.id)));
  selettore.value = attivo;
  selettore.addEventListener("change", () => cambiaProfilo(selettore.value));
  addEventListener("storage", evento => {
    if (evento.key === CHIAVE && evento.newValue && profili.some(p => p.id === evento.newValue)) attiva(evento.newValue, false);
  });
}
