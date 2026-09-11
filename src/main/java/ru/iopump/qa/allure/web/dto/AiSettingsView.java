package ru.iopump.qa.allure.web.dto;

import ru.iopump.qa.allure.ai.AiSettingsService;

/**
 * Read-only view of the "AI analysis" card for {@code admin/settings/index.jte}: for every setting
 * both what the admin typed (empty when the setting is not overridden) and what is in force right
 * now, together with where the value in force comes from.
 * <p>
 * Mirrors {@link AiSettingsService.Effective} so the template binds to a {@code web/dto} type and
 * never imports a service, exactly as {@link SystemSettingsView} does for the other card.
 */
public record AiSettingsView(Item enabled,
                             Item opencodeUrl,
                             Item provider,
                             Item model,
                             Item agent,
                             Item parallel,
                             Item timeoutSeconds,
                             Item auto,
                             Text systemPrompt,
                             Text promptNotes) {

    public static AiSettingsView from(AiSettingsForm form, AiSettingsService.Effective effective) {
        return new AiSettingsView(
            Item.of(form.enabled(), effective.enabled()),
            Item.of(form.opencodeUrl(), effective.opencodeUrl()),
            Item.of(form.provider(), effective.provider()),
            Item.of(form.model(), effective.model()),
            Item.of(form.agent(), effective.agent()),
            Item.of(form.parallel(), effective.parallel()),
            Item.of(form.timeoutSeconds(), effective.timeoutSeconds()),
            Item.of(form.auto(), effective.auto()),
            Text.of(form.systemPrompt(), effective.systemPrompt()),
            Text.of(form.promptNotes(), effective.promptNotes())
        );
    }

    /**
     * One setting on the card.
     *
     * @param override     what to put into the input, empty string when the setting is not overridden
     * @param effective    the value in force, for the label next to the input
     * @param source       {@code SETTINGS} or {@code CONFIGURATION}, shown as the origin badge
     * @param fromSettings whether the value in force is the override (drives the badge colour)
     */
    public record Item(String override, String effective, String source, boolean fromSettings) {

        static Item of(Object override, AiSettingsService.Value<?> effective) {
            return new Item(
                override == null ? "" : String.valueOf(override),
                String.valueOf(effective.value()),
                effective.source().name(),
                effective.fromSettings()
            );
        }
    }

    /**
     * One of the two prompt texts. Unlike {@link Item} it never carries the value in force: a system
     * prompt is thousands of characters long and would be unreadable in a label - the card shows its
     * length instead, and the text itself either sits in the textarea (an override) or under the
     * "Built-in prompt" details (the core's own).
     *
     * @param override     what to put into the textarea, empty string when the prompt is not overridden
     * @param chars        how long the prompt in force is, or "none" when there is no text at all
     * @param source       {@code SETTINGS} or {@code BUILT-IN}, shown as the origin badge
     * @param fromSettings whether the text in force is the override (drives the badge colour)
     */
    public record Text(String override, String chars, String source, boolean fromSettings) {

        static Text of(String override, AiSettingsService.Value<String> effective) {
            final String inForce = effective.value();
            return new Text(
                override == null ? "" : override,
                inForce == null ? "none" : inForce.length() + " characters",
                effective.source().name().replace('_', '-'),
                effective.fromSettings()
            );
        }
    }
}
