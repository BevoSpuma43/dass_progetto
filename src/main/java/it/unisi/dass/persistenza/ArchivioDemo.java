package it.unisi.dass.persistenza;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.sql.DataSourceDefinition;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;

// Avvia-DASS imposta la directory di lavoro del processo Java sulla directory dati.
/** Dichiara il datasource H2, inizializza i dati all'avvio di Payara e offre un riepilogo tecnico. */
@DataSourceDefinition(name = "java:app/jdbc/Dass", className = "org.h2.jdbcx.JdbcDataSource",
        url = "jdbc:h2:file:./dass;WRITE_DELAY=0", user = "sa", password = "", minPoolSize = 1, maxPoolSize = 8)
@Singleton
@Startup
public class ArchivioDemo {
    @PersistenceContext(unitName = "dass")
    private EntityManager em;

    /** Carica le fixture e importa il catalogo in una transazione di avvio. */
    @PostConstruct
    @jakarta.ejb.TransactionAttribute(jakarta.ejb.TransactionAttributeType.REQUIRES_NEW)
    public void avvia() {
        DatiDemo.inizializza(em);
        CatalogoUsda.importa(em);
    }

    /** Conta i record dimostrativi e restituisce gli ID degli episodi in ordine alfabetico. */
    public Map<String, Object> riepilogo() {
        return Map.of("pazienti", conta("Paziente"), "piatti", conta("Piatto"),
                "episodi", conta("Episodio"), "feedback", conta("Feedback"),
                "episodiId", em.createQuery("select e.id from Episodio e order by e.id", String.class).getResultList());
    }

    /** Esegue il conteggio JPQL per una delle entità note usate nel riepilogo. */
    private long conta(String entita) {
        return em.createQuery("select count(e) from " + entita + " e", Long.class).getSingleResult();
    }
}
