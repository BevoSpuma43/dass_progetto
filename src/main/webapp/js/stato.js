const esito = document.getElementById("esito");
const pulsante = document.getElementById("verifica");

async function verificaConnessione() {
  pulsante.disabled = true;
  esito.dataset.stato = "attesa";
  esito.textContent = "Verifica in corso…";
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);

  try {
    const risposta = await fetch("api/stato", { cache: "no-store", signal: controller.signal });
    if (!risposta.ok || (await risposta.json()).stato !== "ok") {
      throw new Error("Risposta API inattesa");
    }
    esito.dataset.stato = "ok";
    esito.textContent = "Il backend risponde correttamente.";
  } catch {
    esito.dataset.stato = "errore";
    esito.textContent = "Verifica fallita. Controlla che Payara sia avviato e riprova.";
  } finally {
    clearTimeout(timeout);
    pulsante.disabled = false;
  }
}

pulsante.addEventListener("click", verificaConnessione);
verificaConnessione();

const urlHome = new URL(location.href);
if (urlHome.searchParams.has("bozza-rimossa")) {
  document.querySelector("#bozza-rimossa").hidden = false;
  urlHome.searchParams.delete("bozza-rimossa");
  history.replaceState(null, "", urlHome);
}

// La scorciatoia vale nella scheda in cui è stata aperta la bozza, fino alla sua scadenza.
try {
  const ultima = JSON.parse(sessionStorage.getItem("dass.ultimaBozza"));
  if (ultima && /^[a-f0-9-]{36}$/i.test(ultima.id) && Date.parse(ultima.scade) > Date.now()) {
    const link = document.querySelector("#ultima-bozza");
    link.href = "haiku.html?bozza=" + encodeURIComponent(ultima.id);
    link.hidden = false;
  }
} catch { /* I collegamenti principali sono disponibili anche senza storage. */ }
