/**
 * Pannello «Scambio di chiavi»: per ogni azione della pagina mostra profilo, direzione dei messaggi,
 * impronte delle chiavi pubbliche, derivazione della chiave del canale ed esito. Al cambio di profilo si
 * svuota insieme al resto dello schermo.
 */
import {osservaScambi} from "./sicurezza/scambio.js";
import {nomeProfilo, osservaProfilo, profiloAttivo} from "./profilo.js";
import {idChiaveDiario} from "./sicurezza/cripto.js";

const orario = new Intl.DateTimeFormat("it-IT", {timeStyle: "medium"});
const ESITI = {"in corso": "In corso", riuscita: "Riuscita", errore: "Errore", annullata: "Annullata"};

function elemento(tag, testo, classe) {
  const nodo = document.createElement(tag);
  if (testo !== undefined) nodo.textContent = testo;
  if (classe) nodo.className = classe;
  return nodo;
}

function voce(lista, termine, valore) {
  lista.append(elemento("dt", termine), elemento("dd", valore));
}

export function montaPannello() {
  const pannello = document.querySelector("#pannello-scambi");
  if (!pannello) return;
  const profilo = pannello.querySelector(".profilo-pannello");
  const chiave = pannello.querySelector(".chiave-diario");
  const elenco = pannello.querySelector(".tracce");
  const vuoto = pannello.querySelector(".nessuno-scambio");
  const nodi = new Map();

  async function intestazione() {
    const id = profiloAttivo();
    profilo.textContent = nomeProfilo(id) + " (" + id + ")";
    chiave.textContent = "…";
    try {
      const codice = await idChiaveDiario(id);
      if (id === profiloAttivo()) chiave.textContent = codice + " · AES-GCM 256, non esportabile, in IndexedDB";
    } catch (e) {
      chiave.textContent = e.message;
    }
  }

  function disegna(traccia) {
    let nodo = nodi.get(traccia.id);
    if (!nodo) {
      nodo = elemento("li");
      nodi.set(traccia.id, nodo);
      // Le azioni più recenti in alto; le precedenti restano consultabili ma chiuse.
      for (const precedente of elenco.querySelectorAll("details")) precedente.open = false;
      elenco.prepend(nodo);
    }
    const dettagli = elemento("details");
    dettagli.open = nodo.querySelector("details")?.open ?? true;
    dettagli.dataset.esito = traccia.esito;
    const titolo = elemento("summary");
    titolo.append(elemento("strong", traccia.azione), " · " + orario.format(traccia.inizio) + " · ",
      elemento("span", ESITI[traccia.esito], "esito"));
    dettagli.append(titolo);

    const dati = elemento("dl");
    voce(dati, "Profilo", nomeProfilo(traccia.profilo) + " (" + traccia.profilo + ")");
    voce(dati, "Scambio", traccia.scambioId ? traccia.scambioId.slice(0, 8) + "…" : "in apertura");
    voce(dati, "Chiave pubblica del browser", traccia.improntaBrowser);
    voce(dati, "Chiave pubblica di Payara", traccia.improntaServer ?? "in attesa");
    voce(dati, "Derivazione", traccia.derivazione ?? traccia.algoritmo);
    if (traccia.scadeAlle) voce(dati, "Scadenza", orario.format(traccia.scadeAlle));
    dettagli.append(dati);

    const messaggi = elemento("ol", undefined, "messaggi");
    for (const m of traccia.messaggi) {
      const riga = elemento("li", undefined, m.verso);
      riga.append(elemento("span", m.verso === "uscita" ? "Browser → Payara" : "Payara → browser", "verso"), elemento("span", m.testo));
      messaggi.append(riga);
    }
    dettagli.append(messaggi);
    const esito = elemento("p", "Esito: " + ESITI[traccia.esito] + (traccia.dettaglioEsito ? " · " + traccia.dettaglioEsito : ""), "esito-traccia");
    dettagli.append(esito);
    nodo.replaceChildren(dettagli);
    vuoto.hidden = true;
  }

  let inizioVista = 0;

  function svuota() {
    inizioVista = Date.now();
    nodi.clear();
    elenco.replaceChildren();
    vuoto.hidden = false;
    intestazione();
  }

  osservaScambi(traccia => {
    // Una traccia aperta prima del cambio di profilo può aggiornarsi dopo: non torna sullo schermo.
    if (traccia.profilo === profiloAttivo() && traccia.inizio.getTime() >= inizioVista) disegna(traccia);
  });
  osservaProfilo(svuota);
  intestazione();
}
