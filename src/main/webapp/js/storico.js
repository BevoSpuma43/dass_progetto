const listaPasti = document.querySelector("#pasti");
const statoStorico = document.querySelector("#stato-storico");
const riprovaStorico = document.querySelector("#riprova-storico");
const modelloPasto = document.querySelector("#scheda-pasto");
const numero = new Intl.NumberFormat("it-IT", {maximumFractionDigits: 2});
const dataPasto = new Intl.DateTimeFormat("it-IT", {dateStyle: "medium", timeStyle: "short"});

async function caricaJson(url) {
  const risposta = await fetch(url, {cache: "no-store"});
  if (!risposta.ok) throw new Error("Richiesta non riuscita");
  return risposta.json();
}

function schedaPasto(pasto) {
  const scheda = modelloPasto.content.firstElementChild.cloneNode(true);
  scheda.querySelector("h2").textContent = pasto.nomePiatto;
  scheda.querySelector("img").src = pasto.miniaturaUrl;
  const orario = scheda.querySelector("time");
  orario.dateTime = pasto.consumatoAlle;
  orario.textContent = dataPasto.format(new Date(pasto.consumatoAlle));
  for (const campo of scheda.querySelectorAll("[data-valore]")) {
    campo.textContent = numero.format(pasto.totali[campo.dataset.valore]) + " " + campo.dataset.unita;
  }
  scheda.querySelector(".stima-pasto strong").textContent = numero.format(pasto.stimaOriginale) + " unità";

  const dettagli = scheda.querySelector("details");
  const stato = dettagli.querySelector(".stato-dettaglio");
  const riprova = dettagli.querySelector(".riprova-dettaglio");
  const ingredienti = dettagli.querySelector(".ingredienti");
  let inCorso = false;
  let caricato = false;
  async function caricaIngredienti() {
    if (!dettagli.open || inCorso || caricato) return;
    inCorso = true;
    riprova.hidden = true;
    stato.hidden = false;
    stato.textContent = "Caricamento degli ingredienti.";
    try {
      const pastoCompleto = await caricaJson("api/pasti/" + encodeURIComponent(pasto.pastoId));
      for (const riga of pastoCompleto.ingredienti) {
        const li = document.createElement("li");
        li.textContent = riga.nome + " · " + numero.format(riga.quantitaG) + " g";
        if (riga.contributo) {
          const c = riga.contributo;
          li.textContent += " · " + numero.format(c.carboidratiG) + " g carboidrati, " +
            numero.format(c.proteineG) + " g proteine, " + numero.format(c.grassiG) + " g grassi, " +
            numero.format(c.kcal) + " kcal";
        }
        ingredienti.append(li);
      }
      stato.hidden = pastoCompleto.ingredienti.length > 0;
      if (!stato.hidden) stato.textContent = "Nessun ingrediente salvato.";
      caricato = true;
    } catch {
      stato.textContent = "Impossibile caricare gli ingredienti.";
      riprova.hidden = false;
    } finally {
      inCorso = false;
    }
  }
  dettagli.addEventListener("toggle", caricaIngredienti);
  riprova.addEventListener("click", caricaIngredienti);
  return scheda;
}

async function caricaStorico() {
  statoStorico.hidden = false;
  statoStorico.textContent = "Caricamento dello storico.";
  riprovaStorico.hidden = true;
  try {
    const pasti = await caricaJson("api/pazienti/paz-demo/pasti");
    listaPasti.replaceChildren(...pasti.map(schedaPasto));
    statoStorico.textContent = "Nessun pasto registrato.";
    statoStorico.hidden = pasti.length > 0;
  } catch {
    statoStorico.textContent = "Impossibile caricare lo storico.";
    riprovaStorico.hidden = false;
  }
}

riprovaStorico.addEventListener("click", caricaStorico);
caricaStorico();
