package ru.iopump.qa.allure.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.iopump.qa.allure.entity.SystemSettingsEntity;
import ru.iopump.qa.allure.properties.AppSecurityProperties;
import ru.iopump.qa.allure.repo.SystemSettingsRepository;
import ru.iopump.qa.allure.web.dto.AiSettingsForm;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the singleton {@link SystemSettingsEntity} row and caches an immutable
 * snapshot in memory for lock-free read access on the request hot path.
 * <p>
 * The cached value is consulted by {@code SecurityConfiguration}'s authorization
 * manager for {@code /api/**} on every request — a DB round-trip there would
 * measurably hurt CI throughput. Writes update the row inside a transaction and
 * then replace the cache.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemSettingsService implements ApplicationRunner {

    private final SystemSettingsRepository systemSettingsRepository;
    private final AppSecurityProperties appSecurityProperties;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    /**
     * Self-reference resolved through the AOP proxy so that {@link #seedIfAbsent()} and
     * {@link #readExisting()} are invoked transactionally from the non-transactional
     * {@link #run(ApplicationArguments)} entry point (a plain {@code this.} call would
     * bypass the proxy and the {@code @Transactional} advice).
     */
    @Lazy
    @Autowired
    private SystemSettingsService self;

    /**
     * Seeds the singleton settings row and primes the in-memory cache. Runs as an
     * {@link ApplicationRunner} so Spring invokes it through the transactional proxy
     * after the context is refreshed — {@link Transactional} therefore applies and the
     * read-or-insert executes atomically in one transaction. A {@code @PostConstruct}
     * method would bypass the proxy and run each repository call in its own transaction.
     * <p>
     * On a shared database two instances can both observe an empty table and race to
     * INSERT {@code SINGLETON_ID}; the loser's flush fails on the primary-key
     * constraint, which is caught and recovered by re-reading the now-present row in a
     * fresh transaction (so neither instance crashes on startup).
     */
    @Override
    public void run(ApplicationArguments args) {
        Snapshot snapshot;
        try {
            snapshot = self.seedIfAbsent();
        } catch (DataIntegrityViolationException raceLost) {
            log.info("System settings row was inserted concurrently — re-reading");
            snapshot = self.readExisting();
        }
        cache.set(snapshot);
        log.info("System settings loaded: {}", cache.get());
    }

    @Transactional
    Snapshot seedIfAbsent() {
        return systemSettingsRepository.findById(SystemSettingsEntity.SINGLETON_ID)
            .map(Snapshot::of)
            .orElseGet(this::insertDefaultRow);
    }

    @Transactional(readOnly = true)
    Snapshot readExisting() {
        return systemSettingsRepository.findById(SystemSettingsEntity.SINGLETON_ID)
            .map(Snapshot::of)
            .orElseGet(() -> new Snapshot(appSecurityProperties.requireApiAuth(), Instant.EPOCH, null));
    }

    private Snapshot insertDefaultRow() {
        final SystemSettingsEntity seeded = SystemSettingsEntity.builder()
            .id(SystemSettingsEntity.SINGLETON_ID)
            .requireApiAuth(appSecurityProperties.requireApiAuth())
            .updatedAt(Instant.now())
            .updatedByUsername(null)
            .build();
        log.info("Seeding system settings (requireApiAuth={})", seeded.isRequireApiAuth());
        return Snapshot.of(systemSettingsRepository.saveAndFlush(seeded));
    }

    public boolean isRequireApiAuth() {
        final Snapshot snapshot = cache.get();
        return snapshot != null && snapshot.requireApiAuth();
    }

    public Snapshot current() {
        final Snapshot snapshot = cache.get();
        if (snapshot != null) {
            return snapshot;
        }
        // Defensive: the ApplicationRunner seeding has not completed yet (e.g. a test
        // bean calls in before context startup finished).
        return new Snapshot(appSecurityProperties.requireApiAuth(), Instant.EPOCH, null);
    }

    @Transactional
    public Snapshot updateRequireApiAuth(boolean requireApiAuth, String actorUsername) {
        final SystemSettingsEntity entity = systemSettingsRepository.findById(SystemSettingsEntity.SINGLETON_ID)
            .orElseGet(() -> SystemSettingsEntity.builder()
                .id(SystemSettingsEntity.SINGLETON_ID)
                .build());
        entity.setRequireApiAuth(requireApiAuth);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedByUsername(actorUsername);
        final SystemSettingsEntity saved = systemSettingsRepository.save(entity);
        final Snapshot snapshot = Snapshot.of(saved);
        // Publish to the lock-free read cache ONLY after the DB commit succeeds. Setting it
        // inside the transaction would leave the cache diverged from the persisted row if the
        // transaction later rolled back — /api/** could then fail OPEN on a stale snapshot.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.set(snapshot);
            }
        });
        log.info("System settings updated by '{}': requireApiAuth={}", actorUsername, requireApiAuth);
        return snapshot;
    }

    /**
     * Overwrites the AI-analysis settings of the singleton row, leaving everything else untouched.
     * The row is loaded and mutated in place on purpose: rebuilding it through the builder would
     * silently reset {@code requireApiAuth} (and the audit columns) of the other card on this page.
     *
     * The OpenCode password is the one field a {@code null} does NOT clear: the card never renders
     * it back, so an empty password box means "leave the stored one alone" and the checkbox
     * {@code clearOpencodePassword} is what removes it. The checkbox wins over a typed value, so a
     * mis-click cannot save a password the admin meant to drop.
     *
     * @param form          the admin form; a {@code null} component clears that single override
     * @param actorUsername who is changing the settings, for the audit line
     */
    @Transactional
    public Snapshot updateAiSettings(AiSettingsForm form, String actorUsername) {
        final SystemSettingsEntity entity = systemSettingsRepository.findById(SystemSettingsEntity.SINGLETON_ID)
            .orElseGet(() -> SystemSettingsEntity.builder()
                .id(SystemSettingsEntity.SINGLETON_ID)
                .build());
        final String changed = describeChanges(entity, form);
        entity.setAiEnabled(form.enabled());
        entity.setAiOpencodeUrl(form.opencodeUrl());
        entity.setAiProvider(form.provider());
        entity.setAiModel(form.model());
        entity.setAiAgent(form.agent());
        entity.setAiParallel(form.parallel());
        entity.setAiTimeoutSeconds(form.timeoutSeconds());
        entity.setAiAuto(form.auto());
        entity.setAiSystemPrompt(form.systemPrompt());
        entity.setAiPromptNotes(form.promptNotes());
        entity.setAiOpencodeUsername(form.opencodeUsername());
        entity.setAiOpencodePassword(newPassword(entity, form));
        final Snapshot snapshot = saveAndPublish(entity, actorUsername);
        log.info("AI settings updated by '{}': {}", actorUsername, changed);
        return snapshot;
    }

    /**
     * Clears every AI override, so all twelve settings fall back to what they are without the panel:
     * the {@code allure-ai.*} configuration for the ten of them that have one, and the prompt
     * built into the allure-ai core for the system prompt (with no project notes at all). The
     * password goes too: {@link AiSettingsForm#empty()} carries the clearing checkbox for it.
     */
    @Transactional
    public Snapshot resetAiSettings(String actorUsername) {
        return updateAiSettings(AiSettingsForm.empty(), actorUsername);
    }

    /**
     * Stamps the audit columns, saves and publishes the new snapshot to the lock-free read cache
     * after the commit - for the same reason {@link #updateRequireApiAuth} does it that way.
     */
    private Snapshot saveAndPublish(SystemSettingsEntity entity, String actorUsername) {
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedByUsername(actorUsername);
        final Snapshot snapshot = Snapshot.of(systemSettingsRepository.save(entity));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.set(snapshot);
            }
        });
        return snapshot;
    }

    /** Names of the settings this form changes, for the audit log line. */
    private static String describeChanges(SystemSettingsEntity entity, AiSettingsForm form) {
        final StringBuilder changed = new StringBuilder();
        appendIfChanged(changed, "enabled", entity.getAiEnabled(), form.enabled());
        appendIfChanged(changed, "opencodeUrl", entity.getAiOpencodeUrl(), form.opencodeUrl());
        appendIfChanged(changed, "provider", entity.getAiProvider(), form.provider());
        appendIfChanged(changed, "model", entity.getAiModel(), form.model());
        appendIfChanged(changed, "agent", entity.getAiAgent(), form.agent());
        appendIfChanged(changed, "parallel", entity.getAiParallel(), form.parallel());
        appendIfChanged(changed, "timeoutSeconds", entity.getAiTimeoutSeconds(), form.timeoutSeconds());
        appendIfChanged(changed, "auto", entity.getAiAuto(), form.auto());
        appendTextIfChanged(changed, "systemPrompt", entity.getAiSystemPrompt(), form.systemPrompt());
        appendTextIfChanged(changed, "promptNotes", entity.getAiPromptNotes(), form.promptNotes());
        appendIfChanged(changed, "opencodeUsername", entity.getAiOpencodeUsername(), form.opencodeUsername());
        appendPasswordIfChanged(changed, entity.getAiOpencodePassword(), newPassword(entity, form));
        return changed.length() == 0 ? "nothing changed" : changed.toString();
    }

    private static void appendIfChanged(StringBuilder changed, String name, Object before, Object after) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (changed.length() > 0) {
            changed.append(", ");
        }
        changed.append(name).append('=').append(after == null ? "default (configuration)" : after);
    }

    /**
     * Same, but for the prompt texts: the audit line says how long the new text is, never what it
     * says. A whole instruction in a log line would bury every other line of the startup log.
     * A cleared prompt falls back to the text built into the allure-ai core, not to a configuration
     * property - there is none for either prompt - so the line says {@code default (built-in)}.
     */
    private static void appendTextIfChanged(StringBuilder changed, String name, String before, String after) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (changed.length() > 0) {
            changed.append(", ");
        }
        changed.append(name).append('=')
            .append(after == null ? "default (built-in)" : after.length() + " chars");
    }

    /**
     * The password the form leaves in the row: the checkbox clears it, a filled box replaces it, and
     * an empty box with the checkbox unticked keeps whatever is stored. Used by both the write and
     * the audit line, so the log can never claim a change the row did not get.
     */
    private static String newPassword(SystemSettingsEntity entity, AiSettingsForm form) {
        if (Boolean.TRUE.equals(form.clearOpencodePassword())) {
            return null;
        }
        return form.opencodePassword() == null ? entity.getAiOpencodePassword() : form.opencodePassword();
    }

    /**
     * Same as {@link #appendIfChanged}, for the OpenCode password: the audit line says that a
     * password was set and how long it is, never what it is. An unchanged password is not mentioned
     * at all - an empty password box is the normal way to save the rest of the card.
     */
    private static void appendPasswordIfChanged(StringBuilder changed, String before, String after) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (changed.length() > 0) {
            changed.append(", ");
        }
        changed.append("opencodePassword=")
            .append(after == null ? "cleared" : "set (" + after.length() + " chars)");
    }

    /**
     * Immutable view of the settings row. The {@code ai*} components are nullable and {@code null}
     * means "not overridden in the admin panel": {@code AiSettingsService} then takes the value
     * from the {@code allure-ai.*} configuration.
     */
    public record Snapshot(boolean requireApiAuth,
                           Instant updatedAt,
                           String updatedByUsername,
                           Boolean aiEnabled,
                           String aiOpencodeUrl,
                           String aiProvider,
                           String aiModel,
                           String aiAgent,
                           Integer aiParallel,
                           Long aiTimeoutSeconds,
                           Boolean aiAuto,
                           String aiSystemPrompt,
                           String aiPromptNotes,
                           String aiOpencodeUsername,
                           String aiOpencodePassword) {

        /** A snapshot with no AI override at all - used for the pre-startup fallback. */
        public Snapshot(boolean requireApiAuth, Instant updatedAt, String updatedByUsername) {
            this(requireApiAuth, updatedAt, updatedByUsername, null, null, null, null, null, null, null, null,
                null, null, null, null);
        }

        static Snapshot of(SystemSettingsEntity entity) {
            return new Snapshot(entity.isRequireApiAuth(), entity.getUpdatedAt(), entity.getUpdatedByUsername(),
                entity.getAiEnabled(), entity.getAiOpencodeUrl(), entity.getAiProvider(), entity.getAiModel(),
                entity.getAiAgent(), entity.getAiParallel(), entity.getAiTimeoutSeconds(), entity.getAiAuto(),
                entity.getAiSystemPrompt(), entity.getAiPromptNotes(),
                entity.getAiOpencodeUsername(), entity.getAiOpencodePassword());
        }

        /**
         * The record text with the two prompts replaced by their lengths: {@code run} logs the whole
         * snapshot at startup, and a 16 000-character instruction there hides the rest of the line.
         */
        @Override
        public String toString() {
            return "Snapshot[requireApiAuth=" + requireApiAuth
                + ", updatedAt=" + updatedAt
                + ", updatedByUsername=" + updatedByUsername
                + ", aiEnabled=" + aiEnabled
                + ", aiOpencodeUrl=" + aiOpencodeUrl
                + ", aiProvider=" + aiProvider
                + ", aiModel=" + aiModel
                + ", aiAgent=" + aiAgent
                + ", aiParallel=" + aiParallel
                + ", aiTimeoutSeconds=" + aiTimeoutSeconds
                + ", aiAuto=" + aiAuto
                + ", aiSystemPrompt=" + length(aiSystemPrompt)
                + ", aiPromptNotes=" + length(aiPromptNotes)
                + ", aiOpencodeUsername=" + aiOpencodeUsername
                + ", aiOpencodePassword=" + secret(aiOpencodePassword)
                + ']';
        }

        private static String length(String text) {
            return text == null ? "null" : text.length() + " chars";
        }

        /** The stored password as a log line may name it: that there is one, and how long it is. */
        private static String secret(String password) {
            return password == null ? "null" : "set (" + password.length() + " chars)";
        }
    }
}
