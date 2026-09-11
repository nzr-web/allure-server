package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 */
final class OpenCodeStub implements AutoCloseable {

    enum Mode {ANSWERS, GARBAGE}

    private static final String ANALYSIS = "{\"causeClass\":\"product\",\"confidence\":0.8,"
        + "\"reason\":\"the service replied 500 to a valid request\","
        + "\"recommendation\":\"check the service log for this build\",\"bugDraft\":null}";
    private static final String NONSENSE = "I am afraid I cannot answer in JSON right now";

    private final HttpServer server;
    private final AtomicInteger sessions = new AtomicInteger();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.ANSWERS;

    private OpenCodeStub(HttpServer server) {
        this.server = server;
    }

    static OpenCodeStub start() {
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final OpenCodeStub stub = new OpenCodeStub(server);
            server.createContext("/config/providers",
                exchange -> reply(exchange, "{\"providers\":[{\"id\":\"litellm\",\"models\":{\"qwen3.8\":{}}}]}"));
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

    /** Forgets the recorded messages - the stub is shared by every test of its class. */
    void clear() {
        messages.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void session(HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        final byte[] body = exchange.getRequestBody().readAllBytes();
        if ("/session".equals(path)) {
            reply(exchange, "{\"id\":\"ses_" + sessions.incrementAndGet() + "\",\"title\":\"stub\"}");
            return;
        }
        if (path.endsWith("/message")) {
            new ObjectMapper().readTree(body); // the client must send a parseable prompt envelope
            messages.add(new String(body, StandardCharsets.UTF_8));
            final String text = mode == Mode.ANSWERS ? ANALYSIS : NONSENSE;
            reply(exchange, "{\"info\":{\"id\":\"msg\",\"role\":\"assistant\"},\"parts\":[{\"type\":\"text\",\"text\":\""
                + text.replace("\"", "\\\"") + "\"}]}");
            return;
        }
        reply(exchange, "true");
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
