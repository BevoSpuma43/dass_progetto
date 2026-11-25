package it.unisi.dass.persistenza;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.sql.DataSourceDefinition;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.logging.Logger;

// Avvia-DASS imposta la directory di lavoro del processo Java sulla directory dati.
/**
 * Dichiara il datasource H2 e prepara l'archivio all'avvio di Payara: rimuove il vecchio diario in
 * chiaro di paz-demo, importa il catalogo e crea i profili utente1 e utente2 con diari vuoti.
 */
@DataSourceDefinition(name = "java:app/jdbc/Dass", className = "org.h2.jdbcx.JdbcDataSource",
        url = "jdbc:h2:file:./dass;WRITE_DELAY=0", user = "sa", password = "", minPoolSize = 1, maxPoolSize = 8)
@Singleton
@Startup
public class AvvioArchivio {
    private static final Logger LOG = Logger.getLogger(AvvioArchivio.class.getName());
    static final String MIGRAZIONE = "rimozione-paz-demo-v1";
    /** Tabelle del diario in chiaro della versione precedente: contenevano solo i dati di paz-demo. */
    static final List<String> TABELLE_IN_CHIARO = List.of("ESITO", "FEEDBACK", "INGREDIENTE", "EPISODIO",
            "MEMORIAPIATTO", "PIATTO", "BOZZA", "PAZIENTE");
    static final List<String[]> PROFILI = List.of(new String[]{"utente1", "Utente 1"}, new String[]{"utente2", "Utente 2"});

    @PersistenceContext(unitName = "dass")
    private EntityManager em;

    /** Prepara l'archivio in una transazione di avvio. */
    @PostConstruct
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void avvia() {
        inizializza(em);
    }

    /** Operazioni idempotenti: ripeterle a ogni avvio non cambia un archivio già pronto. */
    static void inizializza(EntityManager em) {
        rimuoviDiarioInChiaro(em);
        CatalogoUsda.importa(em);
        for (var profilo : PROFILI) {
            if (em.find(Profilo.class, profilo[0]) == null) em.persist(new Profilo(profilo[0], profilo[1]));
        }
    }

    /**
     * Elimina una sola volta, da ogni archivio H2 aperto, le tabelle del diario in chiaro con il profilo
     * paz-demo e i suoi pasti, letture, memorie e bozze. Il catalogo resta.
     */
    static void rimuoviDiarioInChiaro(EntityManager em) {
        if (em.find(CaricamentoDemo.class, MIGRAZIONE) != null) return;
        for (String tabella : TABELLE_IN_CHIARO) {
            em.createNativeQuery("DROP TABLE IF EXISTS " + tabella + " CASCADE").executeUpdate();
        }
        var marcatore = new CaricamentoDemo();
        marcatore.id = MIGRAZIONE;
        marcatore.versione = MIGRAZIONE;
        em.persist(marcatore);
        LOG.info("Rimosso il diario in chiaro di paz-demo: tabelle " + TABELLE_IN_CHIARO);
    }
}
