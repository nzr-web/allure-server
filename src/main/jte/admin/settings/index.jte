@import java.util.Map
@import org.springframework.security.web.csrf.CsrfToken
@import ru.iopump.qa.allure.web.dto.AiCheckView
@import ru.iopump.qa.allure.web.dto.AiSettingsView
@import ru.iopump.qa.allure.web.dto.CurrentUserView
@import ru.iopump.qa.allure.web.dto.SystemSettingsView
@param SystemSettingsView settings
@param AiSettingsView ai
@param String aiBuiltInPrompt
@param AiCheckView aiCheck = null
@param CurrentUserView currentUser = null
@param boolean authEnabled = true
@param boolean isAdmin = false
@param boolean signInRequired = false
@param CsrfToken csrf = null
@param Map<String, String> flash = null
@template.layout.main(
    title = "System Settings",
    activeNav = "admin-settings",
    currentUser = currentUser,
    authEnabled = authEnabled,
    isAdmin = isAdmin,
    signInRequired = signInRequired,
    csrf = csrf,
    content = @`
        <section class="space-y-6">
            @template.partials.breadcrumb(items = new String[]{"Admin", "Settings"})

            <h1 class="text-2xl font-bold text-text">System settings</h1>

            @template.partials.flash(flash = flash)

            <section class="card p-5 space-y-4">
                <header>
                    <h2 class="text-lg font-semibold text-text">API authentication</h2>
                    <p class="mt-1 text-sm text-text-muted">
                        When <strong>required</strong>, every <code class="font-mono text-primary">/api/**</code> request must carry either
                        HTTP Basic credentials or an <code class="font-mono text-primary">X-API-Token</code> header. Anonymous callers are
                        rejected with <code class="font-mono text-primary">401</code>.
                        When <strong>optional</strong>, anonymous requests are accepted as the shared <code class="font-mono text-primary">guest</code>
                        user (backward compatible with older Allure Maven/Gradle plugins).
                    </p>
                </header>
                <form method="post" action="/app/admin/settings/require-api-auth" class="flex items-center gap-3">
                    @template.partials.csrf(csrf = csrf)
                    <input type="hidden" name="requireApiAuth" value="${!settings.requireApiAuth()}">
                    <div class="flex items-center gap-3 rounded-md border border-border-subtle bg-surface px-4 py-2">
                        <span class="text-sm font-medium text-text-muted">Current:</span>
                        @if(settings.requireApiAuth())
                            <span class="inline-flex items-center rounded px-2 py-0.5 text-xs font-semibold bg-success/20 text-success">REQUIRED</span>
                        @else
                            <span class="inline-flex items-center rounded px-2 py-0.5 text-xs font-semibold bg-warning/20 text-warning">OPTIONAL (guest fallback)</span>
                        @endif
                    </div>
                    <button type="submit" class="btn-primary">
                        ${settings.requireApiAuth() ? "Disable requirement" : "Require API authentication"}
                    </button>
                </form>
            </section>

            <section class="card p-5 space-y-4">
                <header>
                    <h2 class="text-lg font-semibold text-text">AI analysis</h2>
                    <p class="mt-1 text-sm text-text-muted">
                        Runtime overrides of the <code class="font-mono text-primary">allure-ai.*</code> configuration. An
                        <strong>empty</strong> field (or <strong>Default</strong> in a list) means "keep the configured value", so the
                        badge next to each field says where the value in force comes from. The two prompts at the bottom have no
                        <code class="font-mono text-primary">allure-ai.*</code> counterpart: empty means the text built into the
                        analysis core. A running analysis keeps the settings it started with; the next job picks the new ones up
                        without a restart.
                    </p>
                </header>

                <form method="post" action="/app/admin/settings/ai" class="space-y-4">
                    @template.partials.csrf(csrf = csrf)

                    <div class="grid gap-4 md:grid-cols-2">
                        !{ var enabled = ai.enabled(); }
                        <div class="space-y-1">
                            <label for="ai-enabled" class="block text-sm font-medium text-text">Enabled</label>
                            <select id="ai-enabled" name="enabled" class="form-field">
                                <option value="" selected="${enabled.override().isEmpty()}">Default (configuration)</option>
                                <option value="true" selected="${enabled.override().equals("true")}">On</option>
                                <option value="false" selected="${enabled.override().equals("false")}">Off</option>
                            </select>
                            <p class="text-xs text-text-muted">
                                In force: <span class="font-mono text-text">${enabled.effective()}</span>
                                <span class="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${enabled.fromSettings() ? "bg-primary/15 text-primary" : "bg-surface text-text-muted"}">${enabled.source()}</span>
                            </p>
                        </div>

                        !{ var auto = ai.auto(); }
                        <div class="space-y-1">
                            <label for="ai-auto" class="block text-sm font-medium text-text">Start the model automatically</label>
                            <select id="ai-auto" name="auto" class="form-field">
                                <option value="" selected="${auto.override().isEmpty()}">Default (configuration)</option>
                                <option value="true" selected="${auto.override().equals("true")}">On</option>
                                <option value="false" selected="${auto.override().equals("false")}">Off</option>
                            </select>
                            <p class="text-xs text-text-muted">
                                In force: <span class="font-mono text-text">${auto.effective()}</span>
                                <span class="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${auto.fromSettings() ? "bg-primary/15 text-primary" : "bg-surface text-text-muted"}">${auto.source()}</span>
                            </p>
                        </div>

                        !{ var opencodeUrl = ai.opencodeUrl(); }
                        <div class="space-y-1 md:col-span-2">
                            <label for="ai-opencode-url" class="block text-sm font-medium text-text">OpenCode URL</label>
                            <input id="ai-opencode-url" type="text" name="opencodeUrl" class="form-field"
                                   value="${opencodeUrl.override()}" placeholder="${opencodeUrl.effective()}" autocomplete="off">
                            <p class="text-xs text-text-muted">
                                In force: <span class="font-mono text-text">${opencodeUrl.effective()}</span>
                                <span class="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${opencodeUrl.fromSettings() ? "bg-primary/15 text-primary" : "bg-surface text-text-muted"}">${opencodeUrl.source()}</span>
                            </p>
                        </div>

                        !{ var provider = ai.provider(); }
                        <div class="space-y-1">
                            <label for="ai-provider" class="block text-sm font-medium text-text">Provider</label>
                            <input id="ai-provider" type="text" name="provider" class="form-field"
                                   value="${provider.override()}" placeholder="${provider.effective()}" autocomplete="off">
                            <p class="text-xs text-text-muted">
                                In force: <span class="font-mono text-text">${provider.effective()}</span>
                                <span class="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${provider.fromSettings() ? "bg-primary/15 text-primary" : "bg-surface text-text-muted"}">${provider.source()}</span>
                            </p>
                        </div>

                        !{ var model = ai.model(); }
                        <div class="space-y-1">
                            <label for="ai-model" class="block text-sm font-medium text-text">Model</label>
                            <input id="ai-model" type="text" name="model" class="form-field"
                                   value="${model.override()}" placeholder="${model.effective()}" autocomplete="off">
                            <p class="text-xs text-text-muted">
                                In force: <span class="font-mono text-text">${model.effective()}</span>
                                <span class="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${model.fromSettings() ? "bg-primary/15 text-primary" : "bg-surface text-text-muted"}">${model.source()}</span>
                            </p>
                        </div>

                        !{ var agent = ai.agent(); }
                        <div class="space-y-1">
                            <label for="ai-agent" class="block text-sm font-medium text-text">Agent</label>
                            <input id="ai-agent" type="text" name="agent" class="form-field"
                                   value="${agent.override()}" placeholder="${agent.effective()}" autocomplete="off">
                            <p class="text-xs text-text-muted">
                                In force: <span class="font-mono text-text">${agent.effective()}</span>
                                <span class="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${agent.fromSettings() ? "bg-primary/15 text-primary" : "bg-surface text-text-muted"}">${agent.source()}</span>
                            </p>
                        </div>

                        !{ var parallel = ai.parallel(); }
                        <div class="space-y-1">
                            <label for="ai-parallel" class="block text-sm font-medium text-text">Clusters in parallel (1-8)</label>
                            <input id="ai-parallel" type="number" min="1" max="8" step="1" name="parallel" class="form-field"
                                   value="${parallel.override()}" placeholder="${parallel.effective()}">
                            <p class="text-xs text-text-muted">
                                In force: <span class="font-mono text-text">${parallel.effective()}</span>
                                <span class="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${parallel.fromSettings() ? "bg-primary/15 text-primary" : "bg-surface text-text-muted"}">${parallel.source()}</span>
                            </p>
                        </div>

                        !{ var timeoutSeconds = ai.timeoutSeconds(); }
                        <div class="space-y-1">
                            <label for="ai-timeout-seconds" class="block text-sm font-medium text-text">Answer timeout, seconds (30-3600)</label>
                            <input id="ai-timeout-seconds" type="number" min="30" max="3600" step="1" name="timeoutSeconds" class="form-field"
                                   value="${timeoutSeconds.override()}" placeholder="${timeoutSeconds.effective()}">
                            <p class="text-xs text-text-muted">
                                In force: <span class="font-mono text-text">${timeoutSeconds.effective()}</span>
                                <span class="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${timeoutSeconds.fromSettings() ? "bg-primary/15 text-primary" : "bg-surface text-text-muted"}">${timeoutSeconds.source()}</span>
                            </p>
                        </div>
                    </div>

                    !{ var systemPrompt = ai.systemPrompt(); }
                    <div class="space-y-1">
                        <label for="ai-system-prompt" class="block text-sm font-medium text-text">System prompt</label>
                        <textarea name="systemPrompt" id="ai-system-prompt" rows="12" class="form-field font-mono">${systemPrompt.override()}</textarea>
                        <p class="text-xs text-text-muted">
                            In force: <span class="font-mono text-text">${systemPrompt.chars()}</span>
                            <span class="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${systemPrompt.fromSettings() ? "bg-primary/15 text-primary" : "bg-surface text-text-muted"}">${systemPrompt.source()}</span>
                        </p>
                        <p class="text-xs text-text-muted">
                            Leave empty to use the built-in prompt. The JSON answer schema at the end must survive your edits,
                            otherwise every cluster ends without an answer. For project-specific rules prefer Project notes.
                        </p>
                        <details class="text-xs text-text-muted">
                            <!-- 14px above and below a 16px line make the hit target 44px, the smallest one a finger hits reliably. -->
                            <summary class="cursor-pointer select-none py-[14px]">Built-in prompt</summary>
                            <pre class="mt-2 max-h-72 overflow-auto whitespace-pre-wrap rounded-md border border-border-subtle bg-surface p-3 font-mono text-xs text-text">${aiBuiltInPrompt}</pre>
                        </details>
                    </div>

                    !{ var promptNotes = ai.promptNotes(); }
                    <div class="space-y-1">
                        <label for="ai-prompt-notes" class="block text-sm font-medium text-text">Project notes</label>
                        <textarea name="promptNotes" id="ai-prompt-notes" rows="5" class="form-field">${promptNotes.override()}</textarea>
                        <p class="text-xs text-text-muted">
                            In force: <span class="font-mono text-text">${promptNotes.chars()}</span>
                            <span class="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${promptNotes.fromSettings() ? "bg-primary/15 text-primary" : "bg-surface text-text-muted"}">${promptNotes.source()}</span>
                        </p>
                        <p class="text-xs text-text-muted">
                            Rules of this project, added to every cluster prompt as a section of its own - what the model cannot
                            know from the results alone ("a 502 from the gateway on the ift-2 stand is infrastructure").
                        </p>
                    </div>

                    <div class="flex flex-wrap items-center gap-3">
                        <button type="submit" class="btn-primary">Save</button>
                        <button type="submit" formaction="/app/admin/settings/ai/check" class="btn-bordered">Check connection</button>
                    </div>
                </form>

                @if(aiCheck != null)
                    <div class="rounded-md border px-4 py-3 text-sm ${aiCheck.ok() && aiCheck.modelFound() ? "border-success/60 text-success" : "border-error/60 text-error"}">
                        <p class="font-medium">${aiCheck.message()}</p>
                        @if(!aiCheck.providers().isEmpty())
                            <p class="mt-1 text-xs text-text-muted">
                                Providers:
                                @for(AiCheckView.Provider found : aiCheck.providers())
                                    <span class="font-mono text-text">${found.id()}</span> (${found.models()} models)
                                @endfor
                            </p>
                        @endif
                    </div>
                @endif

                <form method="post" action="/app/admin/settings/ai/reset">
                    @template.partials.csrf(csrf = csrf)
                    <button type="submit" class="btn-bordered">Reset to configuration</button>
                </form>
            </section>

            @if(settings.updatedByUsername() != null)
                <p class="text-xs text-text-muted">
                    Settings last updated by <span class="font-mono text-text">${settings.updatedByUsername()}</span> at
                    <span class="font-mono text-text">${settings.updatedAt().toString()}</span>
                </p>
            @endif
        </section>
    `
)
