package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in for {@code opencode serve} on loopback: the three endpoints the allure-ai client uses
 * ({@code /config/providers}, {@code POST /session}, {@code POST /session/&#123;id&#125;/message}).
 * The same shape as the stub in the allure-ai core tests, so the server side is exercised against
 * the protocol the core really speaks instead of a mock of the core.
 * <p>
 * {@link Mode#ANSWERS} replies with a well-formed analysis for every cluster; {@link Mode#GARBAGE}
 * replies with prose the response parser cannot read, which is what an unusable model looks like
 * from the outside - including the single re-ask the core makes before giving up.
 * {@link Mode#PARTIAL} answers like {@link Mode#ANSWERS} except for the cluster of
 * {@link #PARTIAL_UNANSWERED}, which gets the prose: one cluster answered, one left for a retry.
 * <p>
 * {@link #requirePassword(String, String)} makes it behave like an {@code opencode serve} started
 * with {@code OPENCODE_SERVER_PASSWORD}: every endpoint answers {@code 401} unless the request
 * carries HTTP Basic with exactly that pair.
 */
final class OpenCodeStub implements AutoCloseable {

    enum Mode {ANSWERS, GARBAGE, PARTIAL}

    /**
     * The reason is deliberately wordy: the rebuilt report must outgrow the pending one by more than
     * the kilobyte {@code ReportEntity.size} is counted in, with room to spare.
     */
    static final String ANALYSIS = "{\"causeClass\":\"product\",\"confidence\":0.8,"
        + "\"reason\":\"the service replied 500 to a valid request: its upstream dependency timed out"
        + " while the request was being processed, which the service log of this build shows\","
        + "\"recommendation\":\"check the service log for this build\",\"bugDraft\":null}";
    private static final String NONSENSE = "I am afraid I cannot answer in JSON right now";
    /** Scenario name whose cluster {@link Mode#PARTIAL} leaves without an answer. */
    static final String PARTIAL_UNANSWERED = "beta";

    private final HttpServer server;
    private final AtomicInteger sessions = new AtomicInteger();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    /** Message endpoints of the sessions {@link Mode#PARTIAL} answers with prose. */
    private final Set<String> refusingSessions = ConcurrentHashMap.newKeySet();
    private volatile Mode mode = Mode.ANSWERS;
    /** The {@code Basic ...} header this stub accepts, or {@code null} while it asks for none. */
    private volatile String expectedAuth;

    private OpenCodeStub(HttpServer server) {
        this.server = server;
    }

    static OpenCodeStub start() {
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final OpenCodeStub stub = new OpenCodeStub(server);
            server.createContext("/config/providers", stub::providers);
            server.createContext("/session", stub::session);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int port() {
        return server.getAddress().getPort();
    }

    void mode(Mode mode) {
        this.mode = mode;
    }

    /**
     * Makes every endpoint demand HTTP Basic with this pair, as a password-protected
     * {@code opencode serve} does. An empty username means the {@code opencode} default.
     */
    void requirePassword(String username, String password) {
        final String user = username == null || username.isEmpty() ? "opencode" : username;
        this.expectedAuth = "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Back to a server that asks for nothing. */
    void requireNoPassword() {
        this.expectedAuth = null;
    }

    /** The {@code Authorization} headers the stub saw, {@code "none"} for a request without one. */
    List<String> authHeaders() {
        return List.copyOf(authHeaders);
    }

    /** How many {@code POST /session} calls this stub has served - i.e. how often it was talked to. */
    int sessionsStarted() {
        return sessions.get();
    }

    /**
     * Raw bodies of the {@code POST /session/&#123;id&#125;/message} calls, in the order they arrived: what
     * the core actually sent the model, prompts included.
     */
    List<String> messages() {
        return List.copyOf(messages);
    }

    /** Forgets what was recorded - the stub is shared by every test of its class. */
    void clear() {
        messages.clear();
        authHeaders.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void providers(HttpExchange exchange) throws IOException {
        if (rejected(exchange)) {
            return;
        }
        reply(exchange, "{\"providers\":[{\"id\":\"litellm\",\"models\":{\"qwen3.8\":{}}}]}");
    }

    /**
     * Records the credentials of the request and answers {@code 401} when they are not the ones
     * asked for; {@code true} means the exchange is already finished.
     */
    private boolean rejected(HttpExchange exchange) throws IOException {
        final String header = exchange.getRequestHeaders().getFirst("Authorization");
        authHeaders.add(header == null ? "none" : header);
        final String expected = expectedAuth;
        if (expected == null || expected.equals(header)) {
            return false;
        }
        final byte[] body = "Unauthorized".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(401, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        return true;
    }

    private void session(HttpExchange exchange) throws IOException {
        if (rejected(exchange)) {
            return;
        }
        final String path = exchange.getRequestURI().getPath();
        final byte[] body = exchange.getRequestBody().readAllBytes();
        if ("/session".equals(path)) {
            reply(exchange, "{\"id\":\"ses_" + sessions.incrementAndGet() + "\",\"title\":\"stub\"}");
            return;
        }
        if (path.endsWith("/message")) {
            new ObjectMapper().readTree(body); // the client must send a parseable prompt envelope
            messages.add(new String(body, StandardCharsets.UTF_8));
            final String text = answers(path, new String(body, StandardCharsets.UTF_8)) ? ANALYSIS : NONSENSE;
            reply(exchange, "{\"info\":{\"id\":\"msg\",\"role\":\"assistant\"},\"parts\":[{\"type\":\"text\",\"text\":\""
                + text.replace("\"", "\\\"") + "\"}]}");
            return;
        }
        reply(exchange, "true");
    }

    /**
     * The re-ask after an unreadable answer goes to the same session and need not repeat the failure
     * text, so {@link Mode#PARTIAL} remembers the session and keeps refusing in it.
     */
    private boolean answers(String messagePath, String prompt) {
        if (mode != Mode.PARTIAL) {
            return mode == Mode.ANSWERS;
        }
        if (prompt.contains(PARTIAL_UNANSWERED + ":")) {
            refusingSessions.add(messagePath);
        }
        return !refusingSessions.contains(messagePath);
    }

    private static void reply(HttpExchange exchange, String json) throws IOException {
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }
}
