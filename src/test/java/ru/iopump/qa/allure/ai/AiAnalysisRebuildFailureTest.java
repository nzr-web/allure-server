package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.iopump.qa.allure.entity.ReportEntity;
import ru.iopump.qa.allure.helper.AllureReportGenerator;
import ru.iopump.qa.allure.repo.JpaReportRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the report looks like when the rebuild after the analysis fails. The generator is the real
 * one, spied on: it builds the first report as usual and misbehaves only when asked to build into
 * the {@code .ai-tmp} sibling, which is exactly the rebuild. A separate class because the spy
 * replaces the generator bean for the whole context. Unstubbed, the spy is the real generator, which
 * is what the recovery from an interrupted swap is tested with.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ai-analysis-rebuild-failure-test-db;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.security.require-api-auth=false",
    "basic.auth.enable=false",
    "gg.jte.development-mode=false",
    "gg.jte.use-precompiled-templates=true",
    "allure.results-dir=build/ai-analysis-rebuild-failure-it/results/",
    "allure.reports.dir=build/ai-analysis-rebuild-failure-it/reports/",
    "allure-ai.cache-dir=build/ai-analysis-rebuild-failure-it/cache",
    "allure-ai.parallel=1",
    "allure-ai.timeout-seconds=30",
    "allure-ai.auto=false",
    "allure-ai.sweep-cron=-"
})
class AiAnalysisRebuildFailureTest {

    private static final Path WORK_DIR = Paths.get("build", "ai-analysis-rebuild-failure-it");
    private static final Path RESULTS_DIR = WORK_DIR.resolve("results");
    private static final Path REPORTS_DIR = WORK_DIR.resolve("reports");
    private static final String API_REPORT = "/api/report";
    private static final String SWAP_TMP_SUFFIX = ".ai-tmp";
    private static final String SWAP_OLD_SUFFIX = ".ai-old";
    private static final String GENERATOR_FAILURE = "generator failed on purpose";
    private static final long AWAIT_TIMEOUT_MS = 120_000L;

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

    @MockitoSpyBean
    private AllureReportGenerator generator;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JpaReportRepository reportRepository;

    @Autowired
    private AiAnalysisService aiAnalysisService;

    @Test
    @DisplayName("should leave the report untouched and fail the job when the rebuild after the analysis fails")
    void keepsTheReportWhenTheRebuildFails() throws Exception {
        // GIVEN - a pending report built as usual, and a generator that fails to build into '.ai-tmp'
        doThrow(new IllegalStateException(GENERATOR_FAILURE)).when(generator).generate(
            argThat(output -> output != null && output.getFileName().toString().endsWith(SWAP_TMP_SUFFIX)),
            anyList(), anyString(), anyBoolean());
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String reportUuid = generate("ai/rebuild-failure", resultUuid);
        final Path report = REPORTS_DIR.resolve(reportUuid);
        final Map<String, String> filesBefore = snapshot(report);
        final long sizeBefore = reportRepository.findOneByUuid(UUID.fromString(reportUuid)).orElseThrow().getSize();

        // WHEN - the model answers and the rebuild is attempted
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai")).andExpect(status().isAccepted());

        // THEN - the job fails with the generator's reason
        assertThat(awaitFinished(reportUuid)).as("job status after a failed rebuild").isEqualTo(AiJobStatus.ERROR);
        assertThat(aiAnalysisService.status(reportUuid).getError())
            .as("failure text of the job")
            .contains(GENERATOR_FAILURE);

        // AND - the report is exactly what it was: every file, its size and its modification time
        assertThat(snapshot(report))
            .as("files of the report after a failed rebuild")
            .isEqualTo(filesBefore);
        assertThat(reportRepository.findOneByUuid(UUID.fromString(reportUuid)).orElseThrow().getSize())
            .as("size of the report in the database after a failed rebuild")
            .isEqualTo(sizeBefore);

        // AND - neither side of the swap is left behind
        assertThat(REPORTS_DIR.resolve(reportUuid + SWAP_TMP_SUFFIX))
            .as("directory the failed rebuild was building in")
            .doesNotExist();
        assertThat(REPORTS_DIR.resolve(reportUuid + SWAP_OLD_SUFFIX))
            .as("directory the report would have been moved to")
            .doesNotExist();
    }

    @Test
    @DisplayName("should move the report back and fail the job when the rebuilt report cannot be moved in")
    void movesTheReportBackWhenTheSwapFails() throws Exception {
        // GIVEN - a pending report, and a rebuild that "succeeds" without writing anything, so the
        // second rename of the swap has nothing to move - after the report has been moved aside
        doAnswer(invocation -> invocation.getArgument(0)).when(generator).generate(
            argThat(output -> output != null && output.getFileName().toString().endsWith(SWAP_TMP_SUFFIX)),
            anyList(), anyString(), anyBoolean());
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String reportUuid = generate("ai/swap-failure", resultUuid);
        final Path report = REPORTS_DIR.resolve(reportUuid);
        final Map<String, String> filesBefore = snapshot(report);

        // WHEN - the model answers and the swap is attempted
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai")).andExpect(status().isAccepted());

        // THEN - the job fails
        assertThat(awaitFinished(reportUuid)).as("job status after a failed swap").isEqualTo(AiJobStatus.ERROR);

        // AND - the report is back under its uuid, file for file, and the aside copy is gone with it
        assertThat(snapshot(report))
            .as("files of the report after a failed swap")
            .isEqualTo(filesBefore);
        assertThat(REPORTS_DIR.resolve(reportUuid + SWAP_OLD_SUFFIX))
            .as("directory the report was moved aside to")
            .doesNotExist();
    }

    @Test
    @DisplayName("should put back a report a killed process left aside and then enrich it when the analysis runs again")
    void restoresAReportLeftAsideByAnInterruptedSwap() throws Exception {
        // GIVEN - the state a process killed between the two renames leaves: the report exists only
        // as '<uuid>.ai-old'
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String reportUuid = generate("ai/interrupted-swap", resultUuid);
        final Path report = REPORTS_DIR.resolve(reportUuid);
        final Path aside = REPORTS_DIR.resolve(reportUuid + SWAP_OLD_SUFFIX);
        Files.move(report, aside);

        // WHEN - the analysis runs
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai")).andExpect(status().isAccepted());

        // THEN - it finishes, and the report is back under its uuid with the answers in it
        assertThat(awaitFinished(reportUuid)).as("job status after the recovery").isEqualTo(AiJobStatus.DONE);
        assertThat(testCases(report))
            .as("test cases of the recovered and rebuilt report")
            .contains(objectMapper.readTree(OpenCodeStub.ANALYSIS).path("recommendation").asText());
        assertThat(aside).as("directory the report was left in").doesNotExist();
        assertThat(REPORTS_DIR.resolve(reportUuid + SWAP_TMP_SUFFIX))
            .as("directory the report was rebuilt in")
            .doesNotExist();
    }

    //// PRIVATE ////

    /** Every {@code data/test-cases/*.json} of the report, concatenated. */
    private static String testCases(Path report) throws IOException {
        final StringBuilder text = new StringBuilder();
        try (Stream<Path> files = Files.list(report.resolve("data").resolve("test-cases"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                text.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    /** Relative path of every file of the report, with its size and modification time. */
    private static Map<String, String> snapshot(Path report) throws IOException {
        final Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(report)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(report.relativize(file).toString(),
                    Files.size(file) + " bytes, modified " + Files.getLastModifiedTime(file));
            }
        }
        assertThat(files).as("files of the report '%s'", report).isNotEmpty();
        return files;
    }

    /** {@code POST /api/report} with the analysis; returns the uuid of the created report. */
    private String generate(String path, String resultUuid) throws Exception {
        final String[] segments = path.split("/");
        final String body = """
            {"reportSpec":{"path":["%s","%s"],"executorInfo":{"buildName":"nightly"}},
             "results":["%s"],"deleteResults":true,"aiAnalysis":true}
            """.formatted(segments[0], segments[1], resultUuid);
        final String response = mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("uuid").asText();
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
