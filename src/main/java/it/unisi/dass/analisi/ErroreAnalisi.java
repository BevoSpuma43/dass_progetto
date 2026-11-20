package it.unisi.dass.analisi;

/**
 * Eccezione applicativa con codice e stato HTTP. Nei servizi EJB comporta il rollback della
 * transazione corrente.
 */
@jakarta.ejb.ApplicationException(rollback = true)
public final class ErroreAnalisi extends RuntimeException {
    private final int statoHttp;
    private final String codice;

    /** Crea un errore con uno stato HTTP, un codice leggibile dal client e un messaggio per l'utente. */
    public ErroreAnalisi(int statoHttp, String codice, String messaggio) {
        super(messaggio);
        this.statoHttp = statoHttp;
        this.codice = codice;
    }

    /** Restituisce lo stato da inviare nella risposta HTTP. */
    public int statoHttp() { return statoHttp; }
    /** Restituisce il codice applicativo che identifica il tipo di errore. */
    public String codice() { return codice; }
}
