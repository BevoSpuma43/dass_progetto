const form = document.querySelector("#prova");
const editor = document.querySelector("#correzioni");
const messaggio = document.querySelector("#messaggio");
const risultato = document.querySelector("#risultato");
const righe = document.querySelector("#righe");
const salvataggio = document.querySelector("#salvataggio");
const anteprima = document.querySelector("#anteprima");
const nutrizione = document.querySelector("#nutrizione");
const registrazione = document.querySelector("#registrazione");
const confermaPasto = document.querySelector("#conferma-pasto");
const piattoPasto = confermaPasto.elements.piatto;
const nomePasto = confermaPasto.elements.nomePiatto;
const orarioPasto = confermaPasto.elements.orario;
const salvaPasto = document.querySelector("#registra-pasto");
const statoRegistrazione = document.querySelector("#stato-registrazione");
const riprovaPasto = document.querySelector("#riprova-registrazione");
const pastoRegistrato = document.querySelector("#pasto-registrato");
const numero = new Intl.NumberFormat("it-IT", {maximumFractionDigits: 2});
const nutrienti = [["Carboidrati","carboidratiG","g"], ["Proteine","proteineG","g"],
  ["Grassi","grassiG","g"], ["Energia","kcal","kcal"]];
let bozza;
let catalogo;
let urlAnteprima;
let campiBozzaId;
let mostraSceltaPasto = false;
let anteprimaPronta = false;
let operazionePasto = false;
const registrazioni = new Map();

async function json(url, opzioni) {
  const risposta = await fetch(url, opzioni);
  if (risposta.status === 204) return null;
  if (!risposta.headers.get("content-type")?.includes("application/json")) {
    throw new Error("Risposta inattesa dal backend. Verifica che Payara esegua il WAR aggiornato.");
  }
  const dati = await risposta.json();
  if (!risposta.ok && !dati.bozzaId) {
    throw Object.assign(new Error(dati.messaggio || "Richiesta non riuscita."), {http: risposta.status});
  }
  return dati;
}

function busy(formulario, valore) {
  for (const controllo of formulario.elements) controllo.disabled = valore;
}

function richiestaJson(method, dati) {
  return {method, headers: {"Content-Type": "application/json"}, body: JSON.stringify(dati)};
}

function annullaAnteprima() {
  nutrizione.hidden = true;
  mostraSceltaPasto = anteprimaPronta = false;
  aggiornaRegistrazione();
}

function preparazioneCatalogo(stato) {
  return stato === "non_noto" ? "preparazione non specificata" : stato;
}

function normalizzaRicerca(testo) {
  return testo.toLocaleLowerCase("it-IT").normalize("NFD").replace(/\p{M}/gu, "")
    .replace(/[^\p{L}\p{N}]+/gu, " ").trim();
}

function riga(i = {}) {
  annullaAnteprima();
  const campo = document.querySelector("#riga").content.firstElementChild.cloneNode(true);
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
      if (v.id === selezionata || parole.every(parola =>
        testo.includes(/^\d+$/.test(parola) ? " " + parola + " " : parola))) {
        opzioni.push(new Option(etichetta, v.id));
      }
    }
    voce.replaceChildren(...opzioni);
    voce.value = opzioni.some(opzione => opzione.value === selezionata) ? selezionata : "";
  }
  mostraVoci(i.voceCatalogoId || "");
  filtro.addEventListener("input", () => mostraVoci(voce.value));
  filtro.addEventListener("keydown", event => { if (event.key === "Enter") event.preventDefault(); });
  const schedaAssociata = campo.querySelector(".scheda-associata");
  const notaAbbinamento = campo.querySelector(".nota-abbinamento");
  function mostraScheda() {
    const selezione = voce.selectedOptions[0];
    const daVerificare = voce.value && i.notaAbbinamento && !campo.dataset.sceltaCatalogoManuale;
    schedaAssociata.textContent = voce.value
      ? "Scheda: " + selezione.label + (daVerificare ? " · Da verificare" : "") : "Nessuna scheda associata";
    notaAbbinamento.hidden = !daVerificare;
    notaAbbinamento.textContent = daVerificare ? i.notaAbbinamento : "";
  }
  mostraScheda();
  const origini = {caption: "Peso dalla descrizione", stimata: "Stima di Claude", ricetta: "Stima da ricetta",
    corretta: "Peso inserito manualmente", da_chiarire: "Peso da chiarire"};
  const nota = [origini[i.origineQuantita], i.stato === "non_noto" ? "Preparazione da chiarire" : i.stato,
    i.statoAbbinamento === "sconosciuto" ? "Nessuna scheda associata" :
    i.statoAbbinamento === "da_chiarire" ? "Abbinamento da verificare" : ""];
  const provenienza = campo.querySelector(".provenienza");
  provenienza.textContent = nota.slice(0,2).filter(Boolean).join(" · ");
  const spiegazione = campo.querySelector(".motivazione");
  spiegazione.textContent = [i.motivazione, nota[2]].filter(Boolean).join(" ");
  if (i.fonteUrl) {
    const link = elemento("a", i.fonteTitolo || "Ricetta consultata");
    link.href = i.fonteUrl;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    spiegazione.append(" ",link);
  }
  const peso = campo.querySelector("[name=grammi]");
  peso.setAttribute("aria-label","Grammi per " + (i.nome || "il nuovo ingrediente"));
  peso.addEventListener("input", () => {
    const uguale = (peso.value === "" ? null : Number(peso.value)) === (i.quantitaG ?? null);
    provenienza.textContent = uguale ? nota.slice(0,2).filter(Boolean).join(" · ") :
      (peso.value === "" ? "Peso da chiarire" : "Peso modificato");
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
    annullaAnteprima();
  });
  righe.append(campo);
}

async function mostra(dati) {
  bozza = dati;
  preparaCampiPasto();
  annullaAnteprima();
  const url = new URL(location.href);
  url.searchParams.set("bozza", dati.bozzaId);
  history.replaceState(null, "", url);
  try {
    sessionStorage.setItem("dass.ultimaBozza", JSON.stringify({id: dati.bozzaId, scade: dati.scadeAlle}));
  } catch { /* L’editor funziona anche se il browser disabilita lo storage. */ }
  catalogo ??= await json("api/catalogo", {cache: "no-store"});
  document.querySelector("#fotoBozza").href = dati.fotoUrl;
  form.elements.caption.value = dati.caption;
  anteprima.src = dati.fotoUrl;
  anteprima.hidden = false;
  righe.replaceChildren();
  dati.ingredienti.forEach(riga);
  document.querySelector("#dubbi").replaceChildren(...dati.dubbi.map(d => {
    const li = document.createElement("li");
    li.textContent = d;
    return li;
  }));
  document.querySelector("#json").textContent = JSON.stringify(dati, null, 2);
  risultato.hidden = false;
  busy(editor, Boolean(leggiRegistrazione()?.richiesta));
  messaggio.textContent = dati.messaggio ? dati.messaggio + " Foto salvata. Puoi inserire gli ingredienti manualmente."
    : dati.modello ? "Proposta di " + dati.modello + ". Verifica gli ingredienti." : "Bozza manuale.";
}

form.elements.foto.addEventListener("change", () => {
  if (urlAnteprima) URL.revokeObjectURL(urlAnteprima);
  const file = form.elements.foto.files[0];
  if (!file) return;
  risultato.hidden = true;
  salvataggio.textContent = messaggio.textContent = "";
  bozza = null;
  annullaAnteprima();
  history.replaceState(null, "", location.pathname);
  urlAnteprima = URL.createObjectURL(file);
  anteprima.src = urlAnteprima;
  anteprima.hidden = false;
});

form.addEventListener("submit", async event => {
  event.preventDefault();
  const file = form.elements.foto.files[0];
  if (!file || file.size > 5 * 1024 * 1024) {
    messaggio.textContent = "Seleziona una foto entro 5 MiB.";
    return;
  }
  const dati = new FormData(form);
  busy(form, true);
  risultato.hidden = true;
  salvataggio.textContent = "";
  messaggio.textContent = "Claude sta analizzando la foto e cercando ricette per stimare le quantità.";
  try {
    await mostra(await json("api/analisi-piatto", {method: "POST", headers: {"X-DASS-Analisi": "1"}, body: dati}));
  } catch (e) { messaggio.textContent = e.message; }
  finally { busy(form, false); }
});

document.querySelector("#aggiungi").addEventListener("click", () => {
  riga();
  righe.lastElementChild.querySelector("input").focus();
});

function elemento(tag, testo) {
  const nodo = document.createElement(tag);
  nodo.textContent = testo;
  return nodo;
}

function mostraAnteprima(dati) {
  document.querySelector("#mancanti").replaceChildren(...dati.daCompletare.map(t => elemento("li", t)));
  anteprimaPronta = dati.stato === "completa";
  document.querySelector("#calcolo-completo").hidden = !anteprimaPronta;
  if (anteprimaPronta) {
    document.querySelector("#tipo-simulazione").textContent = dati.simulazione.scenario === "piatto_abituale"
      ? "Simulazione didattica per il piatto abituale" : "Simulazione didattica di prima occorrenza";
    document.querySelector("#totali").replaceChildren(...nutrienti.flatMap(([nome, chiave, unita]) =>
      [elemento("dt", nome), elemento("dd", numero.format(dati.totali[chiave]) + " " + unita)]));
    document.querySelector("#contributi").replaceChildren(...dati.righe.map(r => {
      const v = r.catalogo;
      const macro = nutrienti.map(([nome,k,u]) => nome + " " + numero.format(r.contributo[k]) + " " + u).join(", ");
      const ig = v.indiceGlicemico === "non_disponibile" ? "non disponibile" : v.indiceGlicemico;
      return elemento("li", v.nome + " (" + preparazioneCatalogo(v.preparazione) + "), " + numero.format(r.quantitaG) + " g. " +
        macro + ". IG " + ig + ". Fonte: " + v.fonte + " (" + v.versione + ").");
    }));
    document.querySelector("#profilo-demo").textContent = dati.profilo.nome + " · " + dati.profilo.versione +
      " · Regola " + dati.versioneRegola;
    document.querySelector("#memoria-demo").textContent = "Memoria usata: " +
      numero.format(dati.simulazione.memoriaCorrenteG) + " g. Risultato:";
    document.querySelector("#valore-demo").textContent = numero.format(dati.simulazione.unitaSimulate) + " unità simulate.";
  }
  mostraSceltaPasto = anteprimaPronta;
  nutrizione.hidden = false;
  aggiornaRegistrazione();
}

function dataLocale(data) {
  return new Date(data.getTime() - data.getTimezoneOffset() * 60_000).toISOString().slice(0,16);
}

function preparaCampiPasto() {
  if (campiBozzaId === bozza.bozzaId) return;
  campiBozzaId = bozza.bozzaId;
  piattoPasto.value = "";
  nomePasto.value = "";
  orarioPasto.value = dataLocale(new Date());
  statoRegistrazione.textContent = "";
}

function leggiRegistrazione() {
  if (!bozza) return null;
  const id = bozza.bozzaId;
  if (!registrazioni.has(id)) {
    let stato = null;
    try {
      stato = JSON.parse(sessionStorage.getItem("dass.registrazione." + id));
    } catch { /* La chiave deterministica evita duplicati anche senza storage. */ }
    registrazioni.set(id, stato);
  }
  return registrazioni.get(id);
}

function memorizzaRegistrazione(stato) {
  const id = bozza.bozzaId;
  registrazioni.set(id, stato);
  try {
    const chiave = "dass.registrazione." + id;
    if (stato) sessionStorage.setItem(chiave, JSON.stringify(stato));
    else sessionStorage.removeItem(chiave);
  } catch { /* Il tentativo rimane disponibile nella scheda. */ }
}

function aggiornaRegistrazione(messaggio) {
  const stato = leggiRegistrazione();
  registrazione.hidden = !bozza || (!mostraSceltaPasto && !stato);
  confermaPasto.hidden = Boolean(stato);
  const nomeVisibile = !piattoPasto.value;
  nomePasto.closest("label").hidden = !nomeVisibile;
  nomePasto.disabled = operazionePasto || !nomeVisibile;
  salvaPasto.disabled = operazionePasto || !anteprimaPronta;
  riprovaPasto.hidden = !stato?.richiesta;
  riprovaPasto.disabled = operazionePasto;
  pastoRegistrato.hidden = !stato?.esito;
  if (stato?.esito) {
    document.querySelector("#dettaglio-registrato").href = "api/pasti/" + encodeURIComponent(stato.esito.pastoId);
    document.querySelector("#storico-registrato").href = "api/piatti/" + encodeURIComponent(stato.esito.piattoId) + "/pasti";
  }
  if (messaggio !== undefined) statoRegistrazione.textContent = messaggio;
  else if (stato?.esito) statoRegistrazione.textContent = "Pasto registrato nel diario.";
  else if (stato?.richiesta && !operazionePasto) {
    statoRegistrazione.textContent = "La registrazione è in attesa di conferma. Riprova lo stesso invio.";
  }
}

async function caricaPiatti() {
  const selezionato = piattoPasto.value;
  const piatti = await json("api/pazienti/paz-demo/piatti");
  piattoPasto.replaceChildren(new Option("Nuovo piatto",""),
    ...piatti.map(p => new Option(p.nome, p.piattoId)));
  piattoPasto.value = piatti.some(p => p.piattoId === selezionato) ? selezionato : "";
}

function calcolaAnteprima() {
  const dati = {revisione: bozza.revisione, ingredienti: bozza.ingredienti, scenarioId: "demo-sintetica-v1"};
  if (piattoPasto.value) dati.piattoId = piattoPasto.value;
  return json("api/bozze/" + bozza.bozzaId + "/anteprima", richiestaJson("POST", dati));
}

function bloccaPasto(valore) {
  operazionePasto = valore;
  busy(form, valore);
  busy(editor, valore || Boolean(leggiRegistrazione()?.richiesta));
  busy(confermaPasto, valore);
  aggiornaRegistrazione();
}

piattoPasto.addEventListener("change", async () => {
  anteprimaPronta = false;
  nutrizione.hidden = true;
  bloccaPasto(true);
  aggiornaRegistrazione("Aggiornamento della simulazione.");
  try {
    mostraAnteprima(await calcolaAnteprima());
    aggiornaRegistrazione("");
  } catch (e) { aggiornaRegistrazione(e.message); }
  finally { bloccaPasto(false); }
});

async function inviaPasto(richiesta) {
  bloccaPasto(true);
  memorizzaRegistrazione({richiesta});
  aggiornaRegistrazione("Registrazione in corso.");
  let messaggio;
  try {
    const esito = await json("api/pasti", richiestaJson("POST", richiesta));
    memorizzaRegistrazione({esito});
  } catch (e) {
    // Un errore di rete può arrivare dopo il commit. In quel caso il retry usa lo stesso corpo.
    if (e.http >= 400 && e.http < 500 && e.http !== 429) memorizzaRegistrazione(null);
    messaggio = leggiRegistrazione()?.richiesta
      ? "Esito da confermare. Riprova la stessa registrazione."
      : e.message;
  } finally {
    bloccaPasto(false);
    aggiornaRegistrazione(messaggio);
  }
}

confermaPasto.addEventListener("submit", async event => {
  event.preventDefault();
  if (!anteprimaPronta || operazionePasto || leggiRegistrazione()) return;
  const orario = new Date(orarioPasto.value);
  if (!Number.isFinite(orario.valueOf()) || dataLocale(orario) !== orarioPasto.value) {
    aggiornaRegistrazione("Indica una data e un’ora valide per il pasto.");
    return;
  }
  const piatto = piattoPasto.value;
  const nome = nomePasto.value.trim();
  if (!piatto && !nome) {
    aggiornaRegistrazione("Inserisci il nome del nuovo piatto.");
    return;
  }
  await inviaPasto({
    // Una bozza può generare un solo episodio anche con due schede aperte.
    richiestaId: "bozza-" + bozza.bozzaId, bozzaId: bozza.bozzaId, revisione: bozza.revisione,
    consumatoAlle: orario.toISOString(), ingredienti: bozza.ingredienti,
    ...(piatto ? {piattoId: piatto} : {nomePiatto: nome})
  });
});

riprovaPasto.addEventListener("click", async () => {
  const richiesta = leggiRegistrazione()?.richiesta;
  if (richiesta && !operazionePasto) await inviaPasto(richiesta);
});

document.querySelector("#rimuovi-bozza").addEventListener("click", async () => {
  if (!bozza || operazionePasto ||
      !window.confirm("Rimuovere la bozza? I pasti già registrati restano nello storico.")) return;
  const id = bozza.bozzaId;
  bloccaPasto(true);
  salvataggio.textContent = "Rimozione in corso.";
  try {
    await json("api/bozze/" + encodeURIComponent(id) + "?revisione=" + bozza.revisione, {method: "DELETE"});
  } catch (e) {
    salvataggio.textContent = e.message;
    bloccaPasto(false);
    return;
  }
  memorizzaRegistrazione(null);
  try {
    const ultima = JSON.parse(sessionStorage.getItem("dass.ultimaBozza"));
    if (ultima?.id === id) sessionStorage.removeItem("dass.ultimaBozza");
  } catch { /* La rimozione è già stata confermata dal server. */ }
  location.replace("./?bozza-rimossa");
});

editor.addEventListener("input", event => {
  if (event.target.matches(".filtro-catalogo")) return;
  annullaAnteprima();
  salvataggio.textContent = "";
});
editor.addEventListener("change", event => {
  if (!event.target.matches(".filtro-catalogo")) annullaAnteprima();
});

editor.addEventListener("submit", async event => {
  event.preventDefault();
  const calcola = event.submitter?.id === "calcola";
  const ingredienti = [...righe.children].map(campo => {
    const valore = campo.querySelector("[name=grammi]").value;
    return {rigaId: campo.dataset.rigaId, nome: campo.querySelector("[name=nome]").value.trim(),
      stato: campo.dataset.stato, voceCatalogoId: campo.querySelector("select").value || null,
      sceltaCatalogoManuale: campo.dataset.sceltaCatalogoManuale === "true",
      quantitaG: valore === "" ? null : Number(valore)};
  });
  busy(editor, true);
  busy(form, true);
  annullaAnteprima();
  salvataggio.textContent = calcola ? "Calcolo in corso." : "Salvataggio in corso.";
  try {
    await mostra(await json("api/bozze/" + bozza.bozzaId + "/ingredienti",
      richiestaJson("PUT", {revisione: bozza.revisione, ingredienti})));
    if (calcola) {
      busy(editor, true);
      const dati = await calcolaAnteprima();
      if (dati.stato === "completa") await caricaPiatti();
      mostraAnteprima(dati);
      salvataggio.textContent = dati.stato === "completa" ? "Quantità salvate. Anteprima calcolata." :
        "Quantità salvate. Per calcolare i totali nutrizionali completa le righe indicate.";
      (registrazione.hidden ? nutrizione : registrazione).scrollIntoView({block: "nearest"});
      return;
    }
    salvataggio.textContent = "Bozza salvata.";
  } catch (e) { salvataggio.textContent = e.message; }
  finally { busy(editor, false); busy(form, false); }
});

const id = new URLSearchParams(location.search).get("bozza");
if (id) {
  busy(form, true);
  json("api/bozze/" + encodeURIComponent(id)).then(mostra).catch(e => {
    messaggio.textContent = e.message;
  }).finally(() => busy(form, false));
}
