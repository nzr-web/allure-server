package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.iopump.qa.allure.entity.SystemSettingsEntity;
import ru.iopump.qa.allure.entity.UserEntity;
import ru.iopump.qa.allure.repo.SystemSettingsRepository;
import ru.iopump.qa.allure.repo.UserRepository;
import ru.iopump.qa.allure.service.SystemSettingsService;
import ru.vtb.at.allureai.llm.PromptBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the runtime AI settings: the admin card writes the settings row, the row wins
 * over the {@code allure-ai.*} configuration, and the analysis really runs with what was typed
 * there - without a restart.
 * <p>
 * The configured OpenCode address is {@code http://127.0.0.1:1}, a port nothing listens on, while
 * the address of the {@link OpenCodeStub} is only ever put into the database. A worker that reads
 * the configuration instead of the settings snapshot therefore cannot pass: it would talk to a
 * closed port and fail the job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ai-settings-test-db;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.security.require-api-auth=false",
    "basic.auth.enable=false",
    "gg.jte.development-mode=false",
    "gg.jte.use-precompiled-templates=true",
    "allure.results-dir=build/ai-settings-it/results/",
    "allure.reports.dir=build/ai-settings-it/reports/",
    "allure-ai.cache-dir=build/ai-settings-it/cache",
    // The configuration points at a closed port on purpose - see the class javadoc.
    "allure-ai.opencode-url=http://127.0.0.1:1",
    "allure-ai.provider=litellm",
    "allure-ai.model=qwen3.8",
    "allure-ai.agent=allure-ai",
    "allure-ai.parallel=1",
    "allure-ai.timeout-seconds=30",
    "allure-ai.auto=false",
    "allure-ai.sweep-cron=-",
    // A password in the configuration, so that every password test proves the settings row wins:
    // the stub asks for the one stored in the database, and this one would earn a 401.
    "allure-ai.opencode-password=0d1f4f1a-3c19-4a4a-8f0a-1b3c5d7e9f00"
})
class AiSettingsIntegrationTest {

    private static final Path WORK_DIR = Paths.get("build", "ai-settings-it");
    private static final Path RESULTS_DIR = WORK_DIR.resolve("results");

    private static final String SETTINGS_PATH = "/app/admin/settings";
    private static final String AI_PATH = SETTINGS_PATH + "/ai";
    private static final String AI_RESET_PATH = AI_PATH + "/reset";
    private static final String AI_CHECK_PATH = AI_PATH + "/check";
    private static final String API_REPORT = "/api/report";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin";
    private static final String CLOSED_PORT_URL = "http://127.0.0.1:1";
    private static final String CONFIGURED_MODEL = "qwen3.8";
    private static final String CONFIGURED_PROVIDER = "litellm";
    private static final long AWAIT_TIMEOUT_MS = 120_000L;
    private static final String CUSTOM_SYSTEM_PROMPT =
        "You are a test. Answer {\"causeClass\":\"infra\",\"confidence\":0.5,\"reason\":\"stub\","
            + "\"recommendation\":\"stub\",\"bugDraft\":null}";
    private static final String PROJECT_NOTES = "Stand ift-2 is down";
    /** Passwords are UUIDs: nothing else on the settings page can look like one by accident. */
    private static final String YAML_PASSWORD = "0d1f4f1a-3c19-4a4a-8f0a-1b3c5d7e9f00";
    private static final String STORED_PASSWORD = "5a2e9c73-6b41-4f8d-9c2a-7e0b4d6f8a13";
    private static final String WRONG_PASSWORD = "c7b9e105-2d34-4e6f-8a1b-3c5d7e9f0a24";
    private static final String STORED_USERNAME = "bot";
    private static final String UNAUTHORIZED_TEXT =
        "OpenCode rejected the credentials (401): the server password is missing or wrong";
    /**
     * The heading the core puts above the project notes, escaped so this file stays ASCII: the
     * section name comes from the core and is Russian, like every prompt it builds.
     */
    private static final String NOTES_HEADING =
        "# \u0423\u043a\u0430\u0437\u0430\u043d\u0438\u044f \u043f\u0440\u043e\u0435\u043a\u0442\u0430";

    /** The stub address is never a property: it must reach the analysis through the settings row. */
    private static final OpenCodeStub STUB;

    static {
        FileUtils.deleteQuietly(WORK_DIR.toFile());
        STUB = OpenCodeStub.start();
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SystemSettingsRepository systemSettingsRepository;

    @Autowired
    private SystemSettingsService systemSettingsService;

    @Autowired
    private AiSettingsService aiSettingsService;

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AiProperties aiProperties;

    @BeforeEach
    void cleanSettings() {
        STUB.mode(OpenCodeStub.Mode.ANSWERS);
        STUB.requireNoPassword();
        STUB.clear();
        systemSettingsService.resetAiSettings("test-setup");
        systemSettingsService.updateRequireApiAuth(false, "test-setup");
        clearTemporaryPassword();
    }

    @Test
    @DisplayName("should store the filled fields and fall back to the configuration for the empty ones")
    void savesOverridesAndFallsBackForEmptyFields() throws Exception {
        // GIVEN - a card where only half of the fields are filled in

        // WHEN - the form is saved
        mockMvc.perform(post(AI_PATH).with(adminUser()).with(csrf())
                .param("enabled", "true")
                .param("opencodeUrl", "http://127.0.0.1:4096")
                .param("provider", "lmstudio")
                .param("model", "")
                .param("agent", "   ")
                .param("parallel", "4")
                .param("timeoutSeconds", "")
                .param("auto", ""))
            .andExpect(status().is3xxRedirection());

        // THEN - the filled fields are in the row and the empty ones are null there
        final SystemSettingsEntity row = settingsRow();
        assertThat(row.getAiEnabled()).as("enabled override").isTrue();
        assertThat(row.getAiOpencodeUrl()).as("opencodeUrl override").isEqualTo("http://127.0.0.1:4096");
        assertThat(row.getAiProvider()).as("provider override").isEqualTo("lmstudio");
        assertThat(row.getAiParallel()).as("parallel override").isEqualTo(4);
        assertThat(row.getAiModel()).as("empty text field must clear the override").isNull();
        assertThat(row.getAiAgent()).as("blank text field must clear the override").isNull();
        assertThat(row.getAiTimeoutSeconds()).as("empty number field must clear the override").isNull();
        assertThat(row.getAiAuto()).as("'Default' in the select must clear the override").isNull();

        // AND - the effective values name where each of them comes from
        final AiSettingsService.Effective effective = aiSettingsService.effective();
        assertThat(effective.provider())
            .as("a filled field is in force and comes from the settings")
            .isEqualTo(new AiSettingsService.Value<>("lmstudio", AiSettingsService.Source.SETTINGS));
        assertThat(effective.parallel())
            .as("numbers behave the same way")
            .isEqualTo(new AiSettingsService.Value<>(4, AiSettingsService.Source.SETTINGS));
        assertThat(effective.model())
            .as("a cleared field falls back to allure-ai.model")
            .isEqualTo(new AiSettingsService.Value<>(CONFIGURED_MODEL, AiSettingsService.Source.CONFIGURATION));
        assertThat(effective.timeoutSeconds())
            .as("a cleared number falls back to allure-ai.timeout-seconds")
            .isEqualTo(new AiSettingsService.Value<>(30L, AiSettingsService.Source.CONFIGURATION));
        assertThat(effective.auto())
            .as("'Default' in the select falls back to allure-ai.auto")
            .isEqualTo(new AiSettingsService.Value<>(false, AiSettingsService.Source.CONFIGURATION));
    }

    @Test
    @DisplayName("should clear every override when 'Reset to configuration' is pressed")
    void resetClearsEveryOverride() throws Exception {
        // GIVEN - a row with overrides
        // Map.ofEntries and not Map.of: twelve overrides are two pairs more than Map.of takes
        saveSettings(Map.ofEntries(
            Map.entry("enabled", "false"), Map.entry("opencodeUrl", "http://127.0.0.1:4096"),
            Map.entry("provider", "lmstudio"), Map.entry("model", "other"),
            Map.entry("agent", "other-agent"), Map.entry("parallel", "8"),
            Map.entry("timeoutSeconds", "600"), Map.entry("auto", "true"),
            Map.entry("systemPrompt", CUSTOM_SYSTEM_PROMPT), Map.entry("promptNotes", PROJECT_NOTES),
            Map.entry("opencodeUsername", STORED_USERNAME), Map.entry("opencodePassword", STORED_PASSWORD)));

        // WHEN - the reset button is pressed
        mockMvc.perform(post(AI_RESET_PATH).with(adminUser()).with(csrf()))
            .andExpect(status().is3xxRedirection());

        // THEN - not a single override is left in the row
        final SystemSettingsEntity row = settingsRow();
        assertThat(new Object[]{row.getAiEnabled(), row.getAiOpencodeUrl(), row.getAiProvider(),
            row.getAiModel(), row.getAiAgent(), row.getAiParallel(), row.getAiTimeoutSeconds(), row.getAiAuto(),
            row.getAiSystemPrompt(), row.getAiPromptNotes(),
            row.getAiOpencodeUsername(), row.getAiOpencodePassword()})
            .as("all twelve AI columns after a reset")
            .containsOnlyNulls();

        // AND - everything is back to the configuration
        final AiSettingsService.Effective effective = aiSettingsService.effective();
        assertThat(effective.opencodeUrl())
            .as("opencodeUrl after a reset")
            .isEqualTo(new AiSettingsService.Value<>(CLOSED_PORT_URL, AiSettingsService.Source.CONFIGURATION));
        assertThat(effective.enabled().source())
            .as("source of every setting after a reset")
            .isEqualTo(AiSettingsService.Source.CONFIGURATION);
    }

    @Test
    @DisplayName("should reject an invalid form with a flash toast and leave the row untouched")
    void rejectsInvalidFormAndKeepsTheRow() throws Exception {
        // GIVEN - a saved, valid state
        saveSettings(Map.of("provider", "lmstudio", "opencodeUrl", "http://127.0.0.1:4096"));

        // WHEN - a form with a schemeless url, parallel below the range and a too small timeout arrives
        final MvcResult result = mockMvc.perform(post(AI_PATH).with(adminUser()).with(csrf())
                .param("opencodeUrl", "127.0.0.1:4096")
                .param("parallel", "0")
                .param("timeoutSeconds", "5"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        // THEN - the page says the form was rejected
        final Map<?, ?> flash = (Map<?, ?>) result.getFlashMap().get("flash");
        assertThat(String.valueOf(flash.get("message")))
            .as("flash toast of a rejected form")
            .contains("Form rejected");

        // AND - the previously saved values are still there
        final SystemSettingsEntity row = settingsRow();
        assertThat(row.getAiProvider()).as("provider after a rejected form").isEqualTo("lmstudio");
        assertThat(row.getAiOpencodeUrl()).as("opencodeUrl after a rejected form").isEqualTo("http://127.0.0.1:4096");
        assertThat(row.getAiParallel()).as("parallel after a rejected form").isNull();
    }

    @Test
    @DisplayName("should answer 403 to a non-admin and to a request without a CSRF token")
    void protectsTheSettingsEndpoint() throws Exception {
        // WHEN - a plain user posts the form with a valid CSRF token
        // THEN - the class-level admin gate rejects it
        mockMvc.perform(post(AI_PATH).with(user("bob").roles("USER")).with(csrf())
                .param("provider", "lmstudio"))
            .andExpect(status().isForbidden());

        // WHEN - an admin posts it without a CSRF token
        // THEN - the browser-surface CSRF filter rejects it
        mockMvc.perform(post(AI_PATH).with(adminUser()).param("provider", "lmstudio"))
            .andExpect(status().isForbidden());

        // AND - neither attempt wrote anything
        assertThat(settingsRow().getAiProvider()).as("provider after two rejected attempts").isNull();
    }

    @Test
    @DisplayName("should run the model against the OpenCode address from the settings, not the configured one")
    void workerTakesTheOpenCodeAddressFromTheSettings() throws Exception {
        // GIVEN - the stub address lives in the settings row only
        saveSettings(Map.of("opencodeUrl", stubUrl()));
        final int sessionsBefore = STUB.sessionsStarted();

        // AND - a report generated with the analysis
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String reportUuid = generate("ai-settings", resultUuid);

        // WHEN - the second button starts the worker
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai"))
            .andExpect(status().isAccepted());

        // THEN - the job finishes and the stub is the one that was talked to
        assertThat(awaitFinished(reportUuid)).as("job started with the address from the settings").isEqualTo(AiJobStatus.DONE);
        assertThat(STUB.sessionsStarted())
            .as("POST /session calls the stub received while the job was running")
            .isGreaterThan(sessionsBefore);
    }

    @Test
    @DisplayName("should report the providers of a reachable OpenCode and store nothing")
    void checkConnectionReportsProviders() throws Exception {
        // WHEN - the check button is pressed with the stub address in the form
        final String html = mockMvc.perform(post(AI_CHECK_PATH).with(adminUser()).with(csrf())
                .param("opencodeUrl", stubUrl())
                .param("provider", CONFIGURED_PROVIDER)
                .param("model", CONFIGURED_MODEL))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - the page shows the providers and says the model is there
        assertThat(html).as("provider list on the page").contains(CONFIGURED_PROVIDER + "</span> (1 models)");
        assertThat(html).as("verdict on the page").contains(CONFIGURED_PROVIDER + "/" + CONFIGURED_MODEL + "' found");
        assertThat(html).as("colour of the successful label").contains("border-success/60 text-success");

        // AND - a check is not a save
        assertThat(settingsRow().getAiOpencodeUrl()).as("opencodeUrl in the row after a check").isNull();
    }

    @Test
    @DisplayName("should show a red label with the reason when OpenCode does not answer")
    void checkConnectionReportsAClosedPort() throws Exception {
        // WHEN - the check button is pressed against a port nothing listens on
        final String html = mockMvc.perform(post(AI_CHECK_PATH).with(adminUser()).with(csrf())
                .param("opencodeUrl", CLOSED_PORT_URL)
                .param("provider", CONFIGURED_PROVIDER)
                .param("model", CONFIGURED_MODEL))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - the failure is a label on the card, with the reason and in the error colour
        assertThat(html).as("failure text on the page").contains("Cannot reach " + CLOSED_PORT_URL);
        assertThat(html).as("colour of the failure label").contains("border-error/60 text-error");

        // AND - nothing was written
        assertThat(settingsRow().getAiOpencodeUrl()).as("opencodeUrl in the row after a failed check").isNull();
    }

    @Test
    @DisplayName("should keep the two cards apart: saving one must not reset the other")
    void theTwoCardsDoNotOverwriteEachOther() throws Exception {
        // GIVEN - saved AI settings
        saveSettings(Map.of("provider", "lmstudio", "parallel", "3", "promptNotes", PROJECT_NOTES));

        // WHEN - the API authentication toggle is saved afterwards
        mockMvc.perform(post(SETTINGS_PATH + "/require-api-auth").with(adminUser()).with(csrf())
                .param("requireApiAuth", "true"))
            .andExpect(status().is3xxRedirection());

        // THEN - the AI settings survived, in the row and in the cached snapshot
        assertThat(settingsRow().getAiProvider()).as("provider after the other card was saved").isEqualTo("lmstudio");
        assertThat(settingsRow().getAiPromptNotes())
            .as("project notes after the other card was saved")
            .isEqualTo(PROJECT_NOTES);
        assertThat(systemSettingsService.current().aiProvider())
            .as("provider in the cached snapshot after the other card was saved")
            .isEqualTo("lmstudio");
        assertThat(systemSettingsService.current().aiPromptNotes())
            .as("project notes in the cached snapshot after the other card was saved")
            .isEqualTo(PROJECT_NOTES);

        // WHEN - the AI card is saved again
        saveSettings(Map.of("provider", "lmstudio", "model", "other"));

        // THEN - the API authentication flag survived that, in the row and in the cached snapshot
        assertThat(settingsRow().isRequireApiAuth()).as("requireApiAuth after the AI card was saved").isTrue();
        assertThat(systemSettingsService.current().requireApiAuth())
            .as("requireApiAuth in the cached snapshot after the AI card was saved")
            .isTrue();
    }

    @Test
    @DisplayName("should answer 409 and hide the grid button, but keep the status badges, when the analysis is off")
    void disabledAnalysisIsRefusedAndInvisible() throws Exception {
        // GIVEN - one report waiting for the model and one that has nothing left to analyse
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 2, "alpha", "beta");
        final String reportUuid = generate("ai-settings-off", resultUuid);
        final String doneUuid = generate("ai-settings-done", AllureResultsFixture.write(RESULTS_DIR, 3));
        assertThat(aiAnalysisService.status(doneUuid).getStatus())
            .as("a run without failures is done before the model is ever asked")
            .isEqualTo(AiJobStatus.DONE);
        assertThat(grid()).as("grid while the analysis is enabled").contains(analysisFormAction(reportUuid));

        // WHEN - an admin switches the analysis off
        saveSettings(Map.of("enabled", "false"));

        // THEN - the API refuses to start the model over that report
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai"))
            .andExpect(status().isConflict());

        // AND - the grid no longer offers the button that would call it, for any row
        final String grid = grid();
        assertThat(grid).as("grid while the analysis is disabled").doesNotContain(analysisFormAction(reportUuid));
        assertThat(grid).as("no row may offer the analysis button while it is disabled").doesNotContain("/ai\"");

        // AND - what the reports already carry is still shown: switching the analysis off does not
        // erase the analysis that has been done
        assertThat(grid).as("status badge of an analysed report while the analysis is disabled")
            .contains(">AI done</span>");
    }

    @Test
    @DisplayName("should start the worker by itself when 'auto' is on in the settings and off in the configuration")
    void autoFromTheSettingsStartsTheWorker() throws Exception {
        // GIVEN - allure-ai.auto=false in the configuration, 'On' in the admin card, stub in the row
        saveSettings(Map.of("opencodeUrl", stubUrl(), "auto", "true"));
        assertThat(aiSettingsService.effective().auto())
            .as("'auto' in force while the configuration says false")
            .isEqualTo(new AiSettingsService.Value<>(true, AiSettingsService.Source.SETTINGS));
        final int sessionsBefore = STUB.sessionsStarted();

        // WHEN - a report is generated with the analysis and nobody presses the button
        final String reportUuid = generate("ai-settings-auto", AllureResultsFixture.write(RESULTS_DIR, 4, "alpha"));

        // THEN - the job was queued inside the generation request already
        assertThat(aiAnalysisService.status(reportUuid).getStatus())
            .as("job status right after a generation with 'auto' on in the settings")
            .isNotEqualTo(AiJobStatus.PENDING);

        // AND - it ran to the end against the OpenCode of the settings
        assertThat(awaitFinished(reportUuid)).as("unattended job started from the settings").isEqualTo(AiJobStatus.DONE);
        assertThat(STUB.sessionsStarted())
            .as("POST /session calls the stub received without anyone asking for the analysis")
            .isGreaterThan(sessionsBefore);
    }

    @Test
    @DisplayName("should send the saved system prompt and project notes to the model")
    void savedPromptsReachTheModel() throws Exception {
        // GIVEN - both prompts saved in the card, with the stub as the OpenCode of the settings
        saveSettings(Map.of("opencodeUrl", stubUrl(),
            "systemPrompt", CUSTOM_SYSTEM_PROMPT,
            "promptNotes", PROJECT_NOTES));

        // AND - a report generated with the analysis
        final String reportUuid = generate("ai-settings-prompts",
            AllureResultsFixture.write(RESULTS_DIR, 5, "alpha"));

        // WHEN - the second button starts the worker
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai"))
            .andExpect(status().isAccepted());
        assertThat(awaitFinished(reportUuid))
            .as("job run with the prompts from the settings")
            .isEqualTo(AiJobStatus.DONE);

        // THEN - every message the model got carried the saved texts: the system prompt as 'system',
        // the project notes as a section of the cluster prompt
        final List<JsonNode> messages = stubMessages();
        assertThat(messages).as("messages the stub received while the job was running").isNotEmpty();
        assertThat(messages).allSatisfy(message -> {
            assertThat(message.path("system").asText())
                .as("'system' of the message sent to the model")
                .isEqualTo(CUSTOM_SYSTEM_PROMPT);
            assertThat(message.path("parts").path(0).path("text").asText())
                .as("cluster prompt sent to the model")
                .contains(NOTES_HEADING)
                .contains(PROJECT_NOTES);
        });
    }

    @Test
    @DisplayName("should fall back to the built-in prompt and to no notes at all when both textareas are empty")
    void emptyPromptsFallBackToTheBuiltInText() throws Exception {
        // GIVEN - both prompts overridden
        saveSettings(Map.of("systemPrompt", CUSTOM_SYSTEM_PROMPT, "promptNotes", PROJECT_NOTES));

        // WHEN - the card is saved again with both textareas cleared
        saveSettings(Map.of("systemPrompt", "", "promptNotes", "   "));

        // THEN - nothing is left in the row
        final SystemSettingsEntity row = settingsRow();
        assertThat(row.getAiSystemPrompt()).as("system prompt after an empty textarea").isNull();
        assertThat(row.getAiPromptNotes()).as("project notes after a blank textarea").isNull();

        // AND - what is in force is the text the core carries itself, with no project notes at all
        final AiSettingsService.Effective effective = aiSettingsService.effective();
        assertThat(effective.systemPrompt())
            .as("system prompt in force with an empty override")
            .isEqualTo(new AiSettingsService.Value<>(PromptBuilder.SYSTEM, AiSettingsService.Source.BUILT_IN));
        assertThat(effective.promptNotes())
            .as("project notes in force with an empty override")
            .isEqualTo(new AiSettingsService.Value<String>(null, AiSettingsService.Source.BUILT_IN));
    }

    @Test
    @DisplayName("should reject a system prompt longer than the column and keep the stored one")
    void rejectsASystemPromptLongerThanTheColumn() throws Exception {
        // GIVEN - a saved prompt
        saveSettings(Map.of("systemPrompt", CUSTOM_SYSTEM_PROMPT));

        // WHEN - a prompt one character longer than the column arrives
        final MvcResult result = mockMvc.perform(post(AI_PATH).with(adminUser()).with(csrf())
                .param("systemPrompt", "x".repeat(16001)))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        // THEN - the form is rejected before the database is touched
        final Map<?, ?> flash = (Map<?, ?>) result.getFlashMap().get("flash");
        assertThat(String.valueOf(flash.get("message")))
            .as("flash toast of a too long system prompt")
            .contains("Form rejected");

        // AND - the prompt saved before is still the one stored
        assertThat(settingsRow().getAiSystemPrompt())
            .as("system prompt after a rejected form")
            .isEqualTo(CUSTOM_SYSTEM_PROMPT);
    }

    @Test
    @DisplayName("should reject project notes longer than the column and keep the stored ones")
    void rejectsProjectNotesLongerThanTheColumn() throws Exception {
        // GIVEN - saved notes
        saveSettings(Map.of("promptNotes", PROJECT_NOTES));

        // WHEN - notes one character longer than the column arrive
        final MvcResult result = mockMvc.perform(post(AI_PATH).with(adminUser()).with(csrf())
                .param("promptNotes", "y".repeat(4001)))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        // THEN - the form is rejected before the database is touched
        final Map<?, ?> flash = (Map<?, ?>) result.getFlashMap().get("flash");
        assertThat(String.valueOf(flash.get("message")))
            .as("flash toast of too long project notes")
            .contains("Form rejected");

        // AND - the notes saved before are still the ones stored
        assertThat(settingsRow().getAiPromptNotes())
            .as("project notes after a rejected form")
            .isEqualTo(PROJECT_NOTES);
    }

    @Test
    @DisplayName("should send the stored OpenCode credentials to the model, not the configured ones")
    void storedCredentialsReachOpenCode() throws Exception {
        // GIVEN - an OpenCode that answers only to the pair stored in the settings row, while the
        // configuration carries a different password
        STUB.requirePassword(STORED_USERNAME, STORED_PASSWORD);
        saveSettings(Map.of("opencodeUrl", stubUrl(),
            "opencodeUsername", STORED_USERNAME,
            "opencodePassword", STORED_PASSWORD));

        // AND - a report generated with the analysis
        final String reportUuid = generate("ai-settings-password",
            AllureResultsFixture.write(RESULTS_DIR, 6, "alpha"));

        // WHEN - the second button starts the worker
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai"))
            .andExpect(status().isAccepted());

        // THEN - the job ran through: every request carried the stored pair, so nothing got a 401
        assertThat(awaitFinished(reportUuid))
            .as("job run against an OpenCode that demands the stored password")
            .isEqualTo(AiJobStatus.DONE);
        assertThat(STUB.authHeaders())
            .as("Authorization headers the stub received while the job was running")
            .isNotEmpty()
            .allSatisfy(header -> assertThat(header).isEqualTo(basicHeader(STORED_USERNAME, STORED_PASSWORD)));
        assertThat(STUB.authHeaders())
            .as("the configured password must not be used while one is stored")
            .doesNotContain(basicHeader(STORED_USERNAME, YAML_PASSWORD));
    }

    @Test
    @DisplayName("should keep the stored password on an empty box and drop it on the checkbox")
    void emptyPasswordBoxKeepsTheStoredOneAndTheCheckboxClearsIt() throws Exception {
        // GIVEN - a stored password
        saveSettings(Map.of("opencodePassword", STORED_PASSWORD));
        assertThat(settingsRow().getAiOpencodePassword()).as("password after it was saved").isEqualTo(STORED_PASSWORD);

        // WHEN - the card is saved again with the password box empty, as it is always rendered
        saveSettings(Map.of("provider", "lmstudio", "opencodePassword", ""));

        // THEN - the stored password is still there: an empty box is not a request to clear it
        assertThat(settingsRow().getAiOpencodePassword())
            .as("password after a save with an empty box")
            .isEqualTo(STORED_PASSWORD);
        assertThat(settingsRow().getAiProvider()).as("the rest of the card was saved").isEqualTo("lmstudio");

        // WHEN - the clearing checkbox is ticked, with a password typed into the box as well
        saveSettings(Map.of("opencodePassword", WRONG_PASSWORD, "clearOpencodePassword", "on"));

        // THEN - the checkbox wins: nothing is stored
        assertThat(settingsRow().getAiOpencodePassword())
            .as("password after the clearing checkbox won over a typed one")
            .isNull();
        assertThat(aiSettingsService.effective().opencodePassword())
            .as("password in force once nothing is stored")
            .isEqualTo(new AiSettingsService.Value<>(YAML_PASSWORD, AiSettingsService.Source.CONFIGURATION));
    }

    @Test
    @DisplayName("should store a password with the spaces around it, they are part of it")
    void storesThePasswordWithItsSpaces() throws Exception {
        // GIVEN - a password that begins and ends with a space
        final String padded = " pa ss ";

        // WHEN - it is saved
        saveSettings(Map.of("opencodePassword", padded));

        // THEN - it is stored exactly as typed, unlike every other text field of this card
        assertThat(settingsRow().getAiOpencodePassword()).as("stored password with its spaces").isEqualTo(padded);
    }

    @Test
    @DisplayName("should never render the stored password on the settings page")
    void theSettingsPageNeverRendersTheStoredPassword() throws Exception {
        // GIVEN - a stored password
        saveSettings(Map.of("opencodeUsername", STORED_USERNAME, "opencodePassword", STORED_PASSWORD));

        // WHEN - the settings page is opened
        final String html = mockMvc.perform(get(SETTINGS_PATH).with(adminUser()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - the page says there is a password, and does not carry it
        assertThat(html).as("state of the stored password on the page")
            .contains("set (" + STORED_PASSWORD.length() + " characters)");
        assertThat(html).as("stored password on the page").doesNotContain(STORED_PASSWORD);
        assertThat(html).as("configured password on the page").doesNotContain(YAML_PASSWORD);
        assertThat(html).as("the username is no secret").contains("value=\"" + STORED_USERNAME + "\"");
    }

    @Test
    @DisplayName("should keep the password out of the properties and out of the snapshot text")
    void neitherThePropertiesNorTheSnapshotPrintThePassword() throws Exception {
        // GIVEN - a stored password on top of the configured one
        saveSettings(Map.of("opencodePassword", STORED_PASSWORD));

        // WHEN - the two objects that end up in log lines are printed
        final String properties = aiProperties.toString();
        final String snapshot = systemSettingsService.current().toString();

        // THEN - neither carries a password, and both still carry what a log line is read for
        assertThat(properties).as("configured password in the properties line").doesNotContain(YAML_PASSWORD);
        assertThat(properties).as("the rest of the properties line").contains(CLOSED_PORT_URL);
        assertThat(snapshot).as("stored password in the snapshot line").doesNotContain(STORED_PASSWORD);
        assertThat(snapshot).as("state of the password in the snapshot line")
            .contains("aiOpencodePassword=set (" + STORED_PASSWORD.length() + " chars)");
    }

    @Test
    @DisplayName("should report a 401 from OpenCode as a wrong password and accept the right one")
    void checkConnectionTellsAWrongPasswordFromARightOne() throws Exception {
        // GIVEN - an OpenCode that demands a password, with the default user name
        STUB.requirePassword(null, STORED_PASSWORD);

        // WHEN - the check button is pressed with the wrong password in the box
        final String wrong = mockMvc.perform(post(AI_CHECK_PATH).with(adminUser()).with(csrf())
                .param("opencodeUrl", stubUrl())
                .param("provider", CONFIGURED_PROVIDER)
                .param("model", CONFIGURED_MODEL)
                .param("opencodePassword", WRONG_PASSWORD))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - the card says the credentials were rejected, in the failure colour
        assertThat(wrong).as("verdict of a check with a wrong password").contains(UNAUTHORIZED_TEXT);
        assertThat(wrong).as("colour of the rejected check").contains("border-error/60 text-error");

        // WHEN - the check is repeated with the right password
        final String right = mockMvc.perform(post(AI_CHECK_PATH).with(adminUser()).with(csrf())
                .param("opencodeUrl", stubUrl())
                .param("provider", CONFIGURED_PROVIDER)
                .param("model", CONFIGURED_MODEL)
                .param("opencodePassword", STORED_PASSWORD))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - the check goes through and the label is the successful one
        assertThat(right).as("verdict of a check with the right password")
            .contains(CONFIGURED_PROVIDER + "/" + CONFIGURED_MODEL + "' found");
        assertThat(right).as("colour of the successful check").contains("border-success/60 text-success");

        // AND - the typed password is back in the box, with the warning that nothing is saved yet:
        // the one answer of this surface that carries a password, so the button can be pressed twice
        assertThat(right).as("typed password in the box after a check")
            .contains("value=\"" + STORED_PASSWORD + "\"");
        assertThat(right).as("warning under the result of a check").contains("Password is not saved yet");

        // AND - a check is still not a save
        assertThat(settingsRow().getAiOpencodePassword()).as("password in the row after two checks").isNull();
    }

    @Test
    @DisplayName("should use the stored password for its own address and send it nowhere else")
    void checkSendsTheStoredPasswordOnlyToTheAddressItBelongsTo() throws Exception {
        // GIVEN - the stub address and its password in the settings row
        STUB.requirePassword(null, STORED_PASSWORD);
        saveSettings(Map.of("opencodeUrl", stubUrl(), "opencodePassword", STORED_PASSWORD));

        // WHEN - the check button is pressed with both boxes left empty
        final String own = mockMvc.perform(post(AI_CHECK_PATH).with(adminUser()).with(csrf())
                .param("opencodeUrl", "")
                .param("provider", CONFIGURED_PROVIDER)
                .param("model", CONFIGURED_MODEL)
                .param("opencodePassword", ""))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - the stored password was used for the address it was stored with
        assertThat(own).as("verdict of a check that fell back to the stored password")
            .contains(CONFIGURED_PROVIDER + "/" + CONFIGURED_MODEL + "' found");
        assertThat(STUB.authHeaders())
            .as("credentials the stub received from the check of its own address")
            .contains(basicHeader(null, STORED_PASSWORD));

        // WHEN - the same empty box is checked against another address typed into the URL field
        // (the very same server under another name, so the request is observable at all)
        STUB.clear();
        final String foreign = mockMvc.perform(post(AI_CHECK_PATH).with(adminUser()).with(csrf())
                .param("opencodeUrl", "http://localhost:" + STUB.port())
                .param("provider", CONFIGURED_PROVIDER)
                .param("model", CONFIGURED_MODEL)
                .param("opencodePassword", ""))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - nothing was sent: the stored password does not travel to an address nobody stored
        assertThat(STUB.authHeaders())
            .as("credentials the stub received from a check of an address that is not the stored one")
            .containsExactly("none");
        assertThat(foreign).as("verdict of a check without credentials").contains(UNAUTHORIZED_TEXT);
    }

    //// PRIVATE ////

    /** What the stub was sent, parsed: a contains() over the raw JSON would pass on the wrong field. */
    private List<JsonNode> stubMessages() throws Exception {
        final List<JsonNode> parsed = new ArrayList<>();
        for (String body : STUB.messages()) {
            parsed.add(objectMapper.readTree(body));
        }
        return parsed;
    }

    /** The header an {@code opencode serve} with a password expects; an empty user means 'opencode'. */
    private static String basicHeader(String username, String password) {
        final String user = username == null || username.isEmpty() ? "opencode" : username;
        return "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String stubUrl() {
        return "http://127.0.0.1:" + STUB.port();
    }

    private static String analysisFormAction(String reportUuid) {
        return "action=\"/app/reports/" + reportUuid + "/ai\"";
    }

    private SystemSettingsEntity settingsRow() {
        return systemSettingsRepository.findById(SystemSettingsEntity.SINGLETON_ID).orElseThrow();
    }

    /** Posts the AI card with the given fields; everything else is left empty, i.e. reset. */
    private void saveSettings(Map<String, String> fields) throws Exception {
        var request = post(AI_PATH).with(adminUser()).with(csrf());
        fields.forEach(request::param);
        mockMvc.perform(request).andExpect(status().is3xxRedirection());
    }

    private String grid() throws Exception {
        return mockMvc.perform(get("/app/reports").header(HttpHeaders.AUTHORIZATION, basicAuthHeader()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    /** {@code POST /api/report} with the analysis requested; returns the uuid of the created report. */
    private String generate(String path, String resultUuid) throws Exception {
        final String body = """
            {"reportSpec":{"path":["ai","%s"],"executorInfo":{"buildName":"nightly"}},
             "results":["%s"],"deleteResults":true,"singleFile":false,"aiAnalysis":true}
            """.formatted(path, resultUuid);
        final String response = mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("uuid").asText();
    }

    private AiJobStatus awaitFinished(String uuid) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        AiJobStatus status = aiAnalysisService.status(uuid).getStatus();
        while (System.currentTimeMillis() < deadline && inFlight(status)) {
            Thread.sleep(50L);
            status = aiAnalysisService.status(uuid).getStatus();
        }
        return status;
    }

    private static boolean inFlight(AiJobStatus status) {
        return status == AiJobStatus.PENDING || status == AiJobStatus.QUEUED || status == AiJobStatus.RUNNING;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminUser() {
        return user(ADMIN_USER).roles("ADMIN");
    }

    private void clearTemporaryPassword() {
        final UserEntity user = userRepository.findByUsername(ADMIN_USER).orElseThrow();
        user.setPasswordTemporary(false);
        userRepository.save(user);
    }

    private static String basicAuthHeader() {
        return "Basic " + Base64.getEncoder()
            .encodeToString((ADMIN_USER + ":" + ADMIN_PASS).getBytes(StandardCharsets.UTF_8));
    }
}
