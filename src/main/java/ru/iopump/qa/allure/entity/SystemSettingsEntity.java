package ru.iopump.qa.allure.entity;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * Singleton row holding runtime-adjustable system flags. There is exactly one row
 * identified by {@link #SINGLETON_ID} — admin-panel writes overwrite in place.
 */
@Entity
@Table(name = "app_system_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString
public class SystemSettingsEntity {

    public static final UUID SINGLETON_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    private UUID id;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    @Builder.Default
    private long version = 0;

    @Column(name = "require_api_auth", nullable = false)
    @Builder.Default
    private boolean requireApiAuth = false;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Nullable
    @Column(name = "updated_by_username", length = 128)
    private String updatedByUsername;

    // AI analysis overrides. Every one of them is nullable on purpose: null means "not set here",
    // and the effective value then comes from the AiProperties configuration. See AiSettingsService.

    @Nullable
    @Column(name = "ai_enabled")
    private Boolean aiEnabled;

    @Nullable
    @Column(name = "ai_opencode_url", length = 512)
    private String aiOpencodeUrl;

    @Nullable
    @Column(name = "ai_provider", length = 64)
    private String aiProvider;

    @Nullable
    @Column(name = "ai_model", length = 64)
    private String aiModel;

    @Nullable
    @Column(name = "ai_agent", length = 64)
    private String aiAgent;

    @Nullable
    @Column(name = "ai_parallel")
    private Integer aiParallel;

    @Nullable
    @Column(name = "ai_timeout_seconds")
    private Long aiTimeoutSeconds;

    @Nullable
    @Column(name = "ai_auto")
    private Boolean aiAuto;

    // The two prompt texts are kept out of toString(): SystemSettingsService logs the whole
    // snapshot at startup, and a 16 000-character instruction in that line hides everything else.

    @Nullable
    @ToString.Exclude
    @Column(name = "ai_system_prompt", length = 16000)
    private String aiSystemPrompt;

    @Nullable
    @ToString.Exclude
    @Column(name = "ai_prompt_notes", length = 4000)
    private String aiPromptNotes;
}
