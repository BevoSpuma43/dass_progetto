/**
 * Azioni sul diario cifrato. Ogni azione apre un nuovo scambio di chiavi con Payara e ne usa il canale
 * per tutte le sue richieste, miniature comprese. I documenti sono cifrati e decifrati nel browser con
 * la chiave del diario del profilo, distinta dalla chiave dello scambio: Payara non vede mai il contenuto.
 */
import {apriCanale} from "./sicurezza/scambio.js";
import {byteInTesto, cifraDocumento, decifraDocumento, testoInByte} from "./sicurezza/cripto.js";
import {contesto, verifica} from "./profilo.js";

const BINARI = new Set(["foto", "miniatura"]);

/** Identificativi dei documenti: gli id sono unici in tutto l'archivio, non solo nel profilo. */
export const id = {
  impostazioni: profilo => "impostazioni-" + profilo,
  bozza: uuid => "b-" + uuid,
  foto: uuid => "f-" + uuid,
  miniatura: uuid => "m-" + uuid,
  pasto: uuid => "p-" + uuid
};

/**
 * Esegue `lavoro(sessione)` dentro una nuova azione. Se l'utente cambia profilo durante l'azione, le
 * richieste vengono interrotte e il risultato scartato con AzioneAnnullata.
 */
export async function azione(nome, lavoro) {
  const ctx = contesto();
  const canale = await apriCanale(ctx.profilo, nome, ctx.signal);
  try {
    verifica(ctx);
    const risultato = await lavoro(new Sessione(canale, ctx));
    verifica(ctx);
    canale.chiudi();
    return risultato;
  } catch (e) {
    canale.chiudi(e);
    throw e;
  }
}

class Sessione {
  constructor(canale, ctx) {
    this.canale = canale;
    this.ctx = ctx;
    this.profilo = ctx.profilo;
  }

  async #richiesta(metodo, percorso, corpo) {
    const risultato = await this.canale.richiesta(metodo, percorso, corpo);
    verifica(this.ctx);
    return risultato;
  }

  #documenti() {
    return "api/profili/" + encodeURIComponent(this.profilo) + "/documenti";
  }

  /** Decifra un documento; con il JSON restituisce l'oggetto, con foto e miniature i byte JPEG. */
  async #apri(documento) {
    const chiaro = await decifraDocumento(this.profilo, documento);
    verifica(this.ctx);
    const contenuto = BINARI.has(documento.tipo) ? chiaro : JSON.parse(byteInTesto(chiaro));
    return {id: documento.id, tipo: documento.tipo, revisione: documento.revisione, aggiornatoAlle: documento.aggiornatoAlle, contenuto};
  }

  /**
   * Come #apri, ma un documento che la chiave di questo browser non apre (chiave rigenerata, altro
   * browser) diventa `{illeggibile: true}` con i soli metadati, senza interrompere l'azione.
   */
  async #apriSePossibile(documento) {
    try {
      return await this.#apri(documento);
    } catch (e) {
      if (e.name === "AzioneAnnullata") throw e;
      return {id: documento.id, tipo: documento.tipo, revisione: documento.revisione, aggiornatoAlle: documento.aggiornatoAlle, illeggibile: true};
    }
  }

  /** Documenti di un tipo, decifrati, dal più recente. */
  async elenca(tipo) {
    const documenti = await this.#richiesta("GET", this.#documenti() + "?tipo=" + encodeURIComponent(tipo));
    const aperti = [];
    for (const documento of documenti) aperti.push(await this.#apriSePossibile(documento));
    return aperti;
  }

  /** Un documento decifrato, oppure null se non esiste. */
  async leggi(idDocumento) {
    let documento;
    try {
      documento = await this.#richiesta("GET", this.#documenti() + "/" + encodeURIComponent(idDocumento));
    } catch (e) {
      if (e.codice === "DOCUMENTO_ASSENTE") return null;
      throw e;
    }
    return this.#apriSePossibile(documento);
  }

  /** Cifra e salva: revisione 0 crea il documento, altrimenti indica quella attesa. Restituisce la ricevuta. */
  async salva(tipo, idDocumento, contenuto, revisione = 0) {
    const chiaro = BINARI.has(tipo) ? contenuto : testoInByte(JSON.stringify(contenuto));
    const {iv, dati} = await cifraDocumento(this.profilo, tipo, idDocumento, chiaro);
    verifica(this.ctx);
    return this.#richiesta("PUT", this.#documenti() + "/" + encodeURIComponent(idDocumento), {tipo, revisione, iv, dati});
  }

  async elimina(idDocumento, revisione) {
    const query = revisione === undefined ? "" : "?revisione=" + encodeURIComponent(revisione);
    return (await this.#richiesta("DELETE", this.#documenti() + "/" + encodeURIComponent(idDocumento) + query)).eliminato;
  }

  /** Invia foto e descrizione all'analisi: l'utente ha dato il consenso nel modulo. */
  async analizza(fotoBase64, caption) {
    return (await this.#richiesta("POST", "api/analisi-piatto", {consenso: true, foto: fotoBase64, caption})).proposta;
  }

  /** Vista della tabella DOCUMENTO_CIFRATO di H2, con i record di tutti i profili. */
  vistaH2(limite = 30) {
    return this.#richiesta("GET", "api/archivio-h2?limite=" + limite);
  }

  /** Un record di H2 completo, di qualunque profilo, così come è conservato. */
  recordH2(profilo, idDocumento) {
    return this.#richiesta("GET", "api/archivio-h2/" + encodeURIComponent(profilo) + "/" + encodeURIComponent(idDocumento));
  }
}
