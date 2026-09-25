package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.iopump.qa.allure.entity.ReportEntity;
import ru.iopump.qa.allure.entity.UserEntity;
import ru.iopump.qa.allure.repo.JpaReportRepository;
import ru.iopump.qa.allure.repo.UserRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * End-to-end test of the AI analysis over the real generation pipeline: uploaded results are
 * clustered before the report is built, a copy is kept for the model, and the worker rebuilds the
 * same report in place once the model has answered.
 * <p>
 * The model is the only thing stubbed ({@link OpenCodeStub} speaks the OpenCode protocol on
 * loopback); the Allure generator, the database and the filesystem layout are the real ones. The
 * report tab plugin is deliberately absent, so every assertion is made on artefacts that do not
 * need it: {@code ai-analysis.json}, {@code categories.json}, {@code environment.properties},
 * the {@code data/categories.json} the Allure core itself produces and the per-test sections the
 * core writes into {@code data/test-cases/*.json}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ai-analysis-test-db;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.security.require-api-auth=false",
    "basic.auth.enable=false",
    "gg.jte.development-mode=false",
    "gg.jte.use-precompiled-templates=true",
    "allure.results-dir=build/ai-analysis-it/results/",
    "allure.reports.dir=build/ai-analysis-it/reports/",
    "allure-ai.cache-dir=build/ai-analysis-it/cache",
    "allure-ai.parallel=1",
    "allure-ai.timeout-seconds=30",
    "allure-ai.auto=false",
    // '-' disables the scheduled sweep: housekeeping must not fire in the middle of a test.
    "allure-ai.sweep-cron=-"
})
class AiAnalysisIntegrationTest {

    private static final Path WORK_DIR = Paths.get("build", "ai-analysis-it");
    private static final Path RESULTS_DIR = WORK_DIR.resolve("results");
    private static final Path REPORTS_DIR = WORK_DIR.resolve("reports");
    private static final Path CACHE_DIR = WORK_DIR.resolve("cache");

    private static final String API_REPORT = "/api/report";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin";
    private static final String AI_SUMMARY = "ai-analysis.json";
    private static final String CATEGORIES = "categories.json";
    private static final String ENVIRONMENT = "environment.properties";
    private static final String AI_CATEGORY_PREFIX = "[AI] ";
    /** Escaped as a .properties key is: the report overview shows it as "AI Analysis". */
    private static final String AI_ENVIRONMENT_KEY = "AI\\ Analysis";
    /**
     * Heading of the per-test section as it reaches the report. The section's own
     * {@code allure-ai:begin} marker is an HTML comment, which Allure drops from the description.
     */
    private static final String AI_SECTION_MARK = "<b>AI Analysis</b>";
    private static final String SWAP_TMP_SUFFIX = ".ai-tmp";
    private static final String SWAP_OLD_SUFFIX = ".ai-old";
    private static final String HISTORY_IN = "history-in";
    private static final int EXPECTED_CLUSTERS = 2;
    private static final long AWAIT_TIMEOUT_MS = 120_000L;
    /** Past the hour a copy is protected for, so the sweep is allowed to take it. */
    private static final Duration OLDER_THAN_SWEEP_AGE = Duration.ofHours(2);

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
    private UserRepository userRepository;

    @BeforeEach
    void answeringModel() {
        STUB.mode(OpenCodeStub.Mode.ANSWERS);
    }

    @Test
    @DisplayName("should cluster the failures into the report and keep the results for the model when aiAnalysis is requested")
    void enrichesTheReportAndKeepsACopy() throws Exception {
        // GIVEN - one nightly run with two distinct failures
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");

        // WHEN - the report is generated with the analysis and the results are asked to be deleted
        final String reportUuid = generate("ai/enrich", resultUuid, true, false, true);

        // THEN - the copy the model will work on carries the offline analysis
        final Path copy = CACHE_DIR.resolve(reportUuid).resolve("results");
        assertThat(readJson(copy.resolve(AI_SUMMARY)).path("status").asText())
            .as("analysis status in the results copy before the model has run")
            .isEqualTo("pending");
        assertThat(readJson(copy.resolve(AI_SUMMARY)).path("clusters"))
            .as("one cluster per distinct failure")
            .hasSize(EXPECTED_CLUSTERS);
        assertThat(Files.readString(copy.resolve(CATEGORIES), StandardCharsets.UTF_8))
            .as("categories written next to the results")
            .contains(AI_CATEGORY_PREFIX);
        assertThat(Files.readString(copy.resolve(ENVIRONMENT), StandardCharsets.UTF_8))
            .as("environment line of the report overview")
            .contains(AI_ENVIRONMENT_KEY);

        // AND - the generated report itself shows the categories, without any plugin
        assertThat(Files.readString(REPORTS_DIR.resolve(reportUuid).resolve("data").resolve(CATEGORIES),
            StandardCharsets.UTF_8))
            .as("categories inside the generated report")
            .contains(AI_CATEGORY_PREFIX);

        // AND - the uploaded results are gone from the intake directory: they were moved, not copied
        assertThat(RESULTS_DIR.resolve(resultUuid))
            .as("uploaded results after a generation that asked to delete them")
            .doesNotExist();
    }

    @Test
    @DisplayName("should leave the uploaded results in place when the request does not ask to delete them")
    void keepsTheUploadedResultsWhenNotAskedToDeleteThem() throws Exception {
        // GIVEN - one nightly run
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");

        // WHEN - the report is generated with deleteResults=false
        final String reportUuid = generate("ai/keep", resultUuid, false, false, true);

        // THEN - both the original and the copy exist
        assertThat(RESULTS_DIR.resolve(resultUuid))
            .as("uploaded results after a generation that keeps them")
            .isDirectory();
        assertThat(CACHE_DIR.resolve(reportUuid).resolve("results").resolve(AI_SUMMARY))
            .as("the analysis copy is taken all the same")
            .isRegularFile();
    }

    @Test
    @DisplayName("should reject aiAnalysis together with singleFile with 400")
    void rejectsAiAnalysisWithSingleFile() throws Exception {
        // GIVEN - a results directory
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");

        // WHEN - both flags are requested at once
        // THEN - 400: a standalone file cannot carry the tab the flag promises
        mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content("""
                {"reportSpec":{"path":["ai","single"]},"results":["%s"],
                 "deleteResults":false,"singleFile":true,"aiAnalysis":true}
                """.formatted(resultUuid)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should reject aiAnalysis with more than one results directory with 400")
    void rejectsAiAnalysisWithSeveralResults() throws Exception {
        // GIVEN - two results directories
        final String first = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");
        final String second = AllureResultsFixture.write(RESULTS_DIR, 2, "beta");

        // WHEN - both are sent in one request with the analysis
        // THEN - 400: the analysis reads one directory as one run, merging is out of scope
        mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content("""
                {"reportSpec":{"path":["ai","two"]},"results":["%s","%s"],
                 "deleteResults":false,"aiAnalysis":true}
                """.formatted(first, second)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should rebuild the same report in place when the model answers every cluster")
    void rebuildsTheSameReportInPlaceWhenTheModelAnswers() throws Exception {
        // GIVEN - a pending report of its own path, built before the model has said anything
        final String path = "ai/publish";
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String pendingUuid = generate(path, resultUuid, true, false, true);
        final ReportEntity before = reportRepository.findOneByUuid(UUID.fromString(pendingUuid)).orElseThrow();
        assertThat(testCases(pendingUuid))
            .as("test cases of the pending report")
            .contains(AI_SECTION_MARK)
            .doesNotContain(stubAnswer());

        // WHEN - the model is asked to analyse it
        mockMvc.perform(post(API_REPORT + "/" + pendingUuid + "/ai")).andExpect(status().isAccepted());
        final AiJobStatus status = awaitFinished(pendingUuid);

        // THEN - the job is done and points at its own report
        assertThat(status).as("job status after the model answered").isEqualTo(AiJobStatus.DONE);
        assertThat(aiAnalysisService.status(pendingUuid).getResultUuid())
            .as("report that carries the analysis")
            .isEqualTo(pendingUuid);

        // AND - still the only report of its path, with the same identity and a recounted size
        assertThat(reportRepository.findByPath(path))
            .as("reports of this path after the analysis")
            .extracting(ReportEntity::getUuid)
            .containsExactly(UUID.fromString(pendingUuid));
        final ReportEntity after = reportRepository.findOneByUuid(UUID.fromString(pendingUuid)).orElseThrow();
        assertThat(after.getLevel()).as("level of the rebuilt report").isEqualTo(before.getLevel());
        assertThat(after.getUrl()).as("url of the rebuilt report").isEqualTo(before.getUrl());
        assertThat(after.getCreatedDateTime()).as("creation time of the rebuilt report").isEqualTo(before.getCreatedDateTime());
        assertThat(after.getSize())
            .as("size of the rebuilt report")
            .isEqualTo(ReportEntity.sizeKB(REPORTS_DIR.resolve(pendingUuid)))
            .isNotEqualTo(before.getSize());

        // AND - the files under that uuid were rebuilt from the answered copy, and the swap left nothing behind
        assertThat(testCases(pendingUuid))
            .as("test cases of the rebuilt report")
            .contains(AI_SECTION_MARK)
            .contains(stubAnswer());
        assertSwapLeftNothing(pendingUuid);

        // AND - the copy and its job stay under the same uuid and now carry the answers
        assertThat(readJson(CACHE_DIR.resolve(pendingUuid).resolve("results").resolve(AI_SUMMARY))
            .path("status").asText())
            .as("analysis status in the results copy")
            .isEqualTo("done");
        assertThat(CACHE_DIR.resolve(pendingUuid).resolve("ai-job.json"))
            .as("job file of the rebuilt report")
            .isRegularFile();
    }

    @Test
    @DisplayName("should answer 200 and publish nothing when the analysis of a report is already done")
    void doesNotPublishASecondReportWhenTheAnalysisIsDone() throws Exception {
        // GIVEN - a report whose analysis has already been published in place
        final String path = "ai/repeat";
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String pendingUuid = generate(path, resultUuid, true, false, true);
        mockMvc.perform(post(API_REPORT + "/" + pendingUuid + "/ai")).andExpect(status().isAccepted());
        assertThat(awaitFinished(pendingUuid)).as("first run of the model").isEqualTo(AiJobStatus.DONE);

        // WHEN - the same report is submitted for analysis again
        mockMvc.perform(post(API_REPORT + "/" + pendingUuid + "/ai"))
            // THEN - 200, not 202: there is nothing left to do
            .andExpect(status().isOk());

        // AND - the path still has its one report
        assertThat(reportRepository.findByPath(path))
            .as("reports of this path after the repeated request")
            .hasSize(1);
    }

    @Test
    @DisplayName("should keep one trend entry per night when the report of the second night is rebuilt after the analysis")
    void keepsOneTrendEntryPerNightAfterTheRebuild() throws Exception {
        // GIVEN - an ordinary first night of the path, then a second night generated with the analysis
        final String path = "ai/trend";
        final String firstResults = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        generate(path, firstResults, true, false, false);
        final String secondResults = AllureResultsFixture.write(RESULTS_DIR, 2, "alpha", "beta");
        final String reportUuid = generate(path, secondResults, true, false, true);

        // WHEN - the model analyses the second night
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai")).andExpect(status().isAccepted());
        assertThat(awaitFinished(reportUuid)).as("job status after the model answered").isEqualTo(AiJobStatus.DONE);
        assertThat(testCases(reportUuid))
            .as("test cases of the rebuilt report: the rebuild did happen")
            .contains(stubAnswer());

        // THEN - two nights, two entries: the second night is not counted twice
        assertThat(trend(reportUuid))
            .as("history trend of the rebuilt second night")
            .hasSize(2);

        // AND - the history the rebuild took is the one of the first night, kept next to the copy
        assertThat(CACHE_DIR.resolve(reportUuid).resolve(HISTORY_IN).resolve("history").resolve("history-trend.json"))
            .as("history kept for the rebuild, in the layout the generator reads")
            .isRegularFile();
        assertThat(prepareLeftovers())
            .as("temporary history directories of the preparation")
            .isEmpty();
    }

    @Test
    @DisplayName("should rebuild the first report of a path without any kept history")
    void rebuildsTheFirstReportOfAPathWithoutHistory() throws Exception {
        // GIVEN - the very first night of a path, generated with the analysis
        final String path = "ai/first-night";
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String reportUuid = generate(path, resultUuid, true, false, true);
        assertThat(CACHE_DIR.resolve(reportUuid).resolve(HISTORY_IN))
            .as("kept history of the first report of a path")
            .doesNotExist();

        // WHEN - the model analyses it
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai")).andExpect(status().isAccepted());

        // THEN - the rebuild goes through and the trend has this one night
        assertThat(awaitFinished(reportUuid)).as("job status after the model answered").isEqualTo(AiJobStatus.DONE);
        assertThat(testCases(reportUuid))
            .as("test cases of the rebuilt report")
            .contains(stubAnswer());
        assertThat(trend(reportUuid))
            .as("history trend of the first night after the rebuild")
            .hasSize(1);
    }

    @Test
    @DisplayName("should finish the leftover clusters of a partial analysis in the same report on a repeated request")
    void retriesAPartialAnalysisInPlace() throws Exception {
        // GIVEN - a report whose analysis answered one cluster out of two
        final String path = "ai/partial";
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", OpenCodeStub.PARTIAL_UNANSWERED);
        final String reportUuid = generate(path, resultUuid, true, false, true);
        STUB.mode(OpenCodeStub.Mode.PARTIAL);
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai")).andExpect(status().isAccepted());
        assertThat(awaitFinished(reportUuid)).as("job status with one cluster unanswered").isEqualTo(AiJobStatus.PARTIAL);
        assertThat(aiAnalysisService.status(reportUuid).getWithoutAnswer())
            .as("clusters left without an answer")
            .isEqualTo(1);
        assertThat(aiAnalysisService.status(reportUuid).getResultUuid())
            .as("report that carries the partial analysis")
            .isEqualTo(reportUuid);

        // WHEN - the model answers everything and the analysis is requested again
        STUB.mode(OpenCodeStub.Mode.ANSWERS);
        mockMvc.perform(post(API_REPORT + "/" + reportUuid + "/ai")).andExpect(status().isAccepted());

        // THEN - done, in the same report, and still the only one of its path
        assertThat(awaitFinished(reportUuid)).as("job status after the repeated request").isEqualTo(AiJobStatus.DONE);
        assertThat(aiAnalysisService.status(reportUuid).getResultUuid())
            .as("report that carries the finished analysis")
            .isEqualTo(reportUuid);
        assertThat(reportRepository.findByPath(path))
            .as("reports of this path after two runs of the model")
            .extracting(ReportEntity::getUuid)
            .containsExactly(UUID.fromString(reportUuid));
        assertSwapLeftNothing(reportUuid);
    }

    @Test
    @DisplayName("should fail the job and publish nothing when the model answers with unusable text")
    void failsTheJobWhenTheModelAnswersGarbage() throws Exception {
        // GIVEN - a pending report and a model that cannot produce the expected JSON
        final String path = "ai/garbage";
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String pendingUuid = generate(path, resultUuid, true, false, true);
        STUB.mode(OpenCodeStub.Mode.GARBAGE);

        // WHEN - the model is asked to analyse the report
        mockMvc.perform(post(API_REPORT + "/" + pendingUuid + "/ai")).andExpect(status().isAccepted());

        // THEN - the job fails and the pending report stays the only one of its path
        assertThat(awaitFinished(pendingUuid)).as("job status after an unusable answer").isEqualTo(AiJobStatus.ERROR);
        assertThat(reportRepository.findByPath(path))
            .as("reports of this path after a failed analysis")
            .hasSize(1);
    }

    @Test
    @DisplayName("should count a repeated failure as persistent and an unseen one as new against the previous run")
    void comparesTheRunWithThePreviousOne() throws Exception {
        // GIVEN - a first nightly run of this path with two failures, kept as the previous run
        final String path = "ai/previous";
        final String firstResults = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        generate(path, firstResults, true, false, true);

        // WHEN - the next run repeats one failure and brings a new one
        final String secondResults = AllureResultsFixture.write(RESULTS_DIR, 2, "alpha", "gamma");
        final String secondReport = generate(path, secondResults, true, false, true);

        // THEN - the diff separates the two
        final JsonNode previousRun = readJson(CACHE_DIR.resolve(secondReport).resolve("results").resolve(AI_SUMMARY))
            .path("previousRun");
        assertThat(previousRun.path("persistent").asInt())
            .as("failures that were already failing in the previous run")
            .isEqualTo(1);
        assertThat(previousRun.path("new").asInt())
            .as("failures unseen in the previous run")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("should delete an old results copy whose report no longer exists when preparing the next analysis")
    void sweepsOldCopiesWithoutAReport() throws Exception {
        // GIVEN - a leftover copy of a report that is not in the database, older than any generation
        final Path orphan = orphanCopy(OLDER_THAN_SWEEP_AGE);

        // WHEN - any report is generated with the analysis
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");
        generate("ai/sweep", resultUuid, true, false, true);

        // THEN - the leftover is gone
        assertThat(orphan).as("old results copy of a report that no longer exists").doesNotExist();
    }

    @Test
    @DisplayName("should keep a fresh results copy without a report: its generation may still be uncommitted")
    void keepsFreshCopiesWithoutAReport() throws Exception {
        // GIVEN - a copy written moments ago, exactly what a generation in another thread looks like
        // before its transaction commits and its report row becomes visible
        final Path fresh = orphanCopy(Duration.ZERO);

        // WHEN - another generation sweeps the cache while preparing its own analysis
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");
        generate("ai/sweep-fresh", resultUuid, true, false, true);

        // THEN - the copy is untouched: deleting it would take the results out from under a job
        assertThat(fresh.resolve("ai-job.json"))
            .as("job file of a copy younger than the sweep age")
            .isRegularFile();
    }

    @Test
    @DisplayName("should delete old leftovers of an interrupted in-place rebuild and keep fresh ones and the reports")
    void sweepsOldSwapLeftovers() throws Exception {
        // GIVEN - '.ai-old' and '.ai-tmp' siblings past the sweep age, the same written moments ago,
        // and an ordinary report directory as old as the stale ones
        final String report = generate("ai/sweep-swap-report", AllureResultsFixture.write(RESULTS_DIR, 1, "alpha"),
            true, false, false);
        final Path reportDir = REPORTS_DIR.resolve(report);
        Files.setLastModifiedTime(reportDir, FileTime.from(Instant.now().minus(OLDER_THAN_SWEEP_AGE)));
        final Path oldAside = swapLeftover(SWAP_OLD_SUFFIX, OLDER_THAN_SWEEP_AGE);
        final Path oldFresh = swapLeftover(SWAP_TMP_SUFFIX, OLDER_THAN_SWEEP_AGE);
        final Path newAside = swapLeftover(SWAP_OLD_SUFFIX, Duration.ZERO);
        final Path newFresh = swapLeftover(SWAP_TMP_SUFFIX, Duration.ZERO);
        try {
            // WHEN - any report is generated with the analysis
            generate("ai/sweep-swap", AllureResultsFixture.write(RESULTS_DIR, 1, "alpha"), true, false, true);

            // THEN - the stale leftovers are gone
            assertThat(oldAside).as("'.ai-old' older than the sweep age").doesNotExist();
            assertThat(oldFresh).as("'.ai-tmp' older than the sweep age").doesNotExist();

            // AND - the fresh ones, possibly of a rebuild in flight, and the report itself stay
            assertThat(newAside.resolve("index.html")).as("'.ai-old' younger than the sweep age").isRegularFile();
            assertThat(newFresh.resolve("index.html")).as("'.ai-tmp' younger than the sweep age").isRegularFile();
            assertThat(reportDir.resolve("index.html")).as("an ordinary report as old as the stale leftovers").isRegularFile();
        } finally {
            FileUtils.deleteQuietly(newAside.toFile());
            FileUtils.deleteQuietly(newFresh.toFile());
        }
    }

    @Test
    @DisplayName("should delete an old prepare directory left by a failed generation and keep a fresh one")
    void sweepsOldPrepareDirectories() throws Exception {
        // GIVEN - the history a preparation kept for a generation that failed before registering it,
        // one past the sweep age and one written moments ago by a generation still in flight
        final Path old = prepareDirectory(OLDER_THAN_SWEEP_AGE);
        final Path fresh = prepareDirectory(Duration.ZERO);
        try {
            // WHEN - any report is generated with the analysis
            final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");
            generate("ai/sweep-prepare", resultUuid, true, false, true);

            // THEN - the old one is gone, the fresh one is untouched
            assertThat(old).as("prepare directory older than the sweep age").doesNotExist();
            assertThat(fresh.resolve(HISTORY_IN).resolve("history").resolve("history.json"))
                .as("history in a prepare directory younger than the sweep age")
                .isRegularFile();
        } finally {
            // other tests of this class expect no prepare directory to be left in the cache
            FileUtils.deleteQuietly(old.toFile());
            FileUtils.deleteQuietly(fresh.toFile());
        }
    }

    @Test
    @DisplayName("should answer 400 when the report uuid in the analysis request is malformed")
    void rejectsAMalformedUuid() throws Exception {
        // GIVEN - a path variable that is not a uuid at all
        // WHEN - it is submitted for analysis
        // THEN - 400 before anything touches the filesystem
        mockMvc.perform(post(API_REPORT + "/not-a-uuid/ai"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("should finish the job at once and offer no analysis button when the run has no failures")
    void finishesTheJobAndHidesTheButtonWhenThereAreNoFailures() throws Exception {
        // GIVEN - a green run and, for comparison, a run that failed
        final String greenResults = AllureResultsFixture.write(RESULTS_DIR, 1);
        final String greenUuid = generate("ai/green", greenResults, true, false, true);
        final String failedResults = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha");
        final String failedUuid = generate("ai/green-neighbour", failedResults, true, false, true);
        clearTemporaryPassword();

        // THEN - there is nothing to ask the model about, so the job is over before it starts
        assertThat(aiAnalysisService.status(greenUuid).getStatus())
            .as("job status of a run without a single failure")
            .isEqualTo(AiJobStatus.DONE);

        // WHEN - the grid is rendered for a user allowed to mutate
        final String html = mockMvc.perform(get("/app/reports").header(HttpHeaders.AUTHORIZATION, basicAuthHeader()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - the green report carries no analysis form, while its failing neighbour on the very
        // same page does: the button is missing because the job is done, not because nothing renders
        assertThat(html)
            .as("analysis form of the report without failures")
            .doesNotContain(analysisFormAction(greenUuid));
        assertThat(html)
            .as("analysis form of the report with clusters, rendered by the same pass")
            .contains(analysisFormAction(failedUuid));
    }

    @Test
    @DisplayName("should render an analysis form in the reports grid that the server actually accepts")
    void gridOffersAWorkingAnalysisForm() throws Exception {
        // GIVEN - a pending report and an authenticated user allowed to mutate
        final String resultUuid = AllureResultsFixture.write(RESULTS_DIR, 1, "alpha", "beta");
        final String pendingUuid = generate("ai/grid", resultUuid, true, false, true);
        clearTemporaryPassword();
        final String basicAuth = basicAuthHeader();

        // WHEN - the reports page is rendered for that user
        final String html = mockMvc.perform(get("/app/reports").header(HttpHeaders.AUTHORIZATION, basicAuth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // THEN - the row carries a form posting to the analysis endpoint of this very report
        final Matcher form = Pattern.compile("action=\"(/app/reports/" + pendingUuid + "/ai)\"").matcher(html);
        assertThat(form.find()).as("analysis form of report '%s' in the rendered grid", pendingUuid).isTrue();

        // AND - that exact url is served: the button is wired, not decorative
        mockMvc.perform(post(form.group(1)).header(HttpHeaders.AUTHORIZATION, basicAuth).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(awaitFinished(pendingUuid)).as("job started from the grid button").isEqualTo(AiJobStatus.DONE);
    }

    //// PRIVATE ////

    /**
     * A cache directory of a report that is not in the database, aged by touching it back in time.
     *
     * @param age how long ago the copy was last written; {@link Duration#ZERO} is "just now"
     */
    private static Path orphanCopy(Duration age) throws IOException {
        final Path orphan = CACHE_DIR.resolve(UUID.randomUUID().toString());
        Files.createDirectories(orphan.resolve("results"));
        Files.writeString(orphan.resolve("ai-job.json"), "{\"status\":\"pending\"}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(age)));
        return orphan;
    }

    /** A {@code <uuid><suffix>} directory in the reports directory, aged by touching it back in time. */
    private static Path swapLeftover(String suffix, Duration age) throws IOException {
        final Path dir = REPORTS_DIR.resolve(UUID.randomUUID() + suffix);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), "<html></html>", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(age)));
        return dir;
    }

    /** A {@code prepare-*} directory with a kept history, aged by touching it back in time. */
    private static Path prepareDirectory(Duration age) throws IOException {
        final Path dir = CACHE_DIR.resolve("prepare-" + UUID.randomUUID());
        final Path history = dir.resolve(HISTORY_IN).resolve("history");
        Files.createDirectories(history);
        Files.writeString(history.resolve("history.json"), "{}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(age)));
        return dir;
    }

    /** {@code POST /api/report}; returns the uuid of the created report. */
    private String generate(String path, String resultUuid, boolean deleteResults, boolean singleFile,
                            boolean aiAnalysis) throws Exception {
        final String[] segments = path.split("/");
        final String body = """
            {"reportSpec":{"path":["%s","%s"],"executorInfo":{"buildName":"nightly"}},
             "results":["%s"],"deleteResults":%b,"singleFile":%b,"aiAnalysis":%b}
            """.formatted(segments[0], segments[1], resultUuid, deleteResults, singleFile, aiAnalysis);
        final String response = mockMvc.perform(post(API_REPORT).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("uuid").asText();
    }

    /** Every {@code data/test-cases/*.json} of the report, concatenated. */
    private static String testCases(String reportUuid) throws IOException {
        final StringBuilder text = new StringBuilder();
        try (Stream<Path> files = Files.list(REPORTS_DIR.resolve(reportUuid).resolve("data").resolve("test-cases"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                text.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    /** The text the stub model answers with; it can only reach a report through a rebuild. */
    private String stubAnswer() throws IOException {
        return objectMapper.readTree(OpenCodeStub.ANALYSIS).path("recommendation").asText();
    }

    /** Entries of {@code history/history-trend.json} of the report. */
    private JsonNode trend(String reportUuid) throws IOException {
        return readJson(REPORTS_DIR.resolve(reportUuid).resolve("history").resolve("history-trend.json"));
    }

    private static void assertSwapLeftNothing(String reportUuid) {
        assertThat(REPORTS_DIR.resolve(reportUuid + SWAP_TMP_SUFFIX))
            .as("directory the report was rebuilt in")
            .doesNotExist();
        assertThat(REPORTS_DIR.resolve(reportUuid + SWAP_OLD_SUFFIX))
            .as("directory the previous build was moved to")
            .doesNotExist();
    }

    /** Directories {@code prepare-*} left in the cache: each should have moved under its report. */
    private static List<Path> prepareLeftovers() throws IOException {
        try (Stream<Path> children = Files.list(CACHE_DIR)) {
            return children.filter(p -> p.getFileName().toString().startsWith("prepare-")).toList();
        }
    }

    /** The {@code action} the grid renders for the analysis button of one report. */
    private static String analysisFormAction(String reportUuid) {
        return "action=\"/app/reports/" + reportUuid + "/ai\"";
    }

    /** Blocks until the worker leaves the in-flight states, or the timeout expires. */
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

    private JsonNode readJson(Path file) throws IOException {
        return objectMapper.readTree(file.toFile());
    }

    private void clearTemporaryPassword() {
        final UserEntity user = userRepository.findByUsername(ADMIN_USER).orElseThrow();
        user.setPasswordTemporary(false);
        userRepository.save(user);
    }

    private static String basicAuthHeader() {
        return "Basic " + Base64.getEncoder()
            .encodeToString((ADMIN_USER + ":" + ADMIN_PASS).getBytes(StandardCharsets.UTF_8));
    }
}
