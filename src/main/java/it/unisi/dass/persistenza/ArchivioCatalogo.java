package it.unisi.dass.persistenza;

import it.unisi.dass.analisi.AbbinamentoCatalogo;
import jakarta.ejb.Stateless;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** Catalogo nutrizionale pubblico: le schede non sono dati personali e restano in chiaro. */
@Stateless
public class ArchivioCatalogo {
    @PersistenceContext(unitName = "dass")
    private EntityManager em;

    /** Schede in ordine di nome e ID, con il filtro facoltativo usato da GET /api/catalogo?q=. */
    public JsonArray catalogo(String filtro) {
        return catalogo(em, filtro);
    }

    static JsonArray catalogo(EntityManager em, String filtro) {
        var lista = Json.createArrayBuilder();
        for (var v : em.createQuery("select v from VoceCatalogo v order by v.nome, v.id", VoceCatalogo.class).getResultList()) {
            if (!filtro.isBlank() && !AbbinamentoCatalogo.corrispondeAllaRicerca(v.nome, v.preparazione, filtro)) continue;
            lista.add(v.json());
        }
        return lista.build();
    }
}
