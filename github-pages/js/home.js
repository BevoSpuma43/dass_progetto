/**
 * Home della versione GitHub Pages: riepilogo dell'archivio nel browser, ripristino dei dati
 * dimostrativi e collegamento all'ultima bozza. Sostituisce stato.js, che verificava Payara.
 */
import {ripristinaDatiDemo} from "./locale/archivio.js";

const esito = document.querySelector("#esito");
const ripristina = document.querySelector("#ripristina");
const ultimaBozza = document.querySelector("#ultima-bozza");

function conteggio(n, singolare, plurale) {
  return n + " " + (n === 1 ? singolare : plurale);
}

async function mostraArchivio(prefisso = "") {
  esito.dataset.stato = "attesa";
  esito.textContent = "Lettura dell’archivio…";
  try {
    const risposta = await fetch("api/demo/archivio", {cache: "no-store"});
    const dati = await risposta.json();
    if (!risposta.ok) throw new Error(dati.messaggio);
    esito.dataset.stato = "ok";
    esito.textContent = prefisso + "Archivio pronto: " + conteggio(dati.piatti, "piatto", "piatti") + ", " +
      conteggio(dati.episodi, "pasto registrato", "pasti registrati") + " e " +
      conteggio(dati.feedback, "lettura", "letture") + " della glicemia.";
  } catch (e) {
    esito.dataset.stato = "errore";
    esito.textContent = e.message || "Archivio del browser non disponibile.";
  }
}

ripristina.addEventListener("click", async () => {
  if (!window.confirm("Ripristinare i dati dimostrativi? Le bozze e i pasti registrati in questo browser verranno cancellati.")) return;
  ripristina.disabled = true;
  try {
    await ripristinaDatiDemo();
    try {
      for (const chiave of Object.keys(sessionStorage)) {
        if (chiave === "dass.ultimaBozza" || chiave.startsWith("dass.registrazione.")) sessionStorage.removeItem(chiave);
      }
    } catch { /* Lo storage di sessione è facoltativo. */ }
    ultimaBozza.hidden = true;
    await mostraArchivio("Dati dimostrativi ripristinati. ");
  } catch (e) {
    esito.dataset.stato = "errore";
    esito.textContent = e.message || "Ripristino non riuscito.";
  } finally {
    ripristina.disabled = false;
  }
});

mostraArchivio();

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
    ultimaBozza.href = "haiku.html?bozza=" + encodeURIComponent(ultima.id);
    ultimaBozza.hidden = false;
  }
} catch { /* I collegamenti principali sono disponibili anche senza storage. */ }
