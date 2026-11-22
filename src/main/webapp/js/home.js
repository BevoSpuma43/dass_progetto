/**
 * Home: parametri del bolo (ICR, ISF, glicemia obiettivo) e bozze da completare del profilo attivo.
 * I parametri sono un documento cifrato «impostazioni» del diario.
 */
import {annullata, contesto, montaSelettore, nomeProfilo, osservaProfilo, valido} from "./profilo.js";
import {montaPannello} from "./pannello-scambi.js";
import {azione, id} from "./diario.js";
import {GLICEMIA_OBIETTIVO_PREDEFINITA, controllaParametri} from "./logica/bolo.js";

const form = document.querySelector("#form-parametri");
const statoParametri = document.querySelector("#stato-parametri");
const statoBozze = document.querySelector("#stato-bozze");
const elencoBozze = document.querySelector("#elenco-bozze");
const data = new Intl.DateTimeFormat("it-IT", {dateStyle: "medium", timeStyle: "short"});
let revisione = 0;

function stato(nodo, testo, tipo) {
  nodo.textContent = testo;
  nodo.dataset.stato = tipo ?? "";
}

function blocca(valore) {
  for (const controllo of form.elements) controllo.disabled = valore;
}

function pulisci() {
  revisione = 0;
  form.reset();
  form.elements.glicemiaObiettivo.value = GLICEMIA_OBIETTIVO_PREDEFINITA;
  stato(statoParametri, "");
  elencoBozze.replaceChildren();
  stato(statoBozze, "");
  for (const nodo of document.querySelectorAll(".nome-profilo")) nodo.textContent = nomeProfilo();
}

function mostraImpostazioni(documento) {
  if (!documento) {
    stato(statoParametri, "Parametri non ancora impostati per questo profilo.", "attesa");
    return;
  }
  revisione = documento.revisione;
  if (documento.illeggibile) {
    stato(statoParametri, "I parametri salvati non si aprono con la chiave di questo browser: inseriscili di nuovo.", "errore");
    return;
  }
  const {icr, isf, glicemiaObiettivo} = documento.contenuto;
  form.elements.icr.value = icr;
  form.elements.isf.value = isf;
  form.elements.glicemiaObiettivo.value = glicemiaObiettivo;
  stato(statoParametri, "Parametri decifrati nel browser (revisione " + revisione + ").", "ok");
}

function mostraBozze(bozze) {
  elencoBozze.replaceChildren(...bozze.map(b => {
    const voce = document.createElement("li");
    if (b.illeggibile) {
      voce.textContent = "Bozza " + b.id + " non decifrabile con la chiave di questo browser.";
      return voce;
    }
    const link = document.createElement("a");
    link.href = "analisi.html?bozza=" + encodeURIComponent(b.contenuto.bozzaId);
    link.textContent = b.contenuto.caption || "Bozza senza descrizione";
    const dettagli = document.createElement("small");
    const righe = b.contenuto.ingredienti.length;
    dettagli.textContent = data.format(new Date(b.contenuto.creataAlle)) + " · " + righe + (righe === 1 ? " ingrediente" : " ingredienti");
    voce.append(link, dettagli);
    return voce;
  }));
  stato(statoBozze, bozze.length === 0 ? "Nessuna bozza in sospeso." : "");
}

async function carica() {
  pulisci();
  blocca(true);
  stato(statoBozze, "Apertura del diario cifrato…", "attesa");
  try {
    const {impostazioni, bozze} = await azione("apertura-home", async sessione => ({
      impostazioni: await sessione.leggi(id.impostazioni(sessione.profilo)),
      bozze: await sessione.elenca("bozza")
    }));
    mostraImpostazioni(impostazioni);
    mostraBozze(bozze);
    blocca(false);
  } catch (e) {
    if (annullata(e)) return;
    stato(statoBozze, e.message, "errore");
    stato(statoParametri, "Parametri non disponibili finché il diario non si apre.", "errore");
  }
}

form.addEventListener("submit", async evento => {
  evento.preventDefault();
  const valori = {
    icr: form.elements.icr.valueAsNumber,
    isf: form.elements.isf.valueAsNumber,
    glicemiaObiettivo: form.elements.glicemiaObiettivo.valueAsNumber
  };
  const errori = controllaParametri(valori);
  if (errori.length > 0) {
    stato(statoParametri, errori.join(" "), "errore");
    return;
  }
  const ctx = contesto();
  blocca(true);
  stato(statoParametri, "Cifratura e salvataggio…", "attesa");
  try {
    const contenuto = {versione: 1, ...valori, aggiornateAlle: new Date().toISOString()};
    const ricevuta = await azione("salva-impostazioni", sessione =>
      sessione.salva("impostazioni", id.impostazioni(sessione.profilo), contenuto, revisione));
    revisione = ricevuta.revisione;
    stato(statoParametri, "Parametri cifrati e salvati (revisione " + revisione + ").", "ok");
  } catch (e) {
    if (annullata(e)) return;
    stato(statoParametri, e.codice === "REVISIONE_SUPERATA" || e.codice === "DOCUMENTO_ESISTENTE"
      ? "I parametri sono stati modificati in un’altra scheda: ricarica la pagina." : e.message, "errore");
  } finally {
    if (valido(ctx)) blocca(false);
  }
});

async function verificaServizio() {
  const esito = document.querySelector("#esito-servizio");
  try {
    const risposta = await fetch("api/stato", {cache: "no-store"});
    esito.textContent = risposta.ok && (await risposta.json()).stato === "ok" ? "Il backend risponde · JSON" : "Risposta inattesa";
  } catch {
    esito.textContent = "Backend non raggiungibile: avvia Payara.";
  }
}

const url = new URL(location.href);
if (url.searchParams.has("bozza-rimossa")) {
  document.querySelector("#bozza-rimossa").hidden = false;
  url.searchParams.delete("bozza-rimossa");
  history.replaceState(null, "", url);
}
await montaSelettore();
montaPannello();
osservaProfilo(() => {
  document.querySelector("#bozza-rimossa").hidden = true;
  carica();
});
verificaServizio();
carica();
