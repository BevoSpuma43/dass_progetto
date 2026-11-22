/** Errore applicativo con stato HTTP e codice: corrisponde a it.unisi.dass.analisi.ErroreAnalisi. */
export class ErroreAnalisi extends Error {
  /**
   * @param {number} statoHttp stato da inviare nella risposta
   * @param {string} codice codice leggibile dal client
   * @param {string} messaggio messaggio per l'utente
   */
  constructor(statoHttp, codice, messaggio) {
    super(messaggio);
    this.name = "ErroreAnalisi";
    this.statoHttp = statoHttp;
    this.codice = codice;
  }
}
