package ru.iopump.qa.allure.web;

import gg.jte.springframework.boot.autoconfigure.JteAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.iopump.qa.allure.config.RedirectConfiguration;
import ru.iopump.qa.allure.config.WebConfiguration;
import ru.iopump.qa.allure.entity.UserEntity;
import ru.iopump.qa.allure.entity.UserRole;
import ru.iopump.qa.allure.ai.AiConnectionCheckService;
import ru.iopump.qa.allure.ai.AiSettingsService;
import ru.iopump.qa.allure.properties.AllureProperties;
import ru.iopump.qa.allure.properties.BasicProperties;
import ru.iopump.qa.allure.security.CurrentUserProvider;
import ru.iopump.qa.allure.service.ApiTokenService;
import ru.iopump.qa.allure.service.SystemSettingsService;
import ru.iopump.qa.allure.web.dto.AiSettingsForm;
import ru.vtb.at.allureai.llm.PromptBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} slice for {@link AdminSettingsController}. Security is excluded so the
 * slice focuses on HTTP behaviour: the GET render, the {@code requireApiAuth} toggle including
 * the absent-checkbox → {@code false} default binding, and the flash wiring. The class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} admin gate is verified by a dedicated annotation
 * test (mirroring {@code AdminUsersControllerTest}) to avoid the CGLIB-proxy / MVC-mapping
 * conflict {@code @EnableMethodSecurity} introduces in a slice.
 */
@WebMvcTest(
    value = AdminSettingsController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class}
)
@Import({WebExceptionAdvice.class, WebConfiguration.class, RedirectConfiguration.class,
    JteAutoConfiguration.class, GlobalModelAdvice.class})
@EnableConfigurationProperties({AllureProperties.class, BasicProperties.class})
class AdminSettingsControllerTest {

    private static final String SETTINGS_PATH = "/app/admin/settings";
    private static final String TOGGLE_PATH = "/app/admin/settings/require-api-auth";
    private static final String AI_PATH = "/app/admin/settings/ai";
    private static final String CONFIGURED_MODEL = "qwen3.8";
    private static final int CONFIGURED_PARALLEL = 2;
    private static final String FLASH_KEY = "flash";
    private static final String FLASH_LEVEL_KEY = "level";
    private static final String FLASH_MESSAGE_KEY = "message";
    private static final String LEVEL_SUCCESS = "success";
    private static final String ADMIN_USERNAME = "admin";
    private static final String SAVED_PROMPT = "You are a test. Answer with one JSON object.";
    private static final String SAVED_PASSWORD = "7f3c9a21-secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemSettingsService systemSettingsService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ApiTokenService apiTokenService;

    @MockitoBean
    private AiSettingsService aiSettingsService;

    @MockitoBean
    private AiConnectionCheckService aiConnectionCheckService;

    private UserEntity adminActor;

    @BeforeEach
    void setUp() {
        adminActor = UserEntity.builder()
            .id(UUID.randomUUID())
            .username(ADMIN_USERNAME)
            .displayName("Admin")
            .role(UserRole.ADMIN)
            .createdAt(Instant.now())
            .build();
        when(currentUserProvider.current()).thenReturn(adminActor);
        when(aiSettingsService.effective()).thenReturn(configuredEffective());
    }

    @Test
    @DisplayName("should carry @PreAuthorize('hasRole(ADMIN)') at class level so non-admins are rejected with 403")
    void controller_requiresAdminRole() {
        // GIVEN — the controller class
        final PreAuthorize annotation = AdminSettingsController.class.getAnnotation(PreAuthorize.class);

        // THEN — the ADMIN gate is present
        assertThat(annotation)
            .as("AdminSettingsController must be annotated with @PreAuthorize at class level")
            .isNotNull();
        assertThat(annotation.value())
            .as("@PreAuthorize expression must enforce the ADMIN role")
            .isEqualTo("hasRole('ADMIN')");
    }

    @Test
    @DisplayName("should render the settings page with 200 when GET /app/admin/settings")
    void index_rendersSettingsPage() throws Exception {
        // GIVEN — a current settings snapshot
        when(systemSettingsService.current())
            .thenReturn(new SystemSettingsService.Snapshot(false, Instant.now(), ADMIN_USERNAME));

        // WHEN — GET the settings page
        MvcResult result = mockMvc.perform(get(SETTINGS_PATH))
            .andExpect(status().isOk())
            .andReturn();

        // THEN — the page renders the system-settings heading
        final String body = result.getResponse().getContentAsString();
        assertThat(body)
            .as("settings page must contain the System settings heading")
            .contains("System settings");
        verify(systemSettingsService).current();
    }

    @Test
    @DisplayName("should enable API auth and flash success when POST /require-api-auth with requireApiAuth=true")
    void toggle_enable_persistsTrueAndFlashesSuccess() throws Exception {
        // GIVEN — admin actor (from setUp)
        when(systemSettingsService.updateRequireApiAuth(true, ADMIN_USERNAME))
            .thenReturn(new SystemSettingsService.Snapshot(true, Instant.now(), ADMIN_USERNAME));

        // WHEN — POST the toggle with the checkbox checked
        MvcResult result = mockMvc.perform(post(TOGGLE_PATH)
                .param("requireApiAuth", "true"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(SETTINGS_PATH))
            .andReturn();

        // THEN — service persists true and success flash explains the new REQUIRED state
        verify(systemSettingsService).updateRequireApiAuth(true, ADMIN_USERNAME);
        final Map<?, ?> flash = extractFlashMap(result);
        assertThat(flash.get(FLASH_LEVEL_KEY))
            .as("enabling API auth must produce a 'success' flash level")
            .isEqualTo(LEVEL_SUCCESS);
        assertThat(flash.get(FLASH_MESSAGE_KEY))
            .as("success message must state API auth is now REQUIRED")
            .asString()
            .contains("REQUIRED");
    }

    @Test
    @DisplayName("should default requireApiAuth to false and flash success when POST /require-api-auth without the checkbox param")
    void toggle_absentCheckbox_defaultsToFalse() throws Exception {
        // GIVEN — admin actor; the unchecked HTML checkbox sends no requireApiAuth param,
        // so the controller binds defaultValue="false".
        when(systemSettingsService.updateRequireApiAuth(false, ADMIN_USERNAME))
            .thenReturn(new SystemSettingsService.Snapshot(false, Instant.now(), ADMIN_USERNAME));

        // WHEN — POST the toggle with no requireApiAuth param at all
        MvcResult result = mockMvc.perform(post(TOGGLE_PATH))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(SETTINGS_PATH))
            .andReturn();

        // THEN — service persists false (the absent-checkbox default) with an OPTIONAL message
        verify(systemSettingsService).updateRequireApiAuth(false, ADMIN_USERNAME);
        final Map<?, ?> flash = extractFlashMap(result);
        assertThat(flash.get(FLASH_LEVEL_KEY))
            .as("disabling API auth must produce a 'success' flash level")
            .isEqualTo(LEVEL_SUCCESS);
        assertThat(flash.get(FLASH_MESSAGE_KEY))
            .as("success message must state API auth is now OPTIONAL")
            .asString()
            .contains("OPTIONAL");
    }

    @Test
    @DisplayName("should render the AI analysis card with the effective values when GET /app/admin/settings")
    void index_rendersAiCard() throws Exception {
        // GIVEN - a settings row with no AI override, so every AI value comes from the configuration
        when(systemSettingsService.current())
            .thenReturn(new SystemSettingsService.Snapshot(false, Instant.now(), ADMIN_USERNAME));

        // WHEN - GET the settings page
        MvcResult result = mockMvc.perform(get(SETTINGS_PATH))
            .andExpect(status().isOk())
            .andReturn();

        // THEN - the card is there with the effective model and the origin of that value
        final String body = result.getResponse().getContentAsString();
        assertThat(body).as("AI card heading").contains("AI analysis");
        assertThat(body).as("form posting to the save endpoint").contains("action=\"" + AI_PATH + "\"");
        assertThat(body).as("effective model on the card").contains(CONFIGURED_MODEL);
        assertThat(body).as("origin of an unset field").contains("CONFIGURATION");
    }

    @Test
    @DisplayName("should bind blank text fields to null when POST /app/admin/settings/ai")
    void updateAiSettings_bindsBlankFieldsToNull() throws Exception {
        // GIVEN - a form where only the provider is filled in; the rest is empty or whitespace
        // WHEN - the card is saved
        mockMvc.perform(post(AI_PATH)
                .param("enabled", "")
                .param("opencodeUrl", "  http://127.0.0.1:4096  ")
                .param("provider", "lmstudio")
                .param("model", "")
                .param("agent", "   ")
                .param("parallel", "")
                .param("timeoutSeconds", "")
                .param("auto", "")
                .param("systemPrompt", "")
                .param("promptNotes", "   "))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(SETTINGS_PATH));

        // THEN - the service is handed nulls for them, and a trimmed value for the url
        verify(systemSettingsService).updateAiSettings(
            eq(new AiSettingsForm(null, "http://127.0.0.1:4096", "lmstudio", null, null, null, null, null,
                null, null, null, null, null)),
            eq(ADMIN_USERNAME));
    }

    @Test
    @DisplayName("should render both prompt textareas and the built-in prompt when GET /app/admin/settings")
    void index_rendersThePromptEditors() throws Exception {
        // GIVEN - a settings row with no prompt override
        when(systemSettingsService.current())
            .thenReturn(new SystemSettingsService.Snapshot(false, Instant.now(), ADMIN_USERNAME));

        // WHEN - GET the settings page
        MvcResult result = mockMvc.perform(get(SETTINGS_PATH))
            .andExpect(status().isOk())
            .andReturn();

        // THEN - both textareas are on the page, inside the form that saves the card
        final String body = result.getResponse().getContentAsString();
        assertThat(body).as("form posting to the save endpoint").contains("action=\"" + AI_PATH + "\"");
        assertThat(body).as("system prompt editor").contains("<textarea name=\"systemPrompt\"");
        assertThat(body).as("project notes editor").contains("<textarea name=\"promptNotes\"");

        // AND - the built-in prompt is there to be copied, not only its heading
        assertThat(body).as("heading of the built-in prompt block").contains("Built-in prompt");
        assertThat(body).as("text of the built-in prompt on the page").contains("causeClass");
        assertThat(body).as("origin badge of a prompt nobody overrode").contains("BUILT-IN");
    }

    @Test
    @DisplayName("should put a saved prompt into the textarea and its length into the 'In force' line")
    void index_rendersASavedPromptOverride() throws Exception {
        // GIVEN - a settings row where the system prompt is overridden
        when(systemSettingsService.current()).thenReturn(new SystemSettingsService.Snapshot(
            false, Instant.now(), ADMIN_USERNAME,
            null, null, null, null, null, null, null, null, SAVED_PROMPT, null, null, null));
        when(aiSettingsService.effective()).thenReturn(effectiveWithSavedSystemPrompt());

        // WHEN - GET the settings page
        MvcResult result = mockMvc.perform(get(SETTINGS_PATH))
            .andExpect(status().isOk())
            .andReturn();

        // THEN - the saved text is what the textarea offers for editing
        final String body = result.getResponse().getContentAsString();
        assertThat(body).as("saved prompt inside the textarea").contains(SAVED_PROMPT + "</textarea>");

        // AND - the line under it counts the characters in force and names the settings as their origin
        assertThat(body).as("'In force' line of an overridden prompt")
            .contains("In force: <span class=\"font-mono text-text\">" + SAVED_PROMPT.length() + " characters</span>");
        assertThat(body).as("origin badge of an overridden prompt").contains(">SETTINGS<");
    }

    @Test
    @DisplayName("should bind the OpenCode credentials, keeping the spaces of the password, when POST /app/admin/settings/ai")
    void updateAiSettings_bindsTheOpenCodeCredentials() throws Exception {
        // GIVEN - a password with a space at either end and the clearing checkbox ticked, as a
        // browser sends it: an unchecked box sends nothing at all, a checked one sends "on"
        // WHEN - the card is saved
        mockMvc.perform(post(AI_PATH)
                .param("opencodeUsername", "  bot  ")
                .param("opencodePassword", " pa ss ")
                .param("clearOpencodePassword", "on"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl(SETTINGS_PATH));

        // THEN - the username is trimmed like every other field, the password is not touched at all,
        // and the checkbox arrives as TRUE
        verify(systemSettingsService).updateAiSettings(
            eq(new AiSettingsForm(null, null, null, null, null, null, null, null, null, null,
                "bot", " pa ss ", Boolean.TRUE)),
            eq(ADMIN_USERNAME));
    }

    @Test
    @DisplayName("should bind an empty password box to null so the stored password survives a save")
    void updateAiSettings_bindsAnEmptyPasswordBoxToNull() throws Exception {
        // GIVEN - the card as it is rendered: the password box is empty and the box is not ticked
        // WHEN - the card is saved
        mockMvc.perform(post(AI_PATH)
                .param("provider", "lmstudio")
                .param("opencodeUsername", "")
                .param("opencodePassword", ""))
            .andExpect(status().is3xxRedirection());

        // THEN - both credentials arrive as null, and nothing asks for the password to be cleared
        verify(systemSettingsService).updateAiSettings(
            eq(new AiSettingsForm(null, null, "lmstudio", null, null, null, null, null, null, null,
                null, null, null)),
            eq(ADMIN_USERNAME));
    }

    @Test
    @DisplayName("should render the credential fields and the state of the password, never the password")
    void index_rendersTheCredentialFieldsWithoutThePassword() throws Exception {
        // GIVEN - a settings row where both OpenCode credentials are stored
        when(systemSettingsService.current()).thenReturn(new SystemSettingsService.Snapshot(
            false, Instant.now(), ADMIN_USERNAME,
            null, null, null, null, null, null, null, null, null, null, "bot", SAVED_PASSWORD));
        when(aiSettingsService.effective()).thenReturn(effectiveWithSavedPassword());

        // WHEN - GET the settings page
        MvcResult result = mockMvc.perform(get(SETTINGS_PATH))
            .andExpect(status().isOk())
            .andReturn();

        // THEN - both fields sit inside the form that saves the card, the password one masked
        final String body = result.getResponse().getContentAsString();
        assertThat(body).as("form posting to the save endpoint").contains("action=\"" + AI_PATH + "\"");
        assertThat(body).as("username field").contains("name=\"opencodeUsername\"");
        assertThat(body).as("masked password field").contains("type=\"password\" name=\"opencodePassword\"");
        assertThat(body).as("checkbox that clears the stored password").contains("name=\"clearOpencodePassword\"");

        // AND - the line under the box says there is a password and how long it is, and the box
        // offers nothing to read: an empty value with a placeholder instead of the password
        assertThat(body).as("state of the stored password")
            .contains("set (" + SAVED_PASSWORD.length() + " characters)");
        assertThat(body).as("placeholder of a password that is set").contains("placeholder=\"unchanged\"");
        assertThat(body).as("the stored password must never reach the page").doesNotContain(SAVED_PASSWORD);
    }

    ///// helpers /////

    /** Every setting in force from the configuration, i.e. nothing overridden in the admin panel. */
    private static AiSettingsService.Effective configuredEffective() {
        return new AiSettingsService.Effective(
            configured(true),
            configured("http://127.0.0.1:4096"),
            configured("litellm"),
            configured(CONFIGURED_MODEL),
            configured("allure-ai"),
            configured(CONFIGURED_PARALLEL),
            configured(300L),
            configured(false),
            builtIn(PromptBuilder.SYSTEM),
            builtIn((String) null),
            configured((String) null),
            configured((String) null)
        );
    }

    /** A prompt nobody has overridden: what is in force is the text the analysis core carries itself. */
    private static <T> AiSettingsService.Value<T> builtIn(T value) {
        return new AiSettingsService.Value<>(value, AiSettingsService.Source.BUILT_IN);
    }

    /** Everything from the configuration, except the system prompt, which the admin has saved. */
    private static AiSettingsService.Effective effectiveWithSavedSystemPrompt() {
        final AiSettingsService.Effective configured = configuredEffective();
        return new AiSettingsService.Effective(
            configured.enabled(),
            configured.opencodeUrl(),
            configured.provider(),
            configured.model(),
            configured.agent(),
            configured.parallel(),
            configured.timeoutSeconds(),
            configured.auto(),
            new AiSettingsService.Value<>(SAVED_PROMPT, AiSettingsService.Source.SETTINGS),
            builtIn((String) null),
            configured.opencodeUsername(),
            configured.opencodePassword()
        );
    }

    /** Everything from the configuration, except the OpenCode password, which the admin has saved. */
    private static AiSettingsService.Effective effectiveWithSavedPassword() {
        final AiSettingsService.Effective configured = configuredEffective();
        return new AiSettingsService.Effective(
            configured.enabled(),
            configured.opencodeUrl(),
            configured.provider(),
            configured.model(),
            configured.agent(),
            configured.parallel(),
            configured.timeoutSeconds(),
            configured.auto(),
            configured.systemPrompt(),
            configured.promptNotes(),
            new AiSettingsService.Value<>("bot", AiSettingsService.Source.SETTINGS),
            new AiSettingsService.Value<>(SAVED_PASSWORD, AiSettingsService.Source.SETTINGS)
        );
    }

    private static <T> AiSettingsService.Value<T> configured(T value) {
        return new AiSettingsService.Value<>(value, AiSettingsService.Source.CONFIGURATION);
    }

    private static Map<?, ?> extractFlashMap(MvcResult result) {
        final Object flashValue = result.getFlashMap().get(FLASH_KEY);
        assertThat(flashValue)
            .as("flash attribute under key 'flash' must be present in redirect attributes")
            .isInstanceOf(Map.class);
        return (Map<?, ?>) flashValue;
    }
}
