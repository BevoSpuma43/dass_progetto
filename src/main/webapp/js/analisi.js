/**
 * Analisi del piatto, correzione della bozza, calcolo di carboidrati e bolo, registrazione del pasto.
 *
 * Foto e descrizione raggiungono Payara (e Claude) solo con il consenso esplicito, dentro il canale
 * cifrato dello scambio di chiavi. Bozza, foto, miniatura e pasto vengono salvati solo dopo essere
 * stati cifrati nel browser con la chiave del diario; totali nutrizionali e bolo sono calcolati qui.
 */
import {annullata, contesto, montaSelettore, nomeProfilo, osservaProfilo, valido, verifica} from "./profilo.js";
import {montaPannello} from "./pannello-scambi.js";
import {azione, id} from "./diario.js";
import {base64} from "./sicurezza/cripto.js";
import * as foto from "./logica/foto.js";
import {collega} from "./logica/abbinamento.js";
import {prepara as preparaRighe} from "./logica/correzione.js";
import {calcola} from "./logica/anteprima.js";
import {GLICEMIA_OBIETTIVO_PREDEFINITA, calcolaBolo, controlla, controllaParametri} from "./logica/bolo.js";

const $ = selettore => document.querySelector(selettore);
const form = $("#analisi");
const editor = $("#correzioni");
const messaggio = $("#messaggio");
const risultato = $("#risultato");
const righe = $("#righe");
const salvataggio = $("#salvataggio");
const anteprima = $("#anteprima");
const nutrizione = $("#nutrizione");
const sezioneBolo = $("#bolo");
const formBolo = $("#form-bolo");
const registrazione = $("#registrazione");
const confermaPasto = $("#conferma-pasto");
const statoRegistrazione = $("#stato-registrazione");
const riprovaSalvataggio = $("#riprova-salvataggio");
const nuovaAnalisi = $("#nuova-analisi");
const numero = new Intl.NumberFormat("it-IT", {maximumFractionDigits: 2});
const unita = new Intl.NumberFormat("it-IT", {minimumFractionDigits: 1, maximumFractionDigits: 1});
const nutrienti = [["Carboidrati", "carboidratiG", "g"], ["Proteine", "proteineG", "g"], ["Grassi", "grassiG", "g"], ["Energia", "kcal", "kcal"]];
// Errori che riguardano la richiesta stessa: in questi casi non si crea una bozza manuale.
const ERRORI_DI_INVIO = new Set(["CONSENSO_MANCANTE", "FOTO_ASSENTE", "FOTO_ILLEGGIBILE", "UPLOAD_GRANDE", "CAPTION_LUNGA",
  "CORPO_GRANDE", "CORPO_NON_VALIDO", "RISPOSTA_NON_AUTENTICA", "RISPOSTA_INATTESA"]);

let catalogo = null;
let impostazioni = null;
let urlAnteprima = null;
let urlFoto = null;
let calcolo = null;
let boloCorrente = null;
/** Bozza aperta: contenuto, foto e stato del salvataggio. Diventa null al cambio di profilo. */
let corrente = null;

function elemento(tag, testo) {
  const nodo = document.createElement(tag);
  nodo.textContent = testo;
  return nodo;
}

function stato(nodo, testo, tipo) {
  nodo.textContent = testo;
  nodo.dataset.stato = tipo ?? "";
}

function blocca(valore) {
  for (const f of [form, editor, confermaPasto, formBolo]) for (const controllo of f.elements) controllo.disabled = valore;
  riprovaSalvataggio.disabled = valore;
  // Una bozza registrata resta in sola lettura.
  if (!valore && corrente?.registrato) {
    for (const controllo of editor.elements) controllo.disabled = true;
    for (const controllo of confermaPasto.elements) controllo.disabled = true;
  }
}

function obiettivoPredefinito() {
  return impostazioni?.glicemiaObiettivo ?? GLICEMIA_OBIETTIVO_PREDEFINITA;
}

function togliBozzaDallUrl() {
  const url = new URL(location.href);
  url.searchParams.delete("bozza");
  history.replaceState(null, "", url);
}

/** Svuota lo schermo: dati, foto, campi del modulo e calcoli del profilo precedente. */
function pulisci() {
  corrente = null;
  impostazioni = null;
  calcolo = null;
  boloCorrente = null;
  for (const url of [urlAnteprima, urlFoto]) if (url) URL.revokeObjectURL(url);
  urlAnteprima = urlFoto = null;
  form.reset();
  form.hidden = false;
  anteprima.hidden = true;
  anteprima.removeAttribute("src");
  $("#foto-bozza").removeAttribute("src");
  form.elements.glicemiaObiettivo.value = GLICEMIA_OBIETTIVO_PREDEFINITA;
  for (const sezione of [risultato, nutrizione, sezioneBolo, registrazione, $("#dati-bozza")]) sezione.hidden = true;
  righe.replaceChildren();
  $("#dubbi").replaceChildren();
  $("#json").textContent = "";
  riprovaSalvataggio.hidden = nuovaAnalisi.hidden = true;
  $("#pasto-registrato").hidden = true;
  for (const nodo of [messaggio, salvataggio, statoRegistrazione]) stato(nodo, "");
  confermaPasto.reset();
  formBolo.reset();
}

async function caricaCatalogo() {
  if (catalogo) return;
  const risposta = await fetch("api/catalogo", {cache: "no-store"});
  if (!risposta.ok) throw new Error("Catalogo degli alimenti non disponibile.");
  catalogo = await risposta.json();
}

function parametriValidi(documento) {
  if (!documento || documento.illeggibile) return null;
  return controllaParametri(documento.contenuto).length === 0 ? documento.contenuto : null;
}

/* ---------- Righe dell'editor ---------- */

function annullaCalcolo() {
  nutrizione.hidden = sezioneBolo.hidden = registrazione.hidden = true;
  calcolo = null;
  boloCorrente = null;
}

function preparazioneCatalogo(statoVoce) {
  return statoVoce === "non_noto" ? "preparazione non specificata" : statoVoce;
}

function normalizzaRicerca(testo) {
  return testo.toLocaleLowerCase("it-IT").normalize("NFD").replace(/\p{M}/gu, "").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
}

function riga(i = {}) {
  annullaCalcolo();
  const campo = $("#riga").content.firstElementChild.cloneNode(true);
  campo.dataset.rigaId = i.rigaId || "";
  campo.dataset.stato = i.stato || "non_noto";
  if (i.sceltaCatalogoManuale) campo.dataset.sceltaCatalogoManuale = "true";
  campo.querySelector("[name=nome]").value = i.nome || "";
  campo.querySelector("[name=grammi]").value = i.quantitaG ?? "";
  const voce = campo.querySelector("select");
  const filtro = campo.querySelector(".filtro-catalogo");
  function mostraVoci(selezionata) {
    const parole = normalizzaRicerca(filtro.value).split(" ");
    const opzioni = [new Option("Da chiarire", "")];
    for (const v of catalogo) {
      const etichetta = v.nome + " – " + preparazioneCatalogo(v.preparazione);
      const testo = " " + normalizzaRicerca(etichetta) + " ";
      if (v.id === selezionata || parole.every(parola => testo.includes(/^\d+$/.test(parola) ? " " + parola + " " : parola))) {
        opzioni.push(new Option(etichetta, v.id));
      }
    }
    voce.replaceChildren(...opzioni);
    voce.value = opzioni.some(opzione => opzione.value === selezionata) ? selezionata : "";
  }
  mostraVoci(i.voceCatalogoId || "");
  filtro.addEventListener("input", () => mostraVoci(voce.value));
  filtro.addEventListener("keydown", evento => { if (evento.key === "Enter") evento.preventDefault(); });
  const schedaAssociata = campo.querySelector(".scheda-associata");
  const notaAbbinamento = campo.querySelector(".nota-abbinamento");
  function mostraScheda() {
    const selezione = voce.selectedOptions[0];
    const daVerificare = voce.value && i.notaAbbinamento && !campo.dataset.sceltaCatalogoManuale;
    schedaAssociata.textContent = voce.value ? "Scheda: " + selezione.label + (daVerificare ? " · Da verificare" : "") : "Nessuna scheda associata";
    notaAbbinamento.hidden = !daVerificare;
    notaAbbinamento.textContent = daVerificare ? i.notaAbbinamento : "";
  }
  mostraScheda();
  const origini = {caption: "Peso dalla descrizione", stimata: "Stima di Claude", ricetta: "Stima da ricetta",
    corretta: "Peso inserito manualmente", da_chiarire: "Peso da chiarire"};
  const nota = [origini[i.origineQuantita], i.stato === "non_noto" ? "Preparazione da chiarire" : i.stato,
    i.statoAbbinamento === "sconosciuto" ? "Nessuna scheda associata" : i.statoAbbinamento === "da_chiarire" ? "Abbinamento da verificare"
      : i.origineAbbinamento !== "claude" ? "" : i.schedaApprossimata === true ? "Scheda più simile scelta da Claude." : "Scheda scelta da Claude."];
  const provenienza = campo.querySelector(".provenienza");
  provenienza.textContent = nota.slice(0, 2).filter(Boolean).join(" · ");
  const spiegazione = campo.querySelector(".motivazione");
  spiegazione.textContent = [i.motivazione, nota[2]].filter(Boolean).join(" ");
  if (i.fonteUrl) {
    const link = elemento("a", i.fonteTitolo || "Ricetta consultata");
    link.href = i.fonteUrl;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    spiegazione.append(" ", link);
  }
  const peso = campo.querySelector("[name=grammi]");
  peso.setAttribute("aria-label", "Grammi per " + (i.nome || "il nuovo ingrediente"));
  peso.addEventListener("input", () => {
    const uguale = (peso.value === "" ? null : Number(peso.value)) === (i.quantitaG ?? null);
    provenienza.textContent = uguale ? nota.slice(0, 2).filter(Boolean).join(" · ") : (peso.value === "" ? "Peso da chiarire" : "Peso modificato");
    spiegazione.hidden = !uguale;
  });
  voce.addEventListener("change", () => {
    campo.dataset.sceltaCatalogoManuale = "true";
    mostraScheda();
    const nome = campo.querySelector("[name=nome]");
    if (!nome.value.trim() && voce.value) nome.value = catalogo.find(v => v.id === voce.value).nome;
  });
  campo.querySelector(".rimuovi").addEventListener("click", () => {
    campo.remove();
    annullaCalcolo();
  });
  righe.append(campo);
}

function leggiRighe() {
  return [...righe.children].map(campo => {
    const valore = campo.querySelector("[name=grammi]").value;
    return {rigaId: campo.dataset.rigaId, nome: campo.querySelector("[name=nome]").value.trim(), stato: campo.dataset.stato,
      voceCatalogoId: campo.querySelector("select").value || null,
      sceltaCatalogoManuale: campo.dataset.sceltaCatalogoManuale === "true",
      quantitaG: valore === "" ? null : Number(valore)};
  });
}

/** Glicemie del riquadro del bolo, se valide; altrimenti quelle già salvate nella bozza. */
function leggiGlicemie(bozza) {
  const attuale = formBolo.elements.glicemiaAttuale.valueAsNumber;
  const obiettivo = formBolo.elements.glicemiaObiettivo.valueAsNumber;
  return {
    glicemiaAttuale: controlla("glicemiaAttuale", attuale) ? bozza.glicemiaAttuale : attuale,
    glicemiaObiettivo: controlla("glicemiaObiettivo", obiettivo) ? bozza.glicemiaObiettivo : obiettivo
  };
}

/* ---------- Bozza ---------- */

function nuovaBozza(uuid, proposta, errore, caption, glicemiaAttuale, glicemiaObiettivo) {
  const ora = new Date().toISOString();
  const collegata = proposta ? collega(proposta, catalogo, caption) : {ingredienti: [], dubbi: []};
  return {
    versione: 1, bozzaId: uuid, creataAlle: ora, aggiornataAlle: ora, caption,
    modello: proposta?.modello ?? null, origineAnalisi: proposta ? "claude" : "manuale",
    messaggioAnalisi: errore ? errore.message : null,
    dubbi: collegata.dubbi ?? [], ingredienti: collegata.ingredienti,
    fotoId: id.foto(uuid), miniaturaId: id.miniatura(uuid), glicemiaAttuale, glicemiaObiettivo
  };
}

async function salvaUnaVolta(sessione, tipo, idDocumento, byte) {
  try {
    await sessione.salva(tipo, idDocumento, byte, 0);
  } catch (e) {
    // Un tentativo precedente l'ha già salvata: l'id è legato alla bozza.
    if (e.codice !== "DOCUMENTO_ESISTENTE") throw e;
  }
}

/** Salva le parti mancanti della bozza nello stesso canale: foto, miniatura e contenuto. */
async function salvaTutto(sessione, lavoro) {
  const b = lavoro.bozza;
  if (!lavoro.salvati.foto && lavoro.fotoJpeg) {
    await salvaUnaVolta(sessione, "foto", b.fotoId, lavoro.fotoJpeg);
    lavoro.salvati.foto = true;
  }
  if (!lavoro.salvati.miniatura && lavoro.miniaturaJpeg) {
    await salvaUnaVolta(sessione, "miniatura", b.miniaturaId, lavoro.miniaturaJpeg);
    lavoro.salvati.miniatura = true;
  }
  const idBozza = id.bozza(b.bozzaId);
  let ricevuta;
  try {
    ricevuta = await sessione.salva("bozza", idBozza, b, lavoro.revisioneBozza);
  } catch (e) {
    if (e.codice !== "DOCUMENTO_ESISTENTE" || lavoro.revisioneBozza !== 0) throw e;
    // La prima scrittura era arrivata ma la risposta no: si riparte dalla revisione salvata.
    ricevuta = await sessione.salva("bozza", idBozza, b, (await sessione.leggi(idBozza)).revisione);
  }
  lavoro.revisioneBozza = ricevuta.revisione;
  lavoro.salvata = true;
}

function mostra() {
  const lavoro = corrente;
  const b = lavoro.bozza;
  form.hidden = true;
  nuovaAnalisi.hidden = false;
  riprovaSalvataggio.hidden = lavoro.salvata;
  if (urlFoto) URL.revokeObjectURL(urlFoto);
  urlFoto = lavoro.fotoJpeg ? URL.createObjectURL(new Blob([lavoro.fotoJpeg], {type: "image/jpeg"})) : null;
  const immagine = $("#foto-bozza");
  immagine.hidden = !urlFoto;
  if (urlFoto) immagine.src = urlFoto;
  $("#descrizione-bozza").textContent = [b.caption || "Nessuna descrizione",
    b.modello ? "Proposta di " + b.modello : "Bozza compilata a mano"].join(" · ");
  righe.replaceChildren();
  b.ingredienti.forEach(riga);
  $("#dubbi").replaceChildren(...b.dubbi.map(d => elemento("li", d)));
  $("#json").textContent = JSON.stringify(b, null, 2);
  $("#dati-bozza").hidden = false;
  formBolo.elements.glicemiaAttuale.value = b.glicemiaAttuale ?? "";
  formBolo.elements.glicemiaObiettivo.value = b.glicemiaObiettivo ?? obiettivoPredefinito();
  risultato.hidden = false;
  if (lavoro.salvata && !lavoro.registrato) {
    const url = new URL(location.href);
    url.searchParams.set("bozza", b.bozzaId);
    history.replaceState(null, "", url);
  }
}

/* ---------- Calcolo e bolo ---------- */

function mostraCalcolo() {
  const dati = calcola(corrente.bozza.ingredienti, catalogo);
  $("#mancanti").replaceChildren(...dati.daCompletare.map(t => elemento("li", t)));
  const completo = dati.stato === "completa";
  $("#calcolo-completo").hidden = !completo;
  nutrizione.hidden = false;
  calcolo = completo ? dati : null;
  if (completo) {
    $("#totali").replaceChildren(...nutrienti.flatMap(([nome, chiave, u]) =>
      [elemento("dt", nome), elemento("dd", numero.format(dati.totali[chiave]) + " " + u)]));
    $("#contributi").replaceChildren(...dati.righe.map(r => {
      const v = r.catalogo;
      const macro = nutrienti.map(([nome, k, u]) => nome + " " + numero.format(r.contributo[k]) + " " + u).join(", ");
      const ig = v.indiceGlicemico === "non_disponibile" ? "non disponibile" : v.indiceGlicemico;
      return elemento("li", v.nome + " (" + preparazioneCatalogo(v.preparazione) + "), " + numero.format(r.quantitaG) + " g. " +
        macro + ". IG " + ig + ". Fonte: " + v.fonte + " (" + v.versione + ").");
    }));
    sezioneBolo.hidden = false;
    aggiornaBolo();
    registrazione.hidden = false;
    preparaCampiPasto();
  }
  (completo ? sezioneBolo : nutrizione).scrollIntoView({block: "nearest", behavior: "smooth"});
}

function segno(valore) {
  return valore < 0 ? " − " + unita.format(-valore) : " + " + unita.format(valore);
}

function aggiornaBolo() {
  if (!calcolo) return;
  const parametri = $("#parametri-bolo");
  const risultatoBolo = $("#risultato-bolo");
  boloCorrente = null;
  if (!impostazioni) {
    const link = elemento("a", "imposta ICR e ISF nella Home");
    link.href = "./";
    parametri.replaceChildren("Per calcolare il bolo di " + nomeProfilo() + " ", link, ".");
    risultatoBolo.hidden = true;
    return;
  }
  const {icr, isf} = impostazioni;
  parametri.textContent = "ICR " + numero.format(icr) + " g/U · ISF " + numero.format(isf) + " mg/dL per U · dai parametri del profilo " + nomeProfilo();
  risultatoBolo.hidden = false;
  const esito = calcolaBolo({carboidratiG: calcolo.totali.carboidratiG, icr, isf,
    glicemiaAttuale: formBolo.elements.glicemiaAttuale.valueAsNumber,
    glicemiaObiettivo: formBolo.elements.glicemiaObiettivo.valueAsNumber});
  const sostituzione = risultatoBolo.querySelector(".sostituzione");
  const totale = risultatoBolo.querySelector(".totale-bolo strong");
  const avvisi = risultatoBolo.querySelector(".avvisi");
  if (!esito.valido) {
    sostituzione.textContent = esito.errori.join(" ");
    totale.textContent = "— U";
    avvisi.replaceChildren();
    return;
  }
  sostituzione.textContent = "bolo = " + numero.format(esito.carboidratiG) + " g / " + numero.format(icr) + " g/U + (" +
    esito.glicemiaAttuale + " − " + esito.glicemiaObiettivo + ") mg/dL / " + numero.format(isf) + " mg/dL/U = " +
    unita.format(esito.quotaPasto) + " U" + segno(esito.correzione) + " U = " + unita.format(esito.totale) + " U";
  totale.textContent = unita.format(esito.boloCalcolato) + " U";
  avvisi.replaceChildren(...esito.avvisi.map(a => elemento("li", a)));
  boloCorrente = esito;
}

function dataLocale(data) {
  return new Date(data.getTime() - data.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

function preparaCampiPasto() {
  if (!confermaPasto.elements.orario.value) confermaPasto.elements.orario.value = dataLocale(new Date());
  stato(statoRegistrazione, corrente.registrato ? statoRegistrazione.textContent : "");
}

/* ---------- Caricamento della pagina ---------- */

async function carica() {
  pulisci();
  const ctx = contesto();
  const uuid = new URLSearchParams(location.search).get("bozza");
  const conBozza = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(uuid ?? "");
  blocca(true);
  stato(messaggio, conBozza ? "Apertura della bozza cifrata…" : "Apertura del diario cifrato…", "attesa");
  try {
    await caricaCatalogo();
    verifica(ctx);
    const esito = await azione(conBozza ? "apertura-bozza" : "apertura-analisi", async sessione => {
      const parametri = await sessione.leggi(id.impostazioni(sessione.profilo));
      if (!conBozza) return {parametri};
      const documento = await sessione.leggi(id.bozza(uuid));
      const immagine = documento && !documento.illeggibile ? await sessione.leggi(documento.contenuto.fotoId) : null;
      return {parametri, documento, immagine};
    });
    impostazioni = parametriValidi(esito.parametri);
    form.elements.glicemiaObiettivo.value = obiettivoPredefinito();
    const avvisoParametri = impostazioni ? "" : " Per il bolo imposta ICR e ISF di " + nomeProfilo() + " nella Home.";
    if (!conBozza) {
      stato(messaggio, avvisoParametri.trim(), impostazioni ? "" : "attesa");
      return;
    }
    if (!esito.documento || esito.documento.illeggibile) {
      togliBozzaDallUrl();
      stato(messaggio, esito.documento ? "Questa bozza non si apre con la chiave del diario di questo browser."
        : "Bozza non trovata: potrebbe essere già stata registrata o rimossa.", "errore");
      return;
    }
    const immagine = esito.immagine && !esito.immagine.illeggibile ? esito.immagine.contenuto : null;
    corrente = {bozza: esito.documento.contenuto, fotoJpeg: immagine, miniaturaJpeg: null,
      salvati: {foto: true, miniatura: true}, revisioneBozza: esito.documento.revisione, salvata: true, registrato: false};
    mostra();
    stato(messaggio, "Bozza decifrata nel browser (revisione " + corrente.revisioneBozza + ")." + avvisoParametri, "ok");
  } catch (e) {
    if (!annullata(e)) stato(messaggio, e.message, "errore");
  } finally {
    if (valido(ctx)) blocca(false);
  }
}

/* ---------- Eventi ---------- */

form.elements.foto.addEventListener("change", () => {
  if (urlAnteprima) URL.revokeObjectURL(urlAnteprima);
  urlAnteprima = null;
  const file = form.elements.foto.files[0];
  anteprima.hidden = !file;
  if (!file) return;
  urlAnteprima = URL.createObjectURL(file);
  anteprima.src = urlAnteprima;
});

form.addEventListener("submit", async evento => {
  evento.preventDefault();
  const file = form.elements.foto.files[0];
  const caption = form.elements.caption.value.trim();
  const glicemiaAttuale = form.elements.glicemiaAttuale.valueAsNumber;
  const glicemiaObiettivo = form.elements.glicemiaObiettivo.valueAsNumber;
  const errori = [];
  if (!file) errori.push("Seleziona la foto di un piatto.");
  else if (file.size > foto.MAX_BYTES) errori.push("La foto deve pesare al massimo 5 MiB.");
  for (const [campo, valore] of [["glicemiaAttuale", glicemiaAttuale], ["glicemiaObiettivo", glicemiaObiettivo]]) {
    const errore = controlla(campo, valore);
    if (errore) errori.push(errore);
  }
  if (!form.elements.consenso.checked) errori.push("Per l’analisi serve il consenso all’invio di foto e descrizione.");
  if (errori.length > 0) {
    stato(messaggio, errori.join(" "), "errore");
    return;
  }
  const ctx = contesto();
  blocca(true);
  stato(messaggio, "Preparazione della foto nel browser…", "attesa");
  let lavoro = null;
  try {
    const jpeg = await foto.prepara(file);
    const fotoJpeg = new Uint8Array(await jpeg.arrayBuffer());
    const miniaturaJpeg = new Uint8Array(await (await foto.miniatura(jpeg)).arrayBuffer());
    if (fotoJpeg.length > foto.MAX_BYTES) throw new Error("La foto ricodificata supera 5 MiB: riduci la risoluzione.");
    await caricaCatalogo();
    verifica(ctx);
    stato(messaggio, "Claude sta analizzando la foto e cercando ricette per stimare le quantità…", "attesa");
    const uuid = crypto.randomUUID();
    await azione("caricamento", async sessione => {
      let proposta = null;
      let erroreAnalisi = null;
      try {
        proposta = await sessione.analizza(base64(fotoJpeg), caption);
      } catch (e) {
        if (annullata(e) || ERRORI_DI_INVIO.has(e.codice) || /^(SCAMBIO|BUSTA)_/.test(e.codice ?? "") || !e.codice) throw e;
        erroreAnalisi = e;
      }
      lavoro = {bozza: nuovaBozza(uuid, proposta, erroreAnalisi, caption, glicemiaAttuale, glicemiaObiettivo), fotoJpeg, miniaturaJpeg,
        salvati: {foto: false, miniatura: false}, revisioneBozza: 0, salvata: false, registrato: false};
      corrente = lavoro;
      stato(messaggio, "Cifratura nel browser e salvataggio di foto, miniatura e bozza…", "attesa");
      await salvaTutto(sessione, lavoro);
    });
    mostra();
    const b = lavoro.bozza;
    stato(messaggio, b.messaggioAnalisi ? b.messaggioAnalisi + " Foto cifrata e salvata: puoi inserire gli ingredienti a mano."
      : "Proposta di " + b.modello + " cifrata e salvata nel diario. Verifica gli ingredienti.", b.messaggioAnalisi ? "errore" : "ok");
  } catch (e) {
    if (annullata(e)) return;
    if (lavoro && corrente === lavoro && !lavoro.salvata) {
      // La proposta è arrivata ma il salvataggio no: resta sullo schermo e si può ripetere.
      mostra();
      stato(messaggio, "Bozza non ancora salvata: " + e.message, "errore");
    } else {
      stato(messaggio, e.message, "errore");
    }
  } finally {
    if (valido(ctx)) blocca(false);
  }
});

riprovaSalvataggio.addEventListener("click", async () => {
  const lavoro = corrente;
  if (!lavoro) return;
  const ctx = contesto();
  blocca(true);
  stato(messaggio, "Cifratura e salvataggio della bozza…", "attesa");
  try {
    await azione("salvataggio-bozza", sessione => salvaTutto(sessione, lavoro));
    mostra();
    stato(messaggio, "Bozza cifrata e salvata nel diario.", "ok");
  } catch (e) {
    if (!annullata(e)) stato(messaggio, "Bozza non ancora salvata: " + e.message, "errore");
  } finally {
    if (valido(ctx)) blocca(false);
  }
});

$("#aggiungi").addEventListener("click", () => {
  riga();
  righe.lastElementChild.querySelector("input").focus();
});

editor.addEventListener("input", evento => {
  if (evento.target.matches(".filtro-catalogo")) return;
  annullaCalcolo();
  stato(salvataggio, "");
});
editor.addEventListener("change", evento => {
  if (!evento.target.matches(".filtro-catalogo")) annullaCalcolo();
});

editor.addEventListener("submit", async evento => {
  evento.preventDefault();
  const lavoro = corrente;
  if (!lavoro || lavoro.registrato) return;
  const calcolaDopo = evento.submitter?.value === "calcola";
  let ingredienti;
  try {
    ingredienti = preparaRighe(leggiRighe(), lavoro.bozza.ingredienti, catalogo);
  } catch (e) {
    stato(salvataggio, e.message, "errore");
    return;
  }
  lavoro.bozza = {...lavoro.bozza, ingredienti, aggiornataAlle: new Date().toISOString(), ...leggiGlicemie(lavoro.bozza)};
  const ctx = contesto();
  blocca(true);
  annullaCalcolo();
  stato(salvataggio, "Cifratura e salvataggio della bozza…", "attesa");
  try {
    await azione("correzione-bozza", sessione => salvaTutto(sessione, lavoro));
    mostra();
    if (calcolaDopo) {
      stato(salvataggio, "Bozza cifrata e salvata. Carboidrati e bolo calcolati nel browser.", "ok");
      mostraCalcolo();
    } else {
      stato(salvataggio, "Bozza cifrata e salvata (revisione " + lavoro.revisioneBozza + ").", "ok");
    }
  } catch (e) {
    if (annullata(e)) return;
    stato(salvataggio, e.codice === "REVISIONE_SUPERATA" ? "La bozza è stata modificata in un’altra scheda: ricarica la pagina." : e.message, "errore");
  } finally {
    if (valido(ctx)) blocca(false);
  }
});

formBolo.addEventListener("input", aggiornaBolo);
formBolo.addEventListener("submit", evento => evento.preventDefault());

confermaPasto.addEventListener("submit", async evento => {
  evento.preventDefault();
  const lavoro = corrente;
  if (!lavoro || !calcolo || lavoro.registrato) return;
  const orario = new Date(confermaPasto.elements.orario.value);
  const nome = confermaPasto.elements.nomePiatto.value.trim();
  if (!Number.isFinite(orario.valueOf()) || dataLocale(orario) !== confermaPasto.elements.orario.value) {
    stato(statoRegistrazione, "Indica una data e un’ora valide per il pasto.", "errore");
    return;
  }
  if (!nome) {
    stato(statoRegistrazione, "Inserisci il nome del piatto.", "errore");
    return;
  }
  if (impostazioni && !boloCorrente) {
    stato(statoRegistrazione, "Completa le glicemie per calcolare il bolo prima di registrare il pasto.", "errore");
    return;
  }
  const b = lavoro.bozza;
  const pasto = {
    versione: 1, pastoId: id.pasto(b.bozzaId), bozzaId: b.bozzaId, nomePiatto: nome, consumatoAlle: orario.toISOString(),
    registratoAlle: new Date().toISOString(), caption: b.caption, modello: b.modello, origineAnalisi: b.origineAnalisi,
    ingredienti: calcolo.righe, totali: calcolo.totali, bolo: boloCorrente, fotoId: b.fotoId, miniaturaId: b.miniaturaId
  };
  const ctx = contesto();
  blocca(true);
  stato(statoRegistrazione, "Cifratura e registrazione del pasto…", "attesa");
  let giaRegistrato = false;
  try {
    await azione("salvataggio-pasto", async sessione => {
      try {
        await sessione.salva("pasto", pasto.pastoId, pasto, 0);
      } catch (e) {
        if (e.codice !== "DOCUMENTO_ESISTENTE") throw e;
        giaRegistrato = true;
      }
      // Il pasto contiene tutto: la bozza si chiude, foto e miniatura restano per lo storico.
      await sessione.elimina(id.bozza(b.bozzaId));
    });
    lavoro.registrato = true;
    togliBozzaDallUrl();
    stato(statoRegistrazione, giaRegistrato ? "Questo pasto era già registrato: la bozza è stata chiusa."
      : "Pasto cifrato e registrato nel diario di " + nomeProfilo() + ". La bozza è stata chiusa.", "ok");
    const link = $("#pasto-registrato");
    link.querySelector("a").href = "storico.html#" + pasto.pastoId;
    link.hidden = false;
  } catch (e) {
    if (!annullata(e)) stato(statoRegistrazione, e.message, "errore");
  } finally {
    if (valido(ctx)) blocca(false);
  }
});

$("#rimuovi-bozza").addEventListener("click", async () => {
  const lavoro = corrente;
  if (!lavoro || lavoro.registrato || !confirm("Rimuovere la bozza? Anche foto e miniatura cifrate verranno eliminate.")) return;
  const b = lavoro.bozza;
  const ctx = contesto();
  blocca(true);
  stato(salvataggio, "Rimozione in corso…", "attesa");
  try {
    await azione("rimozione-bozza", async sessione => {
      await sessione.elimina(id.bozza(b.bozzaId));
      await sessione.elimina(b.fotoId);
      await sessione.elimina(b.miniaturaId);
    });
    location.replace("./?bozza-rimossa");
  } catch (e) {
    if (!annullata(e)) stato(salvataggio, e.message, "errore");
    if (valido(ctx)) blocca(false);
  }
});

await montaSelettore();
montaPannello();
osservaProfilo(() => {
  togliBozzaDallUrl();
  carica();
});
carica();
