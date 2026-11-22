/**
 * Valida le righe dell'editor e prepara i dati usati da salvataggio, calcolo e registrazione.
 * La validazione avviene nel browser: Payara riceve soltanto la bozza cifrata.
 */
import {ErroreAnalisi} from "./errore.js";
import {suggerisci} from "./abbinamento.js";

const STATI = new Set(["cotto", "crudo", "non_noto"]);

/**
 * Valida ID, nomi, pesi e voci di catalogo. Assegna gli ID alle righe nuove e mantiene fonte e
 * provenienza quando la quantità e il contesto risultano invariati.
 */
export function prepara(righe, precedenti, catalogo) {
  if (!Array.isArray(righe) || righe.length > 30) throw invalida();
  const usati = new Set();
  return righe.map(r => {
    if (r === null || typeof r !== "object" || Array.isArray(r)) throw invalida();
    const id = typeof r.rigaId === "string" ? r.rigaId : "";
    const vecchia = precedenti.find(v => v.rigaId === id);
    if (id !== "") {
      if (!vecchia || usati.has(id)) throw invalida();
      usati.add(id);
    }
    const nome = typeof r.nome === "string" ? r.nome.trim() : "";
    if (nome === "" || nome.length > 500) throw invalida();
    const riferimento = r.voceCatalogoId ?? null;
    const voce = catalogo.find(v => v.id === riferimento);
    if (riferimento !== null && !voce) throw invalida();
    const peso = r.quantitaG ?? null;
    if (peso !== null && (typeof peso !== "number" || !(peso > 0))) throw invalida();
    const proposta = vecchia && nome === vecchia.nome ? suggerisci(vecchia, catalogo) : null;
    const manuale = r.sceltaCatalogoManuale === true || vecchia?.sceltaCatalogoManuale === true ||
      (proposta !== null && riferimento !== (proposta.voceCatalogoId ?? null));
    const nota = !manuale && proposta && "notaAbbinamento" in proposta ? proposta : null;
    let stato = voce ? voce.preparazione : (typeof r.stato === "string" ? r.stato : "non_noto");
    if (nota) stato = nota.stato;
    if (!STATI.has(stato)) throw invalida();
    let origine = peso === null ? "da_chiarire" : "corretta";
    if (peso !== null && vecchia && typeof vecchia.quantitaG === "number" && vecchia.quantitaG === peso &&
        nome === vecchia.nome && stato === vecchia.stato) {
      origine = vecchia.origineQuantita;
    }
    let abbinamento = voce ? "abbinato" : "da_chiarire";
    if (!voce && vecchia && nome === vecchia.nome && (vecchia.voceCatalogoId ?? null) === null) {
      abbinamento = vecchia.statoAbbinamento ?? abbinamento;
    }
    const aggiornata = {
      rigaId: id === "" ? crypto.randomUUID() : id, nome, stato, quantitaG: peso, unita: "g",
      origineQuantita: origine, voceCatalogoId: riferimento, statoAbbinamento: abbinamento
    };
    if (manuale) aggiornata.sceltaCatalogoManuale = true;
    if (nota) aggiornata.notaAbbinamento = nota.notaAbbinamento;
    if (vecchia && origine !== "corretta" && nome === vecchia.nome && stato === vecchia.stato &&
        origine === vecchia.origineQuantita) {
      for (const k of ["motivazione", "fonteUrl", "fonteTitolo"]) if (k in vecchia) aggiornata[k] = vecchia[k];
    }
    return aggiornata;
  });
}

/** Errore HTTP 400 usato quando una riga della bozza non è valida. */
function invalida() {
  return new ErroreAnalisi(400, "RIGA_NON_VALIDA", "Controlla nome, peso positivo e voce di catalogo per ogni ingrediente.");
}
