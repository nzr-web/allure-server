package ru.iopump.qa.allure.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one thing {@link SystemSettingsService.Snapshot} does not get from being a record: its
 * {@code toString()}. The whole snapshot is logged at startup ({@code System settings loaded: {}}),
 * so the two prompt texts must be there as lengths - a generated record text would print a 16 000
 * character instruction into the first line of every log.
 */
class SystemSettingsSnapshotTest {

    private static final int PROMPT_LENGTH = 16000;
    private static final String SECRET_MARK = "Answer as a pirate";

    @Test
    @DisplayName("should print the prompts by length and never their text")
    void toString_printsPromptLengthsInsteadOfTheTexts() {
        // GIVEN - a snapshot carrying a system prompt as long as its column allows
        final String systemPrompt = SECRET_MARK + "x".repeat(PROMPT_LENGTH - SECRET_MARK.length());
        final SystemSettingsService.Snapshot snapshot = new SystemSettingsService.Snapshot(
            false, Instant.EPOCH, "admin",
            true, "http://127.0.0.1:4096", "litellm", "qwen3.8", "allure-ai", 2, 300L, false,
            systemPrompt, "Stand ift-2 is down");

        // WHEN - the snapshot goes into a log line
        final String line = snapshot.toString();

        // THEN - the prompts are there as lengths
        assertThat(line).as("system prompt in the log line").contains("aiSystemPrompt=16000 chars");
        assertThat(line).as("project notes in the log line").contains("aiPromptNotes=19 chars");

        // AND - not a word of either text is in it
        assertThat(line).as("text of the system prompt must not be logged").doesNotContain(SECRET_MARK);
        assertThat(line).as("text of the project notes must not be logged").doesNotContain("ift-2");
        assertThat(line.length()).as("length of the whole log line").isLessThan(300);

        // AND - everything else is still printed, so the line stays useful
        assertThat(line).as("the rest of the settings in the log line")
            .contains("requireApiAuth=false")
            .contains("updatedByUsername=admin")
            .contains("aiOpencodeUrl=http://127.0.0.1:4096")
            .contains("aiParallel=2");
    }

    @Test
    @DisplayName("should print an absent prompt as null, not as '0 chars'")
    void toString_printsAnAbsentPromptAsNull() {
        // GIVEN - a snapshot with no override at all
        final SystemSettingsService.Snapshot snapshot =
            new SystemSettingsService.Snapshot(false, Instant.EPOCH, null);

        // WHEN - it goes into a log line
        final String line = snapshot.toString();

        // THEN - an absent prompt looks like every other absent override
        assertThat(line).as("absent system prompt in the log line").contains("aiSystemPrompt=null");
        assertThat(line).as("absent project notes in the log line").contains("aiPromptNotes=null");
    }
}
