package it.unisi.dass.persistenza;

import jakarta.persistence.*;
import jakarta.json.Json;
import jakarta.json.JsonObject;

/** Scheda nutrizionale versionata con preparazione, fonte e valori per 100 g. */
@Entity
public class VoceCatalogo {
    @Id
    String id;

    @Column(nullable = false)
    String nome;

    @Column(nullable = false)
    String preparazione;

    @Column(nullable = false)
    String unita;

    @Column(nullable = false)
    String fonte;

    @Column(nullable = false)
    String versione;

    @Column(nullable = false)
    String indiceGlicemico;

    @Embedded
    Macronutrienti per100g;

    /** Restituisce ID, metadati e valori nutrizionali per 100 g nel JSON del catalogo. */
    JsonObject json() {
        return Json.createObjectBuilder().add("id", id).add("nome", nome).add("preparazione", preparazione)
                .add("versione", versione).add("unita", unita).add("fonte", fonte).add("indiceGlicemico", indiceGlicemico)
                .add("per100g", per100g.json()).build();
    }
}
