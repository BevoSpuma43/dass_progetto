package it.unisi.dass.sicurezza;

import it.unisi.dass.analisi.ErroreAnalisi;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Canale cifrato delle azioni del diario. Ogni richiesta verso i documenti di un profilo o verso
 * l'analisi deve indicare uno scambio di chiavi valido e una busta cifrata con la chiave condivisa,
 * che dichiara metodo, percorso, istante e un nonce mai usato. Il corpo arriva cifrato e la risposta
 * della risorsa viene cifrata prima di lasciare Payara.
 */
@WebFilter(urlPatterns = {"/api/profili/*", "/api/analisi-piatto", "/api/archivio-h2/*"})
public class CanaleCifratoFilter implements Filter {
    private static final Pattern PERCORSO_PROFILO = Pattern.compile("^/api/profili/([^/]+)(?:/.*)?$");
    private static final Pattern NONCE = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final int MASSIMO_CORPO = 16 * 1024 * 1024;
    private Clock orologio = Clock.systemUTC();

    @Inject
    ScambioChiavi scambi;

    /** Descrittore autenticato della richiesta. */
    record Descrittore(String metodo, String percorso, String nonce, long istante) {}

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain catena) throws IOException, ServletException {
        var richiesta = (HttpServletRequest) req;
        var risposta = (HttpServletResponse) res;
        String percorso = richiesta.getRequestURI().substring(richiesta.getContextPath().length());
        // L'elenco dei profili è pubblico: contiene solo identificativi e nomi.
        if (percorso.equals("/api/profili") || percorso.equals("/api/profili/")) {
            catena.doFilter(req, res);
            return;
        }
        ScambioChiavi.Sessione sessione;
        Descrittore descrittore;
        byte[] corpo;
        try {
            sessione = scambi.sessione(richiesta.getHeader(CanaleCifrato.INTESTAZIONE_SCAMBIO));
            descrittore = verificaBusta(sessione, richiesta);
            var profilo = PERCORSO_PROFILO.matcher(percorso);
            if (profilo.matches() && !profilo.group(1).equals(sessione.profiloId))
                throw new ErroreAnalisi(403, "SCAMBIO_DI_ALTRO_PROFILO", "Questo scambio di chiavi appartiene a un altro profilo.");
            corpo = leggiCorpo(sessione, descrittore, richiesta);
        } catch (ErroreAnalisi e) {
            rispondiInChiaro(risposta, e);
            return;
        }
        var raccolta = new RispostaRaccolta(risposta);
        catena.doFilter(new RichiestaInChiaro(richiesta, corpo), raccolta);
        byte[] iv = CanaleCifrato.nuovoIv();
        byte[] dati = CanaleCifrato.cifra(sessione.chiave, iv, raccolta.contenuto(),
                CanaleCifrato.aadRisposta(sessione.id, descrittore.nonce()));
        byte[] busta = Json.createObjectBuilder().add("iv", Base64.getEncoder().encodeToString(iv))
                .add("dati", Base64.getEncoder().encodeToString(dati)).build().toString().getBytes(StandardCharsets.UTF_8);
        risposta.setStatus(raccolta.stato());
        risposta.setContentType("application/json");
        risposta.setHeader(CanaleCifrato.INTESTAZIONE_CIFRATO, "1");
        risposta.setHeader("Cache-Control", "no-store");
        risposta.setContentLength(busta.length);
        risposta.getOutputStream().write(busta);
    }

    /**
     * Decifra la busta dell'intestazione X-DASS-Busta e controlla che descriva proprio questa richiesta,
     * che sia recente e che il nonce non sia già stato usato nello scambio.
     */
    Descrittore verificaBusta(ScambioChiavi.Sessione sessione, HttpServletRequest richiesta) {
        String busta = richiesta.getHeader(CanaleCifrato.INTESTAZIONE_BUSTA);
        String[] parti = busta == null ? new String[0] : busta.split("\\.", -1);
        if (parti.length != 2) throw new ErroreAnalisi(400, "BUSTA_ASSENTE", "Manca la busta cifrata della richiesta.");
        byte[] iv;
        byte[] cifrato;
        try {
            iv = Base64.getUrlDecoder().decode(parti[0]);
            cifrato = Base64.getUrlDecoder().decode(parti[1]);
        } catch (IllegalArgumentException e) {
            throw new ErroreAnalisi(400, "BUSTA_NON_VALIDA", "Busta cifrata illeggibile.");
        }
        byte[] chiaro = CanaleCifrato.decifra(sessione.chiave, iv, cifrato, CanaleCifrato.aadRichiesta(sessione.id));
        JsonObject json;
        try (var reader = Json.createReader(new ByteArrayInputStream(chiaro))) {
            json = reader.readObject();
        } catch (RuntimeException e) {
            throw new ErroreAnalisi(400, "BUSTA_NON_VALIDA", "Descrittore della richiesta illeggibile.");
        }
        if (!(json.get("metodo") instanceof JsonString metodo) || !(json.get("percorso") instanceof JsonString percorso)
                || !(json.get("nonce") instanceof JsonString nonce) || !(json.get("istante") instanceof JsonNumber istante))
            throw new ErroreAnalisi(400, "BUSTA_NON_VALIDA", "Descrittore della richiesta incompleto.");
        String atteso = richiesta.getRequestURI() + (richiesta.getQueryString() == null ? "" : "?" + richiesta.getQueryString());
        if (!metodo.getString().equals(richiesta.getMethod()) || !percorso.getString().equals(atteso))
            throw new ErroreAnalisi(400, "BUSTA_NON_CORRISPONDENTE", "La busta cifrata descrive un'altra richiesta.");
        if (Math.abs(orologio.millis() - istante.longValue()) > CanaleCifrato.FINESTRA_MS)
            throw new ErroreAnalisi(400, "BUSTA_SCADUTA", "La busta cifrata è troppo vecchia: controlla l'orologio del computer.");
        if (!NONCE.matcher(nonce.getString()).matches() || !sessione.registraNonce(nonce.getString()))
            throw new ErroreAnalisi(409, "BUSTA_RIPETUTA", "Questa richiesta cifrata è già stata usata.");
        if (sessione.superaLimite())
            throw new ErroreAnalisi(429, "SCAMBIO_ESAURITO", "Troppe richieste per questo scambio di chiavi. Riprova l'azione.");
        return new Descrittore(metodo.getString(), percorso.getString(), nonce.getString(), istante.longValue());
    }

    /** Decifra il corpo {iv, dati} della richiesta; senza corpo restituisce un array vuoto. */
    static byte[] leggiCorpo(ScambioChiavi.Sessione sessione, Descrittore descrittore, HttpServletRequest richiesta) {
        byte[] grezzo;
        try (var input = richiesta.getInputStream()) {
            grezzo = input.readNBytes(MASSIMO_CORPO + 1);
        } catch (IOException e) {
            throw new ErroreAnalisi(400, "CORPO_ILLEGGIBILE", "Corpo della richiesta illeggibile.");
        }
        if (grezzo.length > MASSIMO_CORPO) throw new ErroreAnalisi(413, "CORPO_GRANDE", "Il corpo cifrato supera 16 MiB.");
        if (grezzo.length == 0) return grezzo;
        try (var reader = Json.createReader(new ByteArrayInputStream(grezzo))) {
            var busta = reader.readObject();
            byte[] iv = Base64.getDecoder().decode(busta.getString("iv"));
            byte[] dati = Base64.getDecoder().decode(busta.getString("dati"));
            return CanaleCifrato.decifra(sessione.chiave, iv, dati, CanaleCifrato.aadCorpo(sessione.id, descrittore.nonce()));
        } catch (ErroreAnalisi e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ErroreAnalisi(400, "CORPO_NON_VALIDO", "Il corpo deve essere una busta cifrata {iv, dati}.");
        }
    }

    /** Gli errori del canale restano in chiaro: senza uno scambio valido non c'è una chiave per cifrarli. */
    private static void rispondiInChiaro(HttpServletResponse risposta, ErroreAnalisi e) throws IOException {
        byte[] corpo = Json.createObjectBuilder().add("codice", e.codice()).add("messaggio", e.getMessage()).build()
                .toString().getBytes(StandardCharsets.UTF_8);
        risposta.setStatus(e.statoHttp());
        risposta.setContentType("application/json");
        risposta.setCharacterEncoding("UTF-8");
        risposta.setHeader("Cache-Control", "no-store");
        risposta.setContentLength(corpo.length);
        risposta.getOutputStream().write(corpo);
    }

    /** Richiesta che la risorsa Jakarta REST legge come se fosse arrivata in chiaro. */
    static final class RichiestaInChiaro extends HttpServletRequestWrapper {
        private final byte[] corpo;

        RichiestaInChiaro(HttpServletRequest richiesta, byte[] corpo) {
            super(richiesta);
            this.corpo = corpo;
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(corpo);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int inizio, int lunghezza) { return input.read(b, inizio, lunghezza); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener ascoltatore) { throw new UnsupportedOperationException(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(corpo), StandardCharsets.UTF_8));
        }

        @Override public int getContentLength() { return corpo.length; }
        @Override public long getContentLengthLong() { return corpo.length; }

        @Override
        public String getContentType() {
            return corpo.length > 0 ? "application/json" : super.getContentType();
        }

        @Override
        public String getHeader(String nome) {
            if ("Content-Length".equalsIgnoreCase(nome)) return String.valueOf(corpo.length);
            if ("Content-Type".equalsIgnoreCase(nome) && corpo.length > 0) return "application/json";
            return super.getHeader(nome);
        }

        @Override
        public Enumeration<String> getHeaders(String nome) {
            if ("Content-Length".equalsIgnoreCase(nome) || ("Content-Type".equalsIgnoreCase(nome) && corpo.length > 0))
                return Collections.enumeration(List.of(getHeader(nome)));
            return super.getHeaders(nome);
        }
    }

    /** Risposta trattenuta in memoria, per cifrarla prima di inviarla. */
    static final class RispostaRaccolta extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int stato = HttpServletResponse.SC_OK;
        private ServletOutputStream flusso;
        private PrintWriter scrittore;

        RispostaRaccolta(HttpServletResponse risposta) {
            super(risposta);
        }

        @Override public void setStatus(int codice) { stato = codice; }
        @Override public void sendError(int codice) { stato = codice; }
        @Override public void sendError(int codice, String messaggio) { stato = codice; }
        @Override public int getStatus() { return stato; }
        @Override public void setContentLength(int lunghezza) {}
        @Override public void setContentLengthLong(long lunghezza) {}
        @Override public boolean isCommitted() { return false; }
        @Override public void resetBuffer() { buffer.reset(); }

        @Override
        public void reset() {
            buffer.reset();
            stato = HttpServletResponse.SC_OK;
        }

        @Override
        public void flushBuffer() {
            if (scrittore != null) scrittore.flush();
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (flusso == null) {
                flusso = new ServletOutputStream() {
                    @Override public void write(int b) { buffer.write(b); }
                    @Override public void write(byte[] b, int inizio, int lunghezza) { buffer.write(b, inizio, lunghezza); }
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener ascoltatore) { throw new UnsupportedOperationException(); }
                };
            }
            return flusso;
        }

        @Override
        public PrintWriter getWriter() {
            if (scrittore == null) scrittore = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8), true);
            return scrittore;
        }

        int stato() {
            return stato;
        }

        byte[] contenuto() {
            if (scrittore != null) scrittore.flush();
            return buffer.toByteArray();
        }
    }
}
