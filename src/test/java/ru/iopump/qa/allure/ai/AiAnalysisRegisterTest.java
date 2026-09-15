package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import ru.iopump.qa.allure.repo.JpaReportRepository;
import ru.iopump.qa.allure.service.JpaReportService;
import ru.iopump.qa.allure.service.SystemSettingsService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The status {@code ai-job.json} is born with. Two of the three branches look alike from the
 * outside - both count zero clusters - and only one of them means the analysis is finished: a
 * preparation that blew up knows nothing about the run, while a preparation that found no failures
 * knows there is nothing to ask the model about.
 * <p>
 * A plain unit test on purpose: driving the core into a failure through the whole generation
 * pipeline would take a broken results directory, which the generator would reject first.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiAnalysisService.register")
class AiAnalysisRegisterTest {

    private static final String REPORT_PATH = "ai/register";
    private static final String BASE_URL = "http://localhost:8080/";
    private static final String PREPARATION_FAILURE = "java.io.IOException: results are unreadable";
    private static final int NO_CLUSTERS = 0;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private JpaReportRepository repository;

    @Mock
    private ObjectProvider<JpaReportService> reportService;

    @Test
    @DisplayName("should register the job as error carrying the failure text when the preparation failed")
    void registersAnErrorWhenThePreparationFailed(@TempDir Path work) throws IOException {
        // GIVEN - the offline analysis died, so its zero clusters mean "unknown", not "none"
        final AiAnalysisService.Prepared prepared =
            new AiAnalysisService.Prepared(null, NO_CLUSTERS, PREPARATION_FAILURE);

        // WHEN - the report generated all the same is registered
        final JsonNode job = register(work, prepared);

        // THEN - the job says so, instead of pretending the analysis is over
        assertThat(job.path("status").asText())
            .as("status of a job whose preparation failed")
            .isEqualTo("error");
        assertThat(job.path("error").asText())
            .as("failure text kept for the GET of the job")
            .isEqualTo(PREPARATION_FAILURE);
    }

    @Test
    @DisplayName("should register the job as done when the preparation found no failures to analyse")
    void registersADoneJobWhenThereIsNothingToAnalyse(@TempDir Path work) throws IOException {
        // GIVEN - a successful preparation of a run without a single failure
        final AiAnalysisService.Prepared prepared = new AiAnalysisService.Prepared(null, NO_CLUSTERS, null);

        // WHEN - the report is registered
        final JsonNode job = register(work, prepared);

        // THEN - the job is over before it starts and nothing offers to run the model
        assertThat(job.path("status").asText())
            .as("status of a job with no clusters")
            .isEqualTo("done");
        assertThat(AiJobStatus.of(job.path("status").asText()).restartable())
            .as("a done job is not offered for a run")
            .isFalse();
    }

    //// PRIVATE ////

    /** Registers one report and returns the {@code ai-job.json} written for it. */
    private JsonNode register(Path work, AiAnalysisService.Prepared prepared) throws IOException {
        final Path cacheDir = work.resolve("cache");
        final Path resultDir = work.resolve("results").resolve(UUID.randomUUID().toString());
        Files.createDirectories(resultDir);
        Files.writeString(resultDir.resolve("executor.json"), "{}", StandardCharsets.UTF_8);

        final UUID reportUuid = UUID.randomUUID();
        final AiProperties properties = properties(cacheDir);
        final AiAnalysisService service = new AiAnalysisService(
            properties, settingsOf(properties), repository, reportService, objectMapper);
        try {
            service.register(reportUuid, resultDir, REPORT_PATH, BASE_URL, false, prepared);
        } finally {
            service.stop();
        }
        return objectMapper.readTree(cacheDir.resolve(reportUuid.toString()).resolve("ai-job.json").toFile());
    }

    /** Effective settings with no row behind them: the configuration above is all there is. */
    private static AiSettingsService settingsOf(AiProperties properties) {
        final SystemSettingsService systemSettings = mock(SystemSettingsService.class);
        when(systemSettings.current()).thenReturn(new SystemSettingsService.Snapshot(false, Instant.EPOCH, null));
        return new AiSettingsService(properties, systemSettings);
    }

    /** Defaults everywhere except the cache directory: {@code auto} stays off, so nothing is queued. */
    private static AiProperties properties(Path cacheDir) {
        return new AiProperties(null, null, null, null, null, null, null, null, cacheDir.toString(), null,
            null, null);
    }
}
