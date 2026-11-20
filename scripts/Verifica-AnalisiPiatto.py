"""Collaudo HTTP di analisi, anteprima e diario. Richiede Python 3.9+, il WAR compilato e Payara preparato."""
import hashlib
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
from contextlib import contextmanager
from urllib import error, request
from uuid import UUID, uuid4
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
CLASSE = "it/unisi/dass/analisi/ServletAnalisiCollaudo.class"
FOTO = (ROOT / "src/main/resources/demo/pasta-04.jpg").read_bytes()
HTTP = request.build_opener(request.ProxyHandler({}))


def chiama(base, percorso, metodo="GET", corpo=None, headers=None, atteso=200, location=False):
    req = request.Request(base + percorso, data=corpo, headers=headers or {}, method=metodo)
    try:
        risposta = HTTP.open(req, timeout=10)
    except error.HTTPError as exc:
        risposta = exc
    with risposta:
        dati = risposta.read()
        assert risposta.status == atteso, (percorso, risposta.status, atteso, dati[:500])
        if risposta.headers.get_content_type() == "application/json":
            risultato = json.loads(dati)
            if location:
                assert risposta.headers["Location"] == base + "/api/pasti/" + risultato["pastoId"]
            return risultato
        return dati


def analizza(base, percorso, foto, caption=None, atteso=200):
    confine = uuid4().hex
    parti = []
    if foto is not None:
        parti.append(b'Content-Disposition: form-data; name="foto"; filename="test.jpg"\r\n'
                     b'Content-Type: image/jpeg\r\n\r\n' + foto)
    if caption is not None:
        parti.append(b'Content-Disposition: form-data; name="caption"\r\n\r\n' + caption.encode("utf8"))
    marker = b"--" + confine.encode("ascii")
    corpo = b"".join(marker + b"\r\n" + p + b"\r\n" for p in parti) + marker + b"--\r\n"
    return chiama(base, percorso, "POST", corpo, {"Content-Type": "multipart/form-data; boundary=" + confine,
                                                 "X-DASS-Analisi": "1"}, atteso)


def salva(base, id_bozza, revisione, righe, atteso=200):
    return chiama(base, "/api/bozze/" + id_bozza + "/ingredienti", "PUT",
                  json.dumps({"revisione": revisione, "ingredienti": righe}).encode("utf8"),
                  {"Content-Type": "application/json"}, atteso)


@contextmanager
def server(dir_dati, war, java, payara):
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        porta = s.getsockname()[1]
    base = f"http://127.0.0.1:{porta}/dass"
    env = os.environ.copy()
    env.pop("ANTHROPIC_API_KEY", None)
    percorso_log = dir_dati / "payara.log"
    with percorso_log.open("wb") as log:
        processo = subprocess.Popen([str(java), "-jar", str(payara), "--noCluster", "--disablePhoneHome",
                                    "--rootdir", str(dir_dati / ("server-" + uuid4().hex)),
                                    "--prebootcommandfile", str(ROOT / "scripts/preboot.asadmin"),
                                    "--port", str(porta), "--contextroot", "/dass", "--deploy", str(war)],
                                   cwd=dir_dati, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(120):
                if processo.poll() is not None or "Server failed to start" in percorso_log.read_text(errors="replace"):
                    raise RuntimeError(f"Avvio di Payara fallito. Consultare {percorso_log}")
                try:
                    with HTTP.open(base + "/api/demo/archivio", timeout=2) as risposta:
                        if json.load(risposta)["episodi"] >= 5:
                            break
                except (OSError, ValueError):
                    pass
                time.sleep(1)
            else:
                raise RuntimeError("Payara non pronto")
            yield base
        finally:
            if processo.poll() is None:
                processo.terminate()
            processo.wait(timeout=30)


def verifica_anteprima(base, bozza):
    url = "/api/bozze/" + bozza["bozzaId"] + "/anteprima"

    def calcola(righe, status=200, **opzioni):
        dati = {"revisione": bozza["revisione"], "ingredienti": righe, **opzioni}
        return chiama(base, url, "POST", json.dumps(dati).encode(), {"Content-Type": "application/json"}, status)

    pasta, sugo, triglia, _ = bozza["ingredienti"]
    olio = {"nome": "Olio", "voceCatalogoId": "olio-v1", "quantitaG": 6}
    porzione = [pasta, dict(sugo, quantitaG=40), olio]
    prima = calcola(porzione)
    assert prima == calcola(porzione) and prima["stato"] == "completa"
    assert prima["totali"] == {"carboidratiG": 50, "proteineG": 7, "grassiG": 8, "kcal": 300}
    assert prima["simulazione"]["unitaSimulate"] == 5 and prima["simulazione"]["memoriaInizialeG"] == 50
    assert prima["profilo"]["id"] == "paz-demo" and prima["versioneRegola"] == "demo-sintetica-v1"
    assert prima["righe"][0]["origineQuantita"] == "caption"
    assert prima["righe"][0]["catalogo"]["preparazione"] == "crudo"
    assert all(r["catalogo"]["fonte"] and r["catalogo"]["indiceGlicemico"] == "non_disponibile" for r in prima["righe"])
    doppia = calcola([dict(pasta, quantitaG=160)])["totali"]
    assert doppia == {k: v * 2 for k, v in calcola([pasta])["totali"].items()}
    assert calcola([dict(pasta, quantitaG=1.25)])["totali"]["carboidratiG"] == 0.75
    for parziale in ([], [pasta, sugo], [dict(triglia, quantitaG=90)]):
        esito = calcola(parziale)
        assert esito["stato"] == "incompleta" and esito["daCompletare"]
        assert esito["totali"] is None and esito["simulazione"] is None
    assert calcola(porzione, 409, revisione=-1)["codice"] == "REVISIONE_SUPERATA"
    assert calcola(porzione, 400, scenarioId="altro")["codice"] == "SCENARIO_NON_VALIDO"
    assert calcola(porzione, 400, targetMgDl=100)["codice"] == "CAMPI_NON_VALIDI"
    riletta = chiama(base, "/api/bozze/" + bozza["bozzaId"])
    assert riletta["ingredienti"] == bozza["ingredienti"] and riletta["revisione"] == bozza["revisione"]
    print("Anteprima OK: 50/7/8 g, 300 kcal, 5 unità simulate. Verificati raddoppio, incompletezza e sola lettura.", flush=True)


def verifica_registrazione(base, bozza, foto):
    conferma = {"richiestaId": str(uuid4()), "bozzaId": bozza["bozzaId"], "revisione": bozza["revisione"],
                "consumatoAlle": "2026-11-19T20:15:00+01:00", "nomePiatto": "Pasta del collaudo HTTP",
                "ingredienti": bozza["ingredienti"]}

    def posta(dati, status=201):
        return chiama(base, "/api/pasti", "POST", json.dumps(dati).encode("utf-8"),
                      {"Content-Type": "application/json"}, status, location=status == 201)

    assert posta(dict(conferma, ingredienti=[]), 400)["codice"] == "PASTO_INCOMPLETO"
    assert chiama(base, "/api/demo/archivio")["episodi"] == 5
    identificativi = posta(conferma)
    assert posta(conferma) == identificativi
    assert posta(dict(conferma, nomePiatto="Un altro piatto"), 409)["codice"] == "RICHIESTA_GIA_USATA"
    pasto, piatto = identificativi["pastoId"], identificativi["piattoId"]
    url = "/api/pasti/" + pasto
    dettaglio = chiama(base, url)
    assert dettaglio["consumatoAlle"] == "2026-11-19T19:15:00Z" and dettaglio["feedback"] is None
    assert dettaglio["totali"]["carboidratiG"] == 54 and dettaglio["stimaOriginale"] == 5.4
    assert dettaglio["ingredienti"][0]["origineQuantita"] == "corretta"
    assert chiama(base, url + "/foto") == foto
    assert chiama(base, url + "/foto?miniatura=true").startswith(b"\xff\xd8")
    assert [r["pastoId"] for r in chiama(base, f"/api/piatti/{piatto}/pasti")] == [pasto]
    assert chiama(base, "/api/pazienti/paz-demo/piatti")[0]["piattoId"] == piatto
    assert chiama(base, "/api/pasti/pasta-03")["feedback"]["motivo"] == "aggiornamento_fixture"
    assert chiama(base, "/api/pasti/ignoto", atteso=404)["codice"] == "PASTO_ASSENTE"
    assert chiama(base, "/api/demo/archivio")["episodi"] == 6
    storico = chiama(base, "/api/pazienti/paz-demo/pasti")
    assert len(storico) == 6 and storico[0]["pastoId"] == pasto
    assert storico[0]["totali"] == dettaglio["totali"] and storico[0]["stimaOriginale"] == dettaglio["stimaOriginale"]
    risorsa_bozza = "/api/bozze/" + bozza["bozzaId"]
    assert chiama(base, risorsa_bozza + "?revisione=-1", "DELETE", atteso=400)["codice"] == "REVISIONE_NON_VALIDA"
    assert chiama(base, risorsa_bozza + "?revisione=0", "DELETE", atteso=409)["codice"] == "REVISIONE_SUPERATA"
    chiama(base, risorsa_bozza + f'?revisione={bozza["revisione"]}', "DELETE", atteso=204)
    chiama(base, risorsa_bozza, "DELETE", atteso=204)
    assert chiama(base, risorsa_bozza, atteso=404)["codice"] == "BOZZA_ASSENTE"
    assert chiama(base, risorsa_bozza + "/foto", atteso=404)["codice"] == "BOZZA_ASSENTE"
    assert chiama(base, url) == dettaglio
    print("Diario HTTP OK: Location, conferma idempotente, conflitto, foto, storico e fixture.", flush=True)
    return url, dettaglio


def collaudo(dir_dati, pacchetto_precedente=None):
    java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
    payara = Path(os.environ["DASS_PAYARA_JAR"]).resolve()
    war = dir_dati / "dass.war"
    if pacchetto_precedente:
        with ZipFile(pacchetto_precedente) as precedente:
            war.write_bytes(precedente.read("target/dass.war"))
        with server(dir_dati, war, java, payara) as base:
            assert chiama(base, "/api/demo/archivio")["feedback"] == 3
        print("Creato archivio di prova con il WAR originale dello step 03.", flush=True)
    shutil.copyfile(ROOT / "target/dass.war", war)
    with ZipFile(war, "a") as z:
        assert not any("ServletAnalisiCollaudo" in n for n in z.namelist())
        z.write(ROOT / "target/test-classes" / CLASSE, "WEB-INF/classes/" + CLASSE)

    with server(dir_dati, war, java, payara) as base:
        specifica = chiama(base, "/openapi.json")
        assert specifica["openapi"] == "3.0.3" and "delete" in specifica["paths"]["/api/bozze/{id}"]
        assert "get" in specifica["paths"]["/api/pazienti/{id}/pasti"]
        assert b'Swagger UI' in chiama(base, "/swagger.html")
        catalogo = chiama(base, "/api/catalogo")
        assert len(catalogo) == 375 and all(v["fonte"] and v["unita"] == "g" for v in catalogo)
        assert {"pasta-v1", "pasta-cotta-v1"} <= {v["id"] for v in chiama(base, "/api/catalogo?q=PASTA")}
        assert not chiama(base, "/api/catalogo?q=inesistente")
        farina, = chiama(base, "/api/catalogo?q=farina%2000")
        assert farina["id"] == "usda-fdc-2003586-2026-10-29" and "USDA FoodData Central" in farina["fonte"]
        farina0, = chiama(base, "/api/catalogo?q=farina%200")
        assert farina0["id"] == "crea-000230-v1"
        croissant = analizza(base, "/collaudo/analisi", FOTO, "croissant vuoto")
        assert croissant["ingredienti"][0]["voceCatalogoId"] == farina["id"]
        dati_croissant = {"revisione": croissant["revisione"], "ingredienti": croissant["ingredienti"]}
        anteprima_croissant = chiama(base, "/api/bozze/" + croissant["bozzaId"] + "/anteprima", "POST",
                                    json.dumps(dati_croissant).encode(), {"Content-Type": "application/json"})
        assert anteprima_croissant["stato"] == "completa"
        assert anteprima_croissant["totali"] == {"carboidratiG": 37.2, "proteineG": 5.7, "grassiG": 0.76, "kcal": 183}
        assert analizza(base, "/api/analisi-piatto", None, "test", 400)["codice"] == "FOTO_ASSENTE"
        for file in (b"testo", b"\xff\xd8\xff\xe0\x00\x10JFIF"):
            assert analizza(base, "/api/analisi-piatto", file, atteso=415)["codice"] == "FOTO_ILLEGGIBILE"
        assert analizza(base, "/api/analisi-piatto", FOTO, "x" * 501, 400)["codice"] == "CAPTION_LUNGA"
        errore = analizza(base, "/api/analisi-piatto", FOTO, atteso=503)
        assert errore["codice"] == "CHIAVE_ASSENTE" and errore["origineAnalisi"] == "manuale"
        bozza = errore["bozzaId"]
        foto_salvata = chiama(base, "/" + errore["fotoUrl"])
        assert foto_salvata.startswith(b"\xff\xd8")
        righe = [{"nome": "Pasta", "voceCatalogoId": "pasta-v1", "quantitaG": 85}]
        manuale = salva(base, bozza, errore["revisione"], righe)
        assert manuale["ingredienti"][0]["origineQuantita"] == "corretta"
        assert salva(base, bozza, errore["revisione"], righe, 409)["codice"] == "REVISIONE_SUPERATA"
        for r in (dict(righe[0], quantitaG=0), dict(righe[0], voceCatalogoId="manca")):
            assert salva(base, bozza, manuale["revisione"], [r], 400)["codice"] == "RIGA_NON_VALIDA"

        proposta = analizza(base, "/collaudo/analisi", FOTO, "80 g di pasta a crudo con sugo")
        cena = analizza(base, "/collaudo/analisi", FOTO, "Pasta al ragù di triglia al ristorante")
        pesce, olio, fusilli = cena["ingredienti"]
        assert [r["quantitaG"] for r in cena["ingredienti"]] == [70, 5, 180]
        assert pesce["voceCatalogoId"] is None and fusilli["voceCatalogoId"] == "pasta-cotta-v1"
        assert pesce["fonteTitolo"] == "Ricetta di collaudo" and olio["origineQuantita"] == "ricetta"
        confermata = salva(base, cena["bozzaId"], cena["revisione"], cena["ingredienti"])
        assert confermata["ingredienti"] == cena["ingredienti"]
        modificata = salva(base, cena["bozzaId"], confermata["revisione"], [pesce, dict(olio,quantitaG=8), fusilli])
        assert modificata["ingredienti"][1]["origineQuantita"] == "corretta"
        assert "fonteUrl" not in modificata["ingredienti"][1] and modificata["ingredienti"][0] == pesce
        verifica_anteprima(base, proposta)
        assert proposta["origineAnalisi"] == "simulata"
        pasta, sugo, triglia, riso = proposta["ingredienti"]
        assert (pasta["voceCatalogoId"], pasta["quantitaG"], pasta["origineQuantita"]) == ("pasta-v1", 80, "caption")
        assert sugo["voceCatalogoId"] == "sugo-v1" and sugo["quantitaG"] is None
        assert triglia["voceCatalogoId"] is None and triglia["statoAbbinamento"] == "sconosciuto"
        assert triglia["quantitaG"] is None and triglia["origineQuantita"] == "da_chiarire"
        assert riso["voceCatalogoId"] is None and riso["statoAbbinamento"] == "da_chiarire"
        corretta = salva(base, proposta["bozzaId"], proposta["revisione"],
                        [pasta, dict(sugo, quantitaG=40), triglia, riso])["ingredienti"]
        assert [r["origineQuantita"] for r in corretta] == ["caption", "corretta", "da_chiarire", "stimata"]
        foto_sola = analizza(base, "/collaudo/analisi", FOTO)
        assert foto_sola["ingredienti"][0]["origineQuantita"] == "stimata"
        assert chiama(base, "/api/demo/archivio")["episodi"] == 5
        print("HTTP OK: catalogo, file invalidi, due analisi simulate, bozza manuale e conflitto di revisione.", flush=True)

    # Su questo solo DB temporaneo portiamo una seconda bozza oltre la scadenza.
    driver = dir_dati / "h2.jar"
    with ZipFile(payara) as z:
        driver.write_bytes(z.read("MICRO-INF/runtime/h2.jar"))
    scaduta = str(UUID(foto_sola["bozzaId"]))
    sql = f"update BOZZA set SCADEEPOCHSECOND=0 where ID='{scaduta}'"
    cp = subprocess.run([str(java), "-cp", str(driver), "org.h2.tools.Shell",
                         "-url", "jdbc:h2:file:" + (dir_dati / "dass").as_posix() + ";IFEXISTS=TRUE",
                         "-user", "sa", "-password", "", "-sql", sql], capture_output=True, check=True)
    assert b"Update count: 1" in cp.stdout, cp.stdout
    with server(dir_dati, war, java, payara) as base:
        assert chiama(base, "/api/bozze/" + bozza)["ingredienti"] == manuale["ingredienti"]
        assert hashlib.sha256(chiama(base, "/" + errore["fotoUrl"])).digest() == hashlib.sha256(foto_salvata).digest()
        assert chiama(base, "/api/bozze/" + scaduta, atteso=404)["codice"] == "BOZZA_ASSENTE"
        assert chiama(base, "/api/bozze/" + scaduta + "/foto", atteso=404)["codice"] == "BOZZA_ASSENTE"
        righe[0]["quantitaG"] = 90
        aggiornata = salva(base, bozza, manuale["revisione"], righe)
        assert aggiornata["ingredienti"][0]["quantitaG"] == 90
        assert chiama(base, "/api/demo/archivio")["episodi"] == 5
        print("Riavvio OK: foto e correzioni conservate, bozza scaduta inaccessibile, cinque episodi iniziali.")
        url_pasto, istantanea = verifica_registrazione(base, aggiornata, foto_salvata)

    with server(dir_dati, war, java, payara) as base:
        assert chiama(base, url_pasto) == istantanea
        assert chiama(base, url_pasto + "/foto") == foto_salvata
        assert chiama(base, "/api/demo/archivio")["episodi"] == 6
        print("Secondo riavvio OK: episodio, istantanea e foto ricaricati dal database su file.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--da-step03", type=Path, metavar="ZIP", help="prova anche il passaggio dal WAR precedente")
    opzioni = parser.parse_args()
    temp_root = Path(tempfile.gettempdir()).resolve()
    tmp = temp_root / ("dass-analisi-" + uuid4().hex)
    # Eredita i permessi della directory temporanea anche per la seconda JVM su Windows.
    tmp.mkdir()
    try:
        collaudo(tmp, opzioni.da_step03)
    except BaseException:
        print("Collaudo interrotto. Log e database temporanei in", tmp)
        raise
    else:
        if tmp.resolve().parent == temp_root:
            shutil.rmtree(tmp)
