package ru.iopump.qa.allure.web.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ru.iopump.qa.allure.service.SystemSettingsService;

/**
 * Binds the "AI analysis" card on {@code /app/admin/settings}. Every component is nullable and
 * {@code null} carries meaning: "not set here, take the value from the {@code allure-ai.*}
 * configuration". The controller registers a {@code StringTrimmerEditor} that turns an empty text
 * field into {@code null}, and an empty {@code Integer}/{@code Long} field binds to {@code null}
 * on its own, so a cleared field resets exactly one setting back to the configuration.
 * <p>
 * The boolean settings are rendered as a three-state {@code <select>} (default / on / off) for the
 * same reason: a checkbox has no way to say "not set".
 *
 * @param enabled        master switch of the analysis
 * @param opencodeUrl    base url of a running {@code opencode serve}
 * @param provider       OpenCode provider id
 * @param model          model id inside the provider
 * @param agent          OpenCode agent, declared without tools
 * @param parallel       clusters analysed in parallel inside one job
 * @param timeoutSeconds timeout of a single model answer
 * @param auto           start the worker right after a report is generated with {@code aiAnalysis}
 * @param systemPrompt   instruction sent to the model instead of the one built into the allure-ai core
 * @param promptNotes    project-specific rules added to every cluster prompt as its own section
 */
public record AiSettingsForm(
    @Nullable Boolean enabled,

    @Nullable
    @Size(max = 512, message = "must be at most 512 characters")
    @Pattern(regexp = "https?://\\S+", message = "must start with http:// or https://")
    String opencodeUrl,

    @Nullable @Pattern(regexp = ID, message = ID_MESSAGE) String provider,

    @Nullable @Pattern(regexp = ID, message = ID_MESSAGE) String model,

    @Nullable @Pattern(regexp = ID, message = ID_MESSAGE) String agent,

    @Nullable
    @Min(value = 1, message = "must be between 1 and 8")
    @Max(value = 8, message = "must be between 1 and 8")
    Integer parallel,

    @Nullable
    @Min(value = 30, message = "must be between 30 and 3600")
    @Max(value = 3600, message = "must be between 30 and 3600")
    Long timeoutSeconds,

    @Nullable Boolean auto,

    @Nullable
    @Size(max = 16000, message = "must be at most 16000 characters")
    String systemPrompt,

    @Nullable
    @Size(max = 4000, message = "must be at most 4000 characters")
    String promptNotes
) {

    /**
     * Provider, model and agent are OpenCode identifiers: one word, no spaces, and no longer than
     * the column that stores them.
     */
    private static final String ID = "\\S{1,64}";
    private static final String ID_MESSAGE = "must be at most 64 characters without spaces";

    /** An all-{@code null} form: every setting falls back to the configuration. */
    public static AiSettingsForm empty() {
        return new AiSettingsForm(null, null, null, null, null, null, null, null, null, null);
    }

    /** The overrides currently stored in the settings row, to pre-fill the card on a GET. */
    public static AiSettingsForm of(SystemSettingsService.Snapshot snapshot) {
        return new AiSettingsForm(
            snapshot.aiEnabled(),
            snapshot.aiOpencodeUrl(),
            snapshot.aiProvider(),
            snapshot.aiModel(),
            snapshot.aiAgent(),
            snapshot.aiParallel(),
            snapshot.aiTimeoutSeconds(),
            snapshot.aiAuto(),
            snapshot.aiSystemPrompt(),
            snapshot.aiPromptNotes()
        );
    }
}
