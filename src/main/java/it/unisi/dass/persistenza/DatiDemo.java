package it.unisi.dass.persistenza;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

/** Fixture sintetica v1. Il chiamante racchiude il caricamento in una sola transazione. */
public final class DatiDemo {
    static final String PAZIENTE_ID = "paz-demo";
    static final String VERSIONE = "fixture-v1";
    static final String REGOLA = "fixture-integrazione-v1";

    /** Impedisce l'istanziazione di questa classe di utilità. */
    private DatiDemo() {}

    /**
     * Prepara la scheda CREA della pasta cotta e, una sola volta, inserisce profilo, catalogo di prova
     * ed episodi storici.
     */
    public static void inizializza(EntityManager em) {
        if (em.find(VoceCatalogo.class, "pasta-cotta-v1") == null) {
            var cotta = voce(em,"pasta-cotta","Pasta di semola","cotto",37.3,6.9,0.6,175);
            cotta.versione = "CREA-000805-v1";
            cotta.fonte = "CREA, Pasta di semola cotta, bollita (www.alimentinutrizione.it/tabelle-nutrizionali/000805)";
        }
        if (em.find(CaricamentoDemo.class, VERSIONE) != null) return;

        Paziente paziente = new Paziente();
        paziente.id = PAZIENTE_ID;
        paziente.nome = "Paziente dimostrativo";
        paziente.versioneProfilo = "profilo-sintetico-v1";
        paziente.targetMgDl = 120;
        paziente.attesaMgDl = 120;
        paziente.glicemiaInizialeMgDl = 120;
        paziente.fattoreMgDlPerG = 4;
        paziente.insulinaAttivaIniziale = 0;
        em.persist(paziente);

        // I valori per 100 g sono costruiti per il collaudo (50/7/8 g nella porzione pasta).
        var pasta = voce(em, "pasta", "Pasta", "crudo", 60, 8, 2, 290);
        var sugo = voce(em, "sugo", "Sugo di pomodoro", "cotto", 5, 1.5, 1, 35);
        var olio = voce(em, "olio", "Olio di oliva", "crudo", 0, 0, 100, 900);
        var riso = voce(em, "riso", "Riso", "cotto", 25, 3, 1, 121);
        var piselli = voce(em, "piselli", "Piselli", "cotto", 10, 5, 1, 69);

        var piatto = piatto(em, paziente, "piatto-pasta", "Pasta al pomodoro", 50);
        var iniziale = memoria(em, piatto, 0, 50, 100, 0);
        var aggiornata = memoria(em, piatto, 1, 52, 41.27439024, 1);
        for (int i = 1; i <= 4; i++) {
            var stato = i == 4 ? aggiornata : iniziale;
            var pasto = episodio(em, "pasta-0" + i, stato, "2026-11-" + (10 + i) + "T11:30:00Z",
                    "Pasta al pomodoro con olio", new Macronutrienti(50, 7, 8, 300), i == 4 ? 5.2 : 5.0);
            ingrediente(em, pasto, pasta, 80);
            ingrediente(em, pasto, sugo, 40);
            ingrediente(em, pasto, olio, 6);
            switch (i) {
                case 1 -> feedback(em, pasto, 145, 150, false, "fuori_finestra", iniziale);
                case 2 -> feedback(em, pasto, 132, 270, false, "attivita_fisica", iniziale);
                case 3 -> feedback(em, pasto, 139, 270, true, "aggiornamento_fixture", aggiornata);
                default -> { /* Il quarto episodio attende la lettura. */ }
            }
        }

        var controllo = piatto(em, paziente, "piatto-controllo", "Riso e piselli", 35);
        var base = memoria(em, controllo, 0, 35, 100, 0);
        var pasto = episodio(em, "controllo-01", base, "2026-11-15T18:45:00Z",
                "Riso con piselli", new Macronutrienti(35, 8, 2, 190), 3.5);
        ingrediente(em, pasto, riso, 100);
        ingrediente(em, pasto, piselli, 100);

        var caricamento = new CaricamentoDemo();
        caricamento.id = VERSIONE;
        caricamento.versione = VERSIONE;
        em.persist(caricamento);
    }

    /** Crea una scheda del catalogo dimostrativo con un ID versionato e valori per 100 g. */
    private static VoceCatalogo voce(EntityManager em, String id, String nome, String stato,
                                    double carboidrati, double proteine, double grassi, double kcal) {
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

    /** Crea un piatto demo associato al profilo e salva la sua memoria iniziale. */
    private static Piatto piatto(EntityManager em, Paziente paziente, String id, String nome, double inizialeG) {
        var p = new Piatto();
        p.id = id;
        p.paziente = paziente;
        p.nome = nome;
        p.memoriaInizialeG = inizialeG;
        em.persist(p);
        return p;
    }

    /** Salva una versione della memoria di un piatto con i valori previsti dalla fixture. */
    private static MemoriaPiatto memoria(EntityManager em, Piatto piatto, int numero, double grammi,
                                         double varianza, int segno) {
        var m = new MemoriaPiatto();
        m.id = piatto.id + "-v" + numero;
        m.piatto = piatto;
        m.numero = numero;
        m.carboidratiEfficaciG = grammi;
        m.varianza = varianza;
        m.ultimoSegno = segno;
        m.segnaliPositiviConsecutivi = segno > 0 ? 1 : 0;
        em.persist(m);
        return m;
    }

    /**
     * Crea un pasto dimostrativo con foto, miniatura, istante, versione della memoria e risultati
     * attesi.
     */
    private static Episodio episodio(EntityManager em, String id, MemoriaPiatto memoria, String istante,
                                     String caption, Macronutrienti totali, double stima) {
        var e = new Episodio();
        e.id = id;
        e.piatto = memoria.piatto;
        e.memoriaOriginale = memoria;
        e.chiaveInvio = "invio-" + id;
        e.istantePastoEpochSecond = Instant.parse(istante).getEpochSecond();
        e.caption = caption;
        e.formatoFoto = "image/jpeg";
        e.foto = immagine(id + ".jpg");
        e.miniatura = immagine(id + "-mini.jpg");
        e.totali = totali;
        e.stimaOriginale = stima;
        e.versioneRegola = REGOLA;
        e.versioneProfilo = e.piatto.paziente.versioneProfilo;
        e.indiceGlicemico = "non_disponibile";
        em.persist(e);
        return e;
    }

    /** Legge i byte di una risorsa JPEG dimostrativa dal classpath. */
    private static byte[] immagine(String nome) {
        try (InputStream in = DatiDemo.class.getResourceAsStream("/demo/" + nome)) {
            if (in == null) throw new IllegalStateException("Manca la risorsa demo " + nome);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Lettura immagine demo fallita: " + nome, e);
        }
    }

    /** Associa al pasto demo una scheda del catalogo e la quantità in grammi prevista dalla fixture. */
    private static void ingrediente(EntityManager em, Episodio episodio, VoceCatalogo voce, double grammi) {
        var r = new Ingrediente();
        r.id = episodio.id + "-" + voce.id;
        r.episodio = episodio;
        r.voce = voce;
        r.quantitaG = grammi;
        r.origineQuantita = "confermata_fixture";
        em.persist(r);
    }

    /**
     * Inserisce una lettura dimostrativa e l'esito che collega le versioni precedente e successiva
     * della memoria.
     */
    private static void feedback(EntityManager em, Episodio pasto, int valore, int minuti,
                                 boolean ammissibile, String motivo, MemoriaPiatto successiva) {
        var f = new Feedback();
        f.id = "lettura-" + pasto.id;
        f.episodio = pasto;
        f.glicemiaMgDl = valore;
        f.minutiDopoPasto = minuti;
        f.istanteLetturaEpochSecond = pasto.istantePastoEpochSecond + 60L * minuti;
        f.ammissibile = ammissibile;
        f.motivo = motivo;
        em.persist(f);

        var esito = new Esito();
        esito.id = "esito-" + pasto.id;
        esito.feedback = f;
        esito.precedente = pasto.memoriaOriginale;
        esito.successiva = successiva;
        esito.versioneRegola = REGOLA;
        esito.motivo = motivo;
        em.persist(esito);
    }
}
