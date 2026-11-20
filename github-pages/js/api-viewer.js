/**
 * Mostra la risposta di una GET verso l'API nel browser. Sostituisce l'apertura diretta dei
 * collegamenti api/... che sul server Payara restituivano JSON o immagini.
 */
const modulo = document.querySelector("#richiesta-api");
const campo = document.querySelector("#percorso-api");
const esito = document.querySelector("#esito-api");
const corpo = document.querySelector("#corpo-api");
const immagine = document.querySelector("#immagine-api");
let urlImmagine = null;

/** Percorso codificato nel frammento dell'URL, usato per la richiesta. */
function frammento() {
  return location.hash.slice(1);
}

/** Forma leggibile del percorso, con gli spazi al posto di %20. */
function leggibile(testo) {
  try {
    return decodeURI(testo);
  } catch {
    return testo;
  }
}

async function mostra(percorso) {
  corpo.hidden = immagine.hidden = true;
  if (!/^api\/\S*$/.test(percorso)) {
    esito.textContent = "Indica un percorso che inizia con api/, ad esempio api/catalogo?q=pasta.";
    return;
  }
  document.title = "DASS · GET " + leggibile(percorso);
  esito.textContent = "Richiesta in corso…";
  try {
    const risposta = await fetch(percorso, {cache: "no-store"});
    const tipo = risposta.headers.get("content-type") ?? "";
    esito.textContent = "GET " + leggibile(percorso) + " · HTTP " + risposta.status + (tipo ? " · " + tipo : "");
    if (tipo.startsWith("image/")) {
      if (urlImmagine) URL.revokeObjectURL(urlImmagine);
      urlImmagine = URL.createObjectURL(await risposta.blob());
      immagine.src = urlImmagine;
      immagine.hidden = false;
      return;
    }
    const testo = await risposta.text();
    try {
      corpo.textContent = JSON.stringify(JSON.parse(testo), null, 2);
    } catch {
      corpo.textContent = testo || "(nessun contenuto)";
    }
    corpo.hidden = false;
  } catch (e) {
    esito.textContent = "Richiesta non riuscita: " + e.message;
  }
}

modulo.addEventListener("submit", event => {
  event.preventDefault();
  const percorso = encodeURI(leggibile(campo.value.trim()));
  if (frammento() === percorso) mostra(percorso);
  else location.hash = percorso;
});

window.addEventListener("hashchange", () => {
  campo.value = leggibile(frammento());
  mostra(frammento());
});

campo.value = leggibile(frammento()) || "api/stato";
mostra(frammento() || "api/stato");
