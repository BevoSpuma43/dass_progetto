/**
 * Fixture sintetica v1: stessi profilo, schede, piatti, memorie, pasti e letture di
 * it.unisi.dass.persistenza.DatiDemo. Le foto restano file statici nella cartella demo/.
 */
export const PAZIENTE_ID = "paz-demo";
export const VERSIONE = "fixture-v1";
const REGOLA = "fixture-integrazione-v1";
const VERSIONE_PROFILO = "profilo-sintetico-v1";

/** Scheda del catalogo dimostrativo con ID versionato e valori per 100 g. */
function voce(id, nome, stato, carboidrati, proteine, grassi, kcal) {
  return {
    id: id + "-v1", nome, preparazione: stato, versione: VERSIONE, unita: "g",
    fonte: "DASS, valori sintetici per 100 g", indiceGlicemico: "non_disponibile",
    per100g: {carboidratiG: carboidrati, proteineG: proteine, grassiG: grassi, kcal}
  };
}

const PASTA_COTTA = {
  ...voce("pasta-cotta", "Pasta di semola", "cotto", 37.3, 6.9, 0.6, 175),
  versione: "CREA-000805-v1",
  fonte: "CREA, Pasta di semola cotta, bollita (www.alimentinutrizione.it/tabelle-nutrizionali/000805)"
};
// I valori per 100 g sono costruiti per il collaudo (50/7/8 g nella porzione pasta).
const PASTA = voce("pasta", "Pasta", "crudo", 60, 8, 2, 290);
const SUGO = voce("sugo", "Sugo di pomodoro", "cotto", 5, 1.5, 1, 35);
const OLIO = voce("olio", "Olio di oliva", "crudo", 0, 0, 100, 900);
const RISO = voce("riso", "Riso", "cotto", 25, 3, 1, 121);
const PISELLI = voce("piselli", "Piselli", "cotto", 10, 5, 1, 69);

/** Schede aggiunte al catalogo nutrizionale, come nella fixture del backend. */
export const VOCI_DEMO = [PASTA_COTTA, PASTA, SUGO, OLIO, RISO, PISELLI];

/**
 * Proposta usata per la foto di esempio quando non è impostata una chiave Anthropic.
 * Riproduce il caso di prova della guida tecnica: 80 g di pasta cruda, 40 g di sugo e 6 g di olio.
 */
export const PROPOSTA_ESEMPIO = {
  statoAnalisi: "da_confermare",
  origineAnalisi: "esempio",
  ingredienti: [
    {nome: "Fusilli", stato: "crudo", quantitaG: 80, origineQuantita: "caption",
      motivazione: "Peso a crudo indicato nella descrizione."},
    {nome: "Sugo di pomodoro", stato: "cotto", quantitaG: 40, origineQuantita: "stimata",
      motivazione: "Stima visiva del condimento per una porzione."},
    {nome: "Olio extravergine di oliva", stato: "crudo", quantitaG: 6, origineQuantita: "stimata",
      motivazione: "Stima di un cucchiaino d’olio aggiunto a crudo."}
  ],
  dubbi: ["Proposta di esempio preparata per la demo statica: non è stata generata da Haiku e vale per la foto di esempio."]
};

function secondi(istante) {
  return Date.parse(istante) / 1000;
}

/** Costruisce i record della fixture da salvare in IndexedDB al primo avvio o al ripristino. */
export function fixture() {
  const pazienti = [{
    id: PAZIENTE_ID, nome: "Paziente dimostrativo", versioneProfilo: VERSIONE_PROFILO,
    targetMgDl: 120, attesaMgDl: 120, glicemiaInizialeMgDl: 120, fattoreMgDlPerG: 4, insulinaAttivaIniziale: 0
  }];
  const piatti = [];
  const memorie = [];
  const episodi = [];
  const feedback = [];
  const esiti = [];

  const piatto = (id, nome, inizialeG) => {
    const p = {id, pazienteId: PAZIENTE_ID, nome, memoriaInizialeG: inizialeG};
    piatti.push(p);
    return p;
  };
  const memoria = (p, numero, grammi, varianza, segno) => {
    const m = {
      id: p.id + "-v" + numero, piattoId: p.id, numero, carboidratiEfficaciG: grammi, varianza,
      ultimoSegno: segno, segnaliPositiviConsecutivi: segno > 0 ? 1 : 0
    };
    memorie.push(m);
    return m;
  };
  const episodio = (id, m, istante, caption, totali, stima, righe) => {
    const e = {
      id, piattoId: m.piattoId, memoriaOriginaleId: m.id, chiaveInvio: "invio-" + id, improntaInvio: null,
      istantePastoEpochSecond: secondi(istante), caption, formatoFoto: "image/jpeg",
      foto: "demo/" + id + ".jpg", miniatura: "demo/" + id + "-mini.jpg",
      totali, stimaOriginale: stima, versioneRegola: REGOLA, versioneProfilo: VERSIONE_PROFILO,
      indiceGlicemico: "non_disponibile", anteprimaOriginale: null,
      ingredienti: righe.map(([v, grammi]) => ({
        id: id + "-" + v.id, voceCatalogoId: v.id, quantitaG: grammi, origineQuantita: "confermata_fixture"
      }))
    };
    episodi.push(e);
    return e;
  };
  const lettura = (pasto, valore, minuti, ammissibile, motivo, successiva) => {
    const f = {
      id: "lettura-" + pasto.id, episodioId: pasto.id, glicemiaMgDl: valore, minutiDopoPasto: minuti,
      istanteLetturaEpochSecond: pasto.istantePastoEpochSecond + 60 * minuti, ammissibile, motivo
    };
    feedback.push(f);
    esiti.push({
      id: "esito-" + pasto.id, feedbackId: f.id, precedenteId: pasto.memoriaOriginaleId,
      successivaId: successiva.id, versioneRegola: REGOLA, motivo
    });
  };

  const pastaPomodoro = piatto("piatto-pasta", "Pasta al pomodoro", 50);
  const iniziale = memoria(pastaPomodoro, 0, 50, 100, 0);
  const aggiornata = memoria(pastaPomodoro, 1, 52, 41.27439024, 1);
  for (let i = 1; i <= 4; i++) {
    const pasto = episodio("pasta-0" + i, i === 4 ? aggiornata : iniziale, `2026-11-${10 + i}T11:30:00Z`,
      "Pasta al pomodoro con olio", {carboidratiG: 50, proteineG: 7, grassiG: 8, kcal: 300}, i === 4 ? 5.2 : 5.0,
      [[PASTA, 80], [SUGO, 40], [OLIO, 6]]);
    if (i === 1) lettura(pasto, 145, 150, false, "fuori_finestra", iniziale);
    if (i === 2) lettura(pasto, 132, 270, false, "attivita_fisica", iniziale);
    if (i === 3) lettura(pasto, 139, 270, true, "aggiornamento_fixture", aggiornata);
    // Il quarto episodio attende la lettura.
  }

  const controllo = piatto("piatto-controllo", "Riso e piselli", 35);
  const base = memoria(controllo, 0, 35, 100, 0);
  episodio("controllo-01", base, "2026-11-15T18:45:00Z", "Riso con piselli",
    {carboidratiG: 35, proteineG: 8, grassiG: 2, kcal: 190}, 3.5, [[RISO, 100], [PISELLI, 100]]);

  return {pazienti, piatti, memorie, episodi, feedback, esiti};
}
