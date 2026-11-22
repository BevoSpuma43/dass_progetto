package it.unisi.dass.persistenza;

import jakarta.persistence.EntityManager;

/**
 * Schede che il catalogo USDA non copre: la pasta di semola cotta con i valori CREA e le schede
 * sintetiche usate dai collaudi (porzione di pasta da 50/7/8 g). Non appartengono a nessun profilo.
 */
final class CatalogoAggiuntivo {
    static final String VERSIONE = "fixture-v1";

    /** Impedisce l'istanziazione di questa classe di utilità. */
    private CatalogoAggiuntivo() {}

    /** Inserisce le schede mancanti, lasciando invariate quelle già presenti. */
    static void inizializza(EntityManager em) {
        var cotta = voce(em, "pasta-cotta", "Pasta di semola", "cotto", 37.3, 6.9, 0.6, 175);
        if (cotta != null) {
            cotta.versione = "CREA-000805-v1";
            cotta.fonte = "CREA, Pasta di semola cotta, bollita (www.alimentinutrizione.it/tabelle-nutrizionali/000805)";
        }
        voce(em, "pasta", "Pasta", "crudo", 60, 8, 2, 290);
        voce(em, "sugo", "Sugo di pomodoro", "cotto", 5, 1.5, 1, 35);
        voce(em, "olio", "Olio di oliva", "crudo", 0, 0, 100, 900);
        voce(em, "riso", "Riso", "cotto", 25, 3, 1, 121);
        voce(em, "piselli", "Piselli", "cotto", 10, 5, 1, 69);
    }

    /** Crea una scheda con ID versionato e valori per 100 g se non esiste già; restituisce null se c'era. */
    private static VoceCatalogo voce(EntityManager em, String id, String nome, String stato,
                                     double carboidrati, double proteine, double grassi, double kcal) {
        if (em.find(VoceCatalogo.class, id + "-v1") != null) return null;
        var v = new VoceCatalogo();
        v.id = id + "-v1";
        v.nome = nome;
        v.preparazione = stato;
        v.unita = "g";
        v.fonte = "DASS, valori sintetici per 100 g";
        v.versione = VERSIONE;
        v.indiceGlicemico = "non_disponibile";
        v.per100g = new Macronutrienti(carboidrati, proteine, grassi, kcal);
        em.persist(v);
        return v;
    }
}
