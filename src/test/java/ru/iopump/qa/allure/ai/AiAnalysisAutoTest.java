package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.iopump.qa.allure.repo.JpaReportRepository;
import ru.iopump.qa.allure.service.SystemSettingsService;
import ru.iopump.qa.allure.web.dto.AiSettingsForm;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code allure-ai.auto} - the pipeline sends one extra JSON field and never calls the analysis
 * endpoint at all. The interesting part is the timing: the worker is started from a transaction
 * synchronisation, because the generation runs inside a transaction and the worker thread would
 * otherwise look for a report row that is not committed yet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ai-analysis-auto-test-db;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.security.require-api-auth=false",
    "basic.auth.enable=false",
    "gg.jte.development-mode=false",
    "gg.jte.use-precompiled-templates=true",
    "allure.results-dir=build/ai-analysis-auto-it/results/",
    "allure.reports.dir=build/ai-analysis-auto-it/reports/",
    "allure-ai.cache-dir=build/ai-analysis-auto-it/cache",
    "allure-ai.parallel=1",
    "allure-ai.timeout-seconds=30",
    "allure-ai.auto=true",
    "allure-ai.sweep-cron=-"
})
class AiAnalysisAutoTest {

    private static final Path WORK_DIR = Paths.get("build", "ai-analysis-auto-it");
    private static final Path RESULTS_DIR = WORK_DIR.resolve("results");
    private static final String API_REPORT = "/api/report";
    private static final String REPORT_PATH_HEAD = "ai";
    private static final String REPORT_PATH_TAIL = "auto";
    private static final String REPORT_PATH_TAIL_OFF = "auto-off";
    private static final int EXPECTED_REPORTS = 2;
    private static final long AWAIT_TIMEOUT_MS = 60_000L;

    private static final OpenCodeStub STUB;

    static {
        FileUtils.deleteQuietly(WORK_DIR.toFile());
        STUB = OpenCodeStub.start();
    }

    @DynamicPropertySource
    static void openCodeUrl(DynamicPropertyRegistry registry) {
        registry.add("allure-ai.opencode-url", () -> "http://127.0.0.1:" + STUB.port());
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
    private JpaReportRepository reportRepository;

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Autowired
    private SystemSettingsService systemSettingsService;

    @AfterEach
    void clearSettingsOverrides() {
        systemSettingsService.resetAiSettings("test-teardown");
    }

    @Test
    @DisplayName("should analyse and publish without a second call when allure-ai.auto is on")
    void startsTheWorkerItselfAfterGeneration() throws Exception {
        // GIVEN - a nightly run and auto mode enabled
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String body = """
            {"reportSpec":{"path":["%s","%s"],"executorInfo":{"buildName":"nightly"}},
             "results":["%s"],"deleteResults":true,"aiAnalysis":true}
            """.formatted(REPORT_PATH_HEAD, REPORT_PATH_TAIL, resultUuid);

        // WHEN - only the generation is requested; nothing calls /api/report/{uuid}/ai
        final String response = mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        final String pendingUuid = objectMapper.readTree(response).path("uuid").asText();

        // THEN - the worker ran on its own and published the analysed version of the report
        assertThat(awaitFinished(pendingUuid)).as("job status in auto mode").isEqualTo(AiJobStatus.DONE);
        assertThat(aiAnalysisService.status(pendingUuid).getResultUuid())
            .as("report published by the unattended worker")
            .isNotNull();
        assertThat(reportRepository.findByPath(REPORT_PATH_HEAD + "/" + REPORT_PATH_TAIL))
            .as("reports of this path after one generation in auto mode")
            .hasSize(EXPECTED_REPORTS);
    }

    @Test
    @DisplayName("should leave the job waiting when the settings switch auto off while allure-ai.auto is on")
    void settingsSwitchAutoOffAgainstTheConfiguration() throws Exception {
        // GIVEN - the same nightly run, but the admin panel says "auto: off" over allure-ai.auto=true
        systemSettingsService.updateAiSettings(autoOff(), "test-setup");
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 2, "gamma");
        final String body = """
            {"reportSpec":{"path":["%s","%s"],"executorInfo":{"buildName":"nightly"}},
             "results":["%s"],"deleteResults":true,"aiAnalysis":true}
            """.formatted(REPORT_PATH_HEAD, REPORT_PATH_TAIL_OFF, resultUuid);

        // WHEN - the report is generated
        final String response = mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        final String pendingUuid = objectMapper.readTree(response).path("uuid").asText();

        // THEN - nothing started by itself: the unattended start happens inside the generation
        // request, so a job that is still pending when it returns was never queued
        assertThat(aiAnalysisService.status(pendingUuid).getStatus())
            .as("job status when the settings switch auto off")
            .isEqualTo(AiJobStatus.PENDING);
        assertThat(reportRepository.findByPath(REPORT_PATH_HEAD + "/" + REPORT_PATH_TAIL_OFF))
            .as("reports of this path: the analysed version must not appear")
            .hasSize(1);
    }

    /** Every setting at its configured value except {@code auto}, which is switched off by hand. */
    private static AiSettingsForm autoOff() {
        return new AiSettingsForm(null, null, null, null, null, null, null, false, null, null);
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
}
