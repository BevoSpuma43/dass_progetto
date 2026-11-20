// Swagger UI della demo statica: la specifica è quella del prototipo, ma il server è l'API nel browser.
const statoSwagger = document.querySelector("#stato-swagger");

async function avviaSwagger() {
  if (typeof SwaggerUIBundle !== "function") {
    statoSwagger.textContent = "Impossibile caricare Swagger UI. Puoi aprire la specifica OpenAPI in JSON.";
    return;
  }
  let spec;
  try {
    const risposta = await fetch("openapi.json");
    if (!risposta.ok) throw new Error("HTTP " + risposta.status);
    spec = await risposta.json();
  } catch {
    statoSwagger.textContent = "Impossibile leggere la specifica OpenAPI.";
    return;
  }
  // I percorsi /api/... restano quelli del backend; cambia solo la radice, la cartella del sito.
  const radice = new URL(".", location.href);
  spec.servers = [{url: radice.origin + radice.pathname.replace(/\/$/, ""), description: "API DASS nel browser (demo statica)"}];
  // La prova Haiku senza bozza esiste solo sul server Java.
  delete spec.paths["/prove/haiku"];
  SwaggerUIBundle({
    spec,
    dom_id: "#swagger-ui",
    deepLinking: true,
    docExpansion: "list",
    validatorUrl: null,
    onComplete() { statoSwagger.hidden = true; }
  });
}

avviaSwagger();
