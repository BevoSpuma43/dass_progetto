package it.unisi.dass.persistenza;

import jakarta.persistence.*;
import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Valori incorporati nelle entità JPA: grammi di carboidrati, proteine e grassi, più energia in kcal.
 */
@Embeddable
public class Macronutrienti {
    @Column(nullable = false)
    double carboidratiG;
    @Column(nullable = false)
    double proteineG;
    @Column(nullable = false)
    double grassiG;
    @Column(nullable = false)
    double kcal;

    /** Fornisce il costruttore senza argomenti usato da JPA. */
    protected Macronutrienti() {}

    /** Inizializza i tre macro in grammi e l'energia in kcal. */
    Macronutrienti(double carboidratiG, double proteineG, double grassiG, double kcal) {
        this.carboidratiG = carboidratiG;
        this.proteineG = proteineG;
        this.grassiG = grassiG;
        this.kcal = kcal;
    }

    /** Restituisce i macro nel formato JSON esposto dalle API. */
    JsonObject json() {
        return Json.createObjectBuilder().add("carboidratiG", carboidratiG).add("proteineG", proteineG)
                .add("grassiG", grassiG).add("kcal", kcal).build();
    }
}
