/**
 * Pannello della chiave Anthropic nella pagina di analisi. La chiave non ha un attributo name,
 * quindi non entra nel FormData inviato all'API: la legge solo il client Haiku nel browser.
 */
import {leggiChiave, leggiModello, MODELLO_PREDEFINITO, rimuoviChiave, salvaChiave, salvaModello} from "./locale/impostazioni.js";

const campoChiave = document.querySelector("#chiave-anthropic");
const ricorda = document.querySelector("#ricorda-chiave");
const campoModello = document.querySelector("#modello-haiku");
const salva = document.querySelector("#salva-chiave");
const rimuovi = document.querySelector("#rimuovi-chiave");
const esito = document.querySelector("#esito-chiave");
const statoChiave = document.querySelector("#stato-chiave");
const modalita = document.querySelector("#modalita-analisi");

function aggiorna() {
  const attuale = leggiChiave();
  statoChiave.textContent = attuale ? (attuale.ricordata ? "· salvata su questo dispositivo" : "· impostata per questa scheda")
    : "· non impostata";
  modalita.textContent = attuale
    ? "Con la tua chiave Haiku analizza la foto e cerca ricette online per stimare le quantità."
    : "Senza chiave l’invio salva la foto in una bozza da compilare a mano. Con la foto di esempio ricevi una proposta già pronta.";
  campoModello.placeholder = MODELLO_PREDEFINITO;
  campoModello.value = leggiModello();
  rimuovi.disabled = !attuale;
}

salva.addEventListener("click", () => {
  const chiave = campoChiave.value.trim();
  try {
    if (chiave) salvaChiave(chiave, ricorda.checked);
    salvaModello(campoModello.value);
    campoChiave.value = "";
    esito.textContent = chiave ? "Chiave salvata." : leggiChiave() ? "Modello aggiornato." : "Inserisci una chiave per attivare l’analisi con Haiku.";
  } catch (e) {
    esito.textContent = e.message;
  }
  aggiorna();
});

rimuovi.addEventListener("click", () => {
  rimuoviChiave();
  esito.textContent = "Chiave rimossa da questo browser.";
  aggiorna();
});

// Invio nei campi del pannello salva le impostazioni invece di inviare la foto.
for (const campo of [campoChiave, campoModello]) {
  campo.addEventListener("keydown", event => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    salva.click();
  });
}

aggiorna();
