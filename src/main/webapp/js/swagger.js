const statoSwagger = document.querySelector("#stato-swagger");
if (typeof SwaggerUIBundle !== "function") {
  statoSwagger.textContent = "Impossibile caricare Swagger UI. Puoi aprire la specifica OpenAPI in JSON.";
} else {
  SwaggerUIBundle({
    url: "openapi.json",
    dom_id: "#swagger-ui",
    deepLinking: true,
    docExpansion: "list",
    validatorUrl: null,
    onComplete() { statoSwagger.hidden = true; }
  });
}
