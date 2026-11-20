package it.unisi.dass.persistenza;

import jakarta.persistence.*;
import jakarta.json.Json;
import jakarta.json.JsonObject;

/** Profilo persistito al quale sono associati i piatti e i parametri del caso dimostrativo. */
@Entity
public class Paziente {
    @Id
    String id;

    @Column(nullable = false)
    String nome;

    @Column(nullable = false)
    String versioneProfilo;

    @Column(nullable = false)
    double targetMgDl;

    @Column(nullable = false)
    double attesaMgDl;

    @Column(nullable = false)
    double fattoreMgDlPerG;

    @Column(nullable = false)
    double glicemiaInizialeMgDl;

    @Column(nullable = false)
    double insulinaAttivaIniziale;

    /** Seleziona i dati del profilo da includere nelle risposte di anteprima e dettaglio. */
    JsonObject profilo() {
        return Json.createObjectBuilder().add("id", id).add("nome", nome).add("versione", versioneProfilo)
                .add("targetMgDl", targetMgDl).add("glicemiaInizialeMgDl", glicemiaInizialeMgDl)
                .add("insulinaAttivaIniziale", insulinaAttivaIniziale).build();
    }
}
