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
 * <p>
 * The OpenCode password is the exception to all of it. The card never renders it back, so an empty
 * password box cannot mean "clear": it means "keep what is stored". Clearing is what
 * {@link #clearOpencodePassword} is for, and it wins over a typed value. Its editor is a
 * {@code StringTrimmerEditor} that does NOT trim, because a leading or trailing space is part of a
 * password, not decoration around it.
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
 * @param promptNotes           project-specific rules added to every cluster prompt as its own section
 * @param opencodeUsername      HTTP Basic user of {@code opencode serve}
 * @param opencodePassword      HTTP Basic password of {@code opencode serve}; {@code null} keeps the stored one
 * @param clearOpencodePassword {@code TRUE} removes the stored password, whatever the box says
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
    String promptNotes,

    @Nullable
    @Size(max = 64, message = "must be at most 64 characters")
    String opencodeUsername,

    @Nullable
    @Size(max = 256, message = "must be at most 256 characters")
    String opencodePassword,

    @Nullable Boolean clearOpencodePassword
) {

    /**
     * Provider, model and agent are OpenCode identifiers: one word, no spaces, and no longer than
     * the column that stores them.
     */
    private static final String ID = "\\S{1,64}";
    private static final String ID_MESSAGE = "must be at most 64 characters without spaces";

    /**
     * A form that clears the card: every setting back to the configuration. All-{@code null} except
     * the password checkbox - a {@code null} password means "keep the stored one", so a reset has to
     * ask for the password to go explicitly.
     */
    public static AiSettingsForm empty() {
        return new AiSettingsForm(null, null, null, null, null, null, null, null, null, null,
            null, null, Boolean.TRUE);
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
            snapshot.aiPromptNotes(),
            snapshot.aiOpencodeUsername(),
            // The stored password is deliberately absent: the card must not render it back, and the
            // GET that uses this form is exactly the page an over-the-shoulder reader sees.
            null,
            null
        );
    }
}
