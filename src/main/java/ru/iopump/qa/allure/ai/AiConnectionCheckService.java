package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Answers one question for the admin settings page: does the OpenCode instance the form points at
 * answer, and does it know the provider/model pair typed next to it.
 * <p>
 * Only {@code GET /config/providers} is called - the same endpoint the allure-ai core probes before
 * a run - so the check starts no session and costs the model nothing. Nothing is written anywhere:
 * the values come from the submitted form, so an admin can try an address out before saving it. The
 * only value the caller may take from the database is the password, and only for the address it was
 * stored with - see {@code AdminSettingsController}.
 * <p>
 * Both timeouts are short on purpose: this runs inside a browser request, and a wrong address must
 * come back as a red label in seconds rather than hold the page.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiConnectionCheckService {

    /** How long the connection to OpenCode may take to establish. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** How long the whole {@code GET /config/providers} may take. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final String PROVIDERS_PATH = "/config/providers";

    /** What {@code opencode serve} answers when it is started with a password and gets none. */
    private static final int UNAUTHORIZED = 401;

    /** The message of a 401, the server-side twin of the one the allure-ai core logs for a job. */
    static final String UNAUTHORIZED_MESSAGE =
        "OpenCode rejected the credentials (401): the server password is missing or wrong";

    /** The user {@code opencode serve} assumes when only a password is configured. */
    private static final String DEFAULT_USERNAME = "opencode";

    private final ObjectMapper objectMapper;

    /** Same check without credentials, for a server that asks for none. */
    public CheckResult check(String opencodeUrl, String provider, String model) {
        return check(opencodeUrl, provider, model, null, null);
    }

    /**
     * @param opencodeUrl base url of {@code opencode serve}, as typed in the form
     * @param provider    provider id to look for, may be {@code null}
     * @param model       model id to look for inside that provider, may be {@code null}
     * @param username    HTTP Basic user, {@code null} or empty means {@code opencode}
     * @param password    HTTP Basic password; without one the request carries no {@code Authorization}
     */
    public CheckResult check(String opencodeUrl, String provider, String model, String username, String password) {
        final URI uri = providersUri(opencodeUrl);
        if (uri == null) {
            return failure("Not a valid URL: '" + opencodeUrl + "'");
        }

        try (HttpClient client = newClient()) {
            final HttpRequest request = providersRequest(uri, basicAuthHeader(username, password));
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == UNAUTHORIZED) {
                // The one answer that is about the card and not about the network: the admin can fix
                // it in the field right above the button.
                return failure(UNAUTHORIZED_MESSAGE);
            }
            if (response.statusCode() != 200) {
                return failure("OpenCode answered " + response.statusCode() + " to GET " + uri);
            }
            return parse(response.body(), uri, provider, model);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure("Interrupted while calling " + uri);
        } catch (IOException | RuntimeException e) {
            log.warn("AI connection check of '{}' failed: {}", uri, e.toString());
            return failure("Cannot reach " + uri + ": " + e);
        }
    }

    private CheckResult parse(String body, URI uri, String provider, String model) {
        final JsonNode providers;
        try {
            providers = objectMapper.readTree(body).path("providers");
        } catch (IOException e) {
            return failure("OpenCode at " + uri + " answered with something that is not JSON");
        }
        if (!providers.isArray()) {
            return failure("OpenCode at " + uri + " answered without a 'providers' array");
        }
        final List<ProviderInfo> found = new ArrayList<>();
        boolean modelFound = false;
        for (JsonNode node : providers) {
            final String id = node.path("id").asText("");
            final List<String> models = modelIds(node.path("models"));
            found.add(new ProviderInfo(id, models.size()));
            if (id.equals(provider) && models.contains(model)) {
                modelFound = true;
            }
        }
        final String pair = provider + "/" + model;
        final String message = modelFound
            ? "OpenCode answered, model '" + pair + "' found"
            : "OpenCode answered, but model '" + pair + "' was NOT found";
        return new CheckResult(true, message, List.copyOf(found), modelFound);
    }

    private static List<String> modelIds(JsonNode models) {
        final List<String> ids = new ArrayList<>();
        if (models.isObject()) {
            final Iterator<Map.Entry<String, JsonNode>> fields = models.fields();
            while (fields.hasNext()) {
                ids.add(fields.next().getKey());
            }
        } else if (models.isArray()) {
            // Older opencode builds answer with a list of model objects instead of a map.
            models.forEach(model -> ids.add(model.path("id").asText("")));
        }
        return ids;
    }

    /**
     * The url of {@code GET <opencodeUrl>/config/providers}, or {@code null} when what was typed is
     * not an absolute http(s) url. The base is validated BEFORE the path is appended: appending
     * first turns {@code "http://"} into {@code "http://config/providers"}, a perfectly parseable
     * url with the host {@code config}, and the admin would be told the host is unreachable instead
     * of being told the address is incomplete.
     */
    @Nullable
    private static URI providersUri(String opencodeUrl) {
        final String base = opencodeUrl == null ? "" : opencodeUrl.trim();
        try {
            final URI parsed = new URI(base);
            if (parsed.getScheme() == null || parsed.getHost() == null || parsed.getHost().isEmpty()) {
                return null;
            }
            return new URI(trimTrailingSlash(base) + PROVIDERS_PATH);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** The client of the check; short connect timeout - this runs inside a browser request. */
    static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** The one request the check makes, with the timeout of the whole exchange on it. */
    static HttpRequest providersRequest(URI uri) {
        return providersRequest(uri, null);
    }

    /** The same request, with the {@code Authorization} header when there is a password to send. */
    static HttpRequest providersRequest(URI uri, @Nullable String authHeader) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET();
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return builder.build();
    }

    /**
     * {@code Basic ...} for the pair, or {@code null} when there is no password - the same rule the
     * allure-ai core follows, so what the button checks is what a job will send.
     */
    @Nullable
    static String basicAuthHeader(String username, String password) {
        if (password == null || password.isEmpty()) {
            return null;
        }
        final String user = username == null || username.isEmpty() ? DEFAULT_USERNAME : username;
        return "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String trimTrailingSlash(String url) {
        final String trimmed = url == null ? "" : url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static CheckResult failure(String message) {
        return new CheckResult(false, message, List.of(), false);
    }

    /**
     * @param ok         whether OpenCode answered at all
     * @param message    human-readable outcome, shown as is next to the card
     * @param providers  providers OpenCode reports, empty when it did not answer
     * @param modelFound whether the provider/model pair of the form is among them
     */
    public record CheckResult(boolean ok, String message, List<ProviderInfo> providers, boolean modelFound) {
    }

    /**
     * @param id     provider id as OpenCode reports it
     * @param models how many models it offers
     */
    public record ProviderInfo(String id, int models) {
    }
}
