package ru.iopump.qa.allure.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.iopump.qa.allure.ai.AiConnectionCheckService;
import ru.iopump.qa.allure.ai.AiSettingsService;
import ru.iopump.qa.allure.entity.UserEntity;
import ru.iopump.qa.allure.security.CurrentUserProvider;
import ru.iopump.qa.allure.service.SystemSettingsService;
import ru.iopump.qa.allure.web.dto.AiCheckView;
import ru.iopump.qa.allure.web.dto.AiSettingsForm;
import ru.iopump.qa.allure.web.dto.AiSettingsView;
import ru.iopump.qa.allure.web.dto.SystemSettingsView;
import ru.vtb.at.allureai.llm.PromptBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only runtime settings UI at {@code /app/admin/settings}. Currently
 * exposes the {@code requireApiAuth} toggle — when on, {@code /api/**} requires
 * authentication (Basic or X-API-Token); when off, anonymous API traffic is
 * treated as guest (transitional default for backward compatibility) — and the
 * "AI analysis" card, which overrides the {@code allure-ai.*} configuration at
 * runtime: an empty field means "keep the configured value".
 */
@Controller
@RequestMapping("/app/admin/settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
public class AdminSettingsController {

    private static final String VIEW_INDEX = "admin/settings/index";
    private static final String REDIRECT_INDEX = "redirect:/app/admin/settings";
    private static final String FLASH_KEY = "flash";

    private final SystemSettingsService systemSettingsService;
    private final CurrentUserProvider currentUserProvider;
    private final AiSettingsService aiSettingsService;
    private final AiConnectionCheckService aiConnectionCheckService;

    /**
     * An empty text field means "no override", not "an override that is an empty string": trimming
     * to {@code null} is what makes a cleared field fall back to the configuration. Numbers need no
     * help — an empty {@code Integer}/{@code Long} parameter binds to {@code null} already.
     */
    @InitBinder
    void trimEmptyStringsToNull(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }

    /**
     * The instruction built into the allure-ai core, shown on the card so that an admin edits a copy
     * of it instead of writing one from scratch. A model attribute and not a field of
     * {@link AiSettingsView}: it is the same constant on every request and says nothing about what is
     * stored in the settings row. Every POST handler here takes {@code RedirectAttributes}, so this
     * attribute is not appended to a redirect url.
     */
    @ModelAttribute("aiBuiltInPrompt")
    String aiBuiltInPrompt() {
        return PromptBuilder.SYSTEM;
    }

    @GetMapping
    public String index(Model model) {
        final SystemSettingsService.Snapshot snapshot = systemSettingsService.current();
        model.addAttribute("settings", SystemSettingsView.from(snapshot));
        model.addAttribute("ai", AiSettingsView.from(AiSettingsForm.of(snapshot), aiSettingsService.effective()));
        model.addAttribute("title", "System Settings");
        model.addAttribute("activeNav", "admin-settings");
        return VIEW_INDEX;
    }

    /**
     * Saves the AI card. Validation failures leave the database untouched: {@code @Valid} without a
     * {@code BindingResult} parameter raises a {@code BindException}, which {@link WebExceptionAdvice}
     * turns into the same "Form rejected: …" flash toast every other form on this surface uses.
     */
    @PostMapping("/ai")
    public String updateAiSettings(@Valid @ModelAttribute AiSettingsForm form, RedirectAttributes flash) {
        final UserEntity actor = currentUserProvider.current();
        systemSettingsService.updateAiSettings(form, actor.getUsername());
        flash.addFlashAttribute(FLASH_KEY, toastMap("success", "AI analysis settings saved."));
        return REDIRECT_INDEX;
    }

    @PostMapping("/ai/reset")
    public String resetAiSettings(RedirectAttributes flash) {
        final UserEntity actor = currentUserProvider.current();
        systemSettingsService.resetAiSettings(actor.getUsername());
        flash.addFlashAttribute(FLASH_KEY,
            toastMap("success", "AI analysis settings cleared: the allure-ai configuration is in force again."));
        return REDIRECT_INDEX;
    }

    /**
     * Probes the OpenCode instance the form points at and renders the page straight away instead of
     * redirecting: the result belongs to the values just typed, and a redirect would either lose
     * them or have to save them first. Nothing is written — a check is not a save.
     */
    @PostMapping("/ai/check")
    public String checkAiConnection(@ModelAttribute AiSettingsForm form, Model model) {
        final AiSettingsService.Effective effective = aiSettingsService.effective();
        final String url = form.opencodeUrl() == null ? effective.opencodeUrl().value() : form.opencodeUrl();
        final String provider = form.provider() == null ? effective.provider().value() : form.provider();
        final String modelId = form.model() == null ? effective.model().value() : form.model();
        model.addAttribute("settings", SystemSettingsView.from(systemSettingsService.current()));
        model.addAttribute("ai", AiSettingsView.from(form, effective));
        model.addAttribute("aiCheck", AiCheckView.from(aiConnectionCheckService.check(url, provider, modelId)));
        model.addAttribute("title", "System Settings");
        model.addAttribute("activeNav", "admin-settings");
        return VIEW_INDEX;
    }

    @PostMapping("/require-api-auth")
    public String updateRequireApiAuth(@RequestParam(name = "requireApiAuth", defaultValue = "false") boolean requireApiAuth,
                                       RedirectAttributes flash) {
        final UserEntity actor = currentUserProvider.current();
        systemSettingsService.updateRequireApiAuth(requireApiAuth, actor.getUsername());
        final String message = requireApiAuth
            ? "API authentication is now REQUIRED. Anonymous /api/** requests will receive 401."
            : "API authentication is now OPTIONAL. Anonymous /api/** requests are accepted as guest.";
        flash.addFlashAttribute(FLASH_KEY, toastMap("success", message));
        return REDIRECT_INDEX;
    }

    private static Map<String, String> toastMap(String level, String message) {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("level", level);
        map.put("message", message == null ? "" : message);
        return map;
    }
}
