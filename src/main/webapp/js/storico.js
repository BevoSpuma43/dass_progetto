/**
 * Storico dei pasti del profilo attivo, dettaglio del pasto con la foto e vista dell'archivio H2.
 * Tutto ciò che arriva da Payara è cifrato: pasti e miniature si decifrano qui con la chiave del diario.
 */
import {annullata, contesto, montaSelettore, nomeProfilo, osservaProfilo, profiloAttivo, valido} from "./profilo.js";
import {montaPannello} from "./pannello-scambi.js";
import {azione} from "./diario.js";
import {byteInTesto, decifraDocumento, idChiaveDiario} from "./sicurezza/cripto.js";

const $ = selettore => document.querySelector(selettore);
const lista = $("#pasti");
const statoStorico = $("#stato-storico");
const riprova = $("#riprova-storico");
const modello = $("#scheda-pasto");
const dialogo = $("#dettaglio-pasto");
const tabellaH2 = $("#tabella-h2");
const statoH2 = $("#stato-h2");
const esitoProva = $("#esito-prova");
const numero = new Intl.NumberFormat("it-IT", {maximumFractionDigits: 1});
const unita = new Intl.NumberFormat("it-IT", {minimumFractionDigits: 1, maximumFractionDigits: 1});
const quando = new Intl.DateTimeFormat("it-IT", {dateStyle: "medium", timeStyle: "short"});
const nutrienti = [["Carboidrati", "carboidratiG", "g"], ["Proteine", "proteineG", "g"], ["Grassi", "grassiG", "g"], ["Energia", "kcal", "kcal"]];
const TIPI = {pasto: "pasto", bozza: "bozza", foto: "foto", miniatura: "miniatura", impostazioni: "parametri del bolo"};
let indirizzi = [];

function elemento(tag, testo, classe) {
  const nodo = document.createElement(tag);
  if (testo !== undefined) nodo.textContent = testo;
  if (classe) nodo.className = classe;
  return nodo;
}

function stato(nodo, testo, tipo) {
  nodo.textContent = testo;
  nodo.dataset.stato = tipo ?? "";
}

function urlImmagine(byte) {
  const url = URL.createObjectURL(new Blob([byte], {type: "image/jpeg"}));
  indirizzi.push(url);
  return url;
}

function descriviBolo(bolo) {
  if (!bolo) return "Bolo non calcolato: ICR e ISF non erano impostati.";
  return "Glicemia " + bolo.glicemiaAttuale + " mg/dL, obiettivo " + bolo.glicemiaObiettivo + " mg/dL · ICR " +
    numero.format(bolo.icr) + " g/U · ISF " + numero.format(bolo.isf) + " mg/dL per U";
}

/** Svuota lo schermo e libera le immagini decifrate del profilo precedente. */
function pulisci() {
  for (const url of indirizzi) URL.revokeObjectURL(url);
  indirizzi = [];
  lista.replaceChildren();
  stato(statoStorico, "");
  riprova.hidden = true;
  if (dialogo.open) dialogo.close();
  tabellaH2.hidden = true;
  tabellaH2.tBodies[0].replaceChildren();
  stato(statoH2, "");
  esitoProva.hidden = true;
  esitoProva.replaceChildren();
  for (const nodo of document.querySelectorAll(".nome-profilo")) nodo.textContent = nomeProfilo();
}

function scheda(documento, miniature) {
  const voce = modello.content.firstElementChild.cloneNode(true);
  const articolo = voce.querySelector("article");
  if (documento.illeggibile) {
    articolo.replaceChildren(elemento("p", "Pasto " + documento.id + " non decifrabile con la chiave del diario di questo browser."));
    return voce;
  }
  const pasto = documento.contenuto;
  voce.id = pasto.pastoId;
  articolo.querySelector("h2").textContent = pasto.nomePiatto;
  const orario = articolo.querySelector("time");
  orario.dateTime = pasto.consumatoAlle;
  orario.textContent = quando.format(new Date(pasto.consumatoAlle));
  const immagine = articolo.querySelector("img");
  const miniatura = miniature.get(pasto.miniaturaId);
  if (miniatura) immagine.src = urlImmagine(miniatura);
  else immagine.hidden = true;
  for (const campo of articolo.querySelectorAll("[data-valore]")) {
    campo.textContent = numero.format(pasto.totali[campo.dataset.valore]) + " " + campo.dataset.unita;
  }
  articolo.querySelector(".stima-pasto strong").textContent = pasto.bolo ? unita.format(pasto.bolo.boloCalcolato) + " U" : "—";
  articolo.querySelector(".parametri-pasto").textContent = descriviBolo(pasto.bolo);
  articolo.querySelector(".apri-pasto").addEventListener("click", () => apri(pasto));
  articolo.querySelector(".verifica-h2").addEventListener("click", () => mostraH2(pasto.pastoId));
  return voce;
}

async function carica() {
  pulisci();
  stato(statoStorico, "Apertura dello storico cifrato…", "attesa");
  try {
    const {pasti, miniature} = await azione("apertura-storico", async sessione => ({
      pasti: await sessione.elenca("pasto"),
      miniature: await sessione.elenca("miniatura")
    }));
    const mappa = new Map(miniature.filter(m => !m.illeggibile).map(m => [m.id, m.contenuto]));
    const istante = d => (d.illeggibile ? 0 : Date.parse(d.contenuto.consumatoAlle));
    lista.replaceChildren(...pasti.sort((a, b) => istante(b) - istante(a)).map(p => scheda(p, mappa)));
    stato(statoStorico, pasti.length === 0 ? "Nessun pasto registrato per " + nomeProfilo() + "." : "");
    const evidenziato = location.hash && document.getElementById(decodeURIComponent(location.hash.slice(1)));
    if (evidenziato) {
      evidenziato.classList.add("evidenziato");
      evidenziato.scrollIntoView({block: "center"});
    }
  } catch (e) {
    if (annullata(e)) return;
    stato(statoStorico, e.message, "errore");
    riprova.hidden = false;
  }
}

/** Riapre un pasto: lo rilegge e scarica la foto nella stessa azione, poi li decifra. */
async function apri(riepilogo) {
  $("#titolo-dettaglio").textContent = riepilogo.nomePiatto;
  const statoDettaglio = dialogo.querySelector(".stato-dettaglio");
  const foto = dialogo.querySelector(".foto-dettaglio");
  foto.hidden = true;
  for (const selettore of [".quando", ".record-dettaglio"]) dialogo.querySelector(selettore).textContent = "";
  for (const selettore of [".valori-pasto", ".bolo-dettaglio", ".ingredienti-dettaglio"]) dialogo.querySelector(selettore).replaceChildren();
  stato(statoDettaglio, "Apertura del pasto cifrato…", "attesa");
  dialogo.showModal();
  try {
    const {documento, immagine} = await azione("apertura-pasto", async sessione => {
      const letto = await sessione.leggi(riepilogo.pastoId);
      const fotoLetta = letto && !letto.illeggibile ? await sessione.leggi(letto.contenuto.fotoId) : null;
      return {documento: letto, immagine: fotoLetta};
    });
    if (!documento || documento.illeggibile) {
      stato(statoDettaglio, documento ? "Il pasto non si apre con la chiave di questo browser." : "Il pasto non esiste più.", "errore");
      return;
    }
    const pasto = documento.contenuto;
    stato(statoDettaglio, "");
    if (immagine && !immagine.illeggibile) {
      foto.src = urlImmagine(immagine.contenuto);
      foto.alt = "Foto di " + pasto.nomePiatto;
      foto.hidden = false;
    }
    dialogo.querySelector(".quando").textContent = quando.format(new Date(pasto.consumatoAlle)) + (pasto.caption ? " · " + pasto.caption : "");
    dialogo.querySelector(".valori-pasto").replaceChildren(...nutrienti.map(([nome, chiave, u]) => {
      const gruppo = elemento("div");
      gruppo.append(elemento("dt", nome), elemento("dd", numero.format(pasto.totali[chiave]) + " " + u));
      return gruppo;
    }));
    const bolo = dialogo.querySelector(".bolo-dettaglio");
    if (pasto.bolo) {
      const b = pasto.bolo;
      bolo.append(elemento("p", b.formula, "formula"),
        elemento("p", "bolo = " + numero.format(b.carboidratiG) + " g / " + numero.format(b.icr) + " g/U + (" + b.glicemiaAttuale + " − " +
          b.glicemiaObiettivo + ") mg/dL / " + numero.format(b.isf) + " mg/dL/U = " + unita.format(b.totale) + " U"),
        elemento("p", "Bolo calcolato: " + unita.format(b.boloCalcolato) + " U", "totale"));
      for (const avviso of b.avvisi) bolo.append(elemento("p", avviso, "avviso"));
    } else {
      bolo.append(elemento("p", descriviBolo(null)));
    }
    dialogo.querySelector(".ingredienti-dettaglio").replaceChildren(...pasto.ingredienti.map(r =>
      elemento("li", r.nome + " · " + numero.format(r.quantitaG) + " g · " + numero.format(r.contributo.carboidratiG) + " g carboidrati · " +
        (r.catalogo ? r.catalogo.nome + " (" + r.catalogo.fonte + ")" : "senza scheda"))));
    dialogo.querySelector(".record-dettaglio").textContent = "Record " + documento.id + " · revisione " + documento.revisione +
      " · decifrato con la chiave del diario di " + nomeProfilo() + ".";
  } catch (e) {
    if (!annullata(e)) stato(statoDettaglio, e.message, "errore");
  }
}

/* ---------- Vista dell'archivio H2 ---------- */

function rigaH2(record, evidenzia) {
  const riga = elemento("tr");
  if (record.id === evidenzia) riga.className = "evidenziato";
  const profilo = elemento("td");
  profilo.append(elemento("strong", record.profilo));
  if (record.profilo === profiloAttivo()) profilo.append(elemento("small", "profilo attivo"));
  const documento = elemento("td");
  documento.append(elemento("code", record.id), elemento("small", (TIPI[record.tipo] ?? record.tipo) + " · revisione " + record.revisione +
    " · " + quando.format(new Date(record.aggiornatoAlle))));
  const cifrato = elemento("td");
  cifrato.append(elemento("code", record.estratto.slice(0, 32) + "…", "estratto"), elemento("small", record.byte + " byte cifrati · IV " + record.iv));
  const azioneProva = elemento("td");
  const bottone = elemento("button", "Prova a decifrare", "secondario");
  bottone.type = "button";
  bottone.addEventListener("click", () => prova(record));
  azioneProva.append(bottone);
  riga.append(profilo, documento, cifrato, azioneProva);
  return riga;
}

async function mostraH2(evidenzia) {
  const ctx = contesto();
  $("#mostra-h2").disabled = true;
  stato(statoH2, "Lettura della tabella DOCUMENTO_CIFRATO…", "attesa");
  esitoProva.hidden = true;
  try {
    const vista = await azione("vista-h2", sessione => sessione.vistaH2(40));
    tabellaH2.tBodies[0].replaceChildren(...vista.documenti.map(r => rigaH2(r, evidenzia)));
    tabellaH2.hidden = vista.documenti.length === 0;
    stato(statoH2, vista.documenti.length === 0 ? "La tabella è vuota." :
      "Ultimi " + vista.documenti.length + " record della tabella " + vista.tabella + ", così come li conserva Payara.", "");
    $("#archivio-h2").scrollIntoView({block: "start", behavior: "smooth"});
  } catch (e) {
    if (!annullata(e)) stato(statoH2, e.message, "errore");
  } finally {
    if (valido(ctx)) $("#mostra-h2").disabled = false;
  }
}

/** Prova ad aprire un record di H2 con la chiave del diario del profilo attivo. */
async function prova(riepilogo) {
  const attivo = profiloAttivo();
  esitoProva.hidden = false;
  esitoProva.dataset.stato = "attesa";
  esitoProva.replaceChildren(elemento("p", "Lettura del record " + riepilogo.id + "…"));
  try {
    const record = await azione("prova-decifratura", sessione => sessione.recordH2(riepilogo.profilo, riepilogo.id));
    const chiave = await idChiaveDiario(attivo);
    let chiaro = null;
    try {
      chiaro = await decifraDocumento(record.profilo, record, attivo);
    } catch (e) {
      if (e.name !== "OperationError") throw e;
    }
    if (attivo !== profiloAttivo()) return;
    const titolo = "Record " + record.id + " di " + record.profilo + " con la chiave del diario di " + nomeProfilo(attivo) + " (id " + chiave + ")";
    if (!chiaro) {
      esitoProva.dataset.stato = "errore";
      esitoProva.replaceChildren(elemento("h3", titolo),
        elemento("p", "Decifratura impossibile: la verifica del tag AES-GCM fallisce. La chiave di " + nomeProfilo(attivo) +
          " non è quella con cui il browser ha cifrato il documento, e Payara conserva solo testo cifrato."));
      return;
    }
    esitoProva.dataset.stato = "ok";
    const anteprima = record.tipo === "foto" || record.tipo === "miniatura"
      ? elemento("p", "Immagine JPEG di " + chiaro.length + " byte decifrata nel browser.")
      : elemento("pre", JSON.stringify(JSON.parse(byteInTesto(chiaro)), null, 2).slice(0, 1200));
    esitoProva.replaceChildren(elemento("h3", titolo), elemento("p", "Decifratura riuscita: il documento appartiene al profilo attivo."), anteprima);
    if (record.tipo === "foto" || record.tipo === "miniatura") {
      const immagine = elemento("img");
      immagine.src = urlImmagine(chiaro);
      immagine.alt = "Immagine decifrata del record " + record.id;
      esitoProva.append(immagine);
    }
  } catch (e) {
    if (annullata(e)) return;
    esitoProva.dataset.stato = "errore";
    esitoProva.replaceChildren(elemento("p", e.message));
  }
}

dialogo.querySelector(".chiudi").addEventListener("click", () => dialogo.close());
riprova.addEventListener("click", carica);
$("#mostra-h2").addEventListener("click", () => mostraH2());

await montaSelettore();
montaPannello();
osservaProfilo(carica);
carica();
