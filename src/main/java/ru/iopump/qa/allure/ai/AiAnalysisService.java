package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.entity.ExecutorInfo;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import ru.iopump.qa.allure.entity.ReportEntity;
import ru.iopump.qa.allure.helper.ExecutorCiPlugin;
import ru.iopump.qa.allure.repo.JpaReportRepository;
import ru.iopump.qa.allure.service.JpaReportService;
import ru.vtb.at.allureai.EnrichOutcome;
import ru.vtb.at.allureai.EnrichRequest;
import ru.vtb.at.allureai.EnrichRunner;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Owns everything the AI analysis needs on top of a normal report generation.
 * <p>
 * Two phases, deliberately split so the model never blocks {@code POST /api/report}:
 * <ol>
 *   <li>{@link #prepare(Path, String)} runs the allure-ai core <em>without</em> the model over the
 *       uploaded results (seconds): clusters, the diff against the previous run, {@code [AI]}
 *       categories, per-test sections and {@code ai-analysis.json} with status {@code pending}.
 *       Whatever it writes is picked up by the very generation that follows.</li>
 *   <li>{@link #enqueue(String, String)} hands the report over to a single-threaded worker that runs
 *       the core <em>with</em> the model over the copy kept in {@link AiProperties#cacheDir()} and
 *       then asks {@link JpaReportService} for a fresh version of the same report path.</li>
 * </ol>
 * The copy is what makes the second phase possible at all: the generation request usually asks for
 * the results to be deleted, and the worker starts long after they would be gone.
 * <p>
 * {@link JpaReportService} is taken as an {@link ObjectProvider} because the report service needs
 * this bean as well; resolving it lazily inside the worker breaks the construction-time cycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiAnalysisService {

    private static final String RESULTS = "results";
    private static final String JOB_FILE = "ai-job.json";

    /**
     * A copy younger than this is kept even when no report row matches it. {@link #register} writes
     * {@code <cache-dir>/<uuid>} while the generating transaction is still open, so until that
     * transaction commits the report is invisible to every other thread - and a sweep running at
     * that moment (the scheduled one, or the one a parallel generation triggers from
     * {@link #prepare}) would see the copy of a perfectly healthy job as an orphan and delete the
     * results the model is about to read. An hour is far longer than any generation takes and still
     * removes the leftovers of a deleted report within a day.
     */
    private static final Duration MIN_AGE_BEFORE_SWEEP = Duration.ofHours(1);

    private final AiProperties properties;
    private final AiSettingsService settings;
    private final JpaReportRepository repository;
    private final ObjectProvider<JpaReportService> reportService;
    private final ObjectMapper objectMapper;

    /** Cache of {@code ai-job.json} by report uuid; the file on disk stays authoritative. */
    private final Map<String, AiJob> jobs = new ConcurrentHashMap<>();

    /** One thread on purpose: two models talking to OpenCode at once buys nothing here. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "allure-ai-worker");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Read point 1 of the effective settings: the generation asks before it prepares anything. The
     * admin panel wins over {@code allure-ai.enabled}, in both directions.
     */
    public boolean isEnabled() {
        return settings.effective().enabled().value();
    }

    /**
     * Re-reads the job files after a restart. A job that was {@code queued} or {@code running} when
     * the process died is marked {@code error} and is NOT resubmitted: a restart loop must not keep
     * firing the model unattended.
     */
    @PostConstruct
    void restore() {
        for (String uuid : jobUuids()) {
            final AiJob job = readJob(uuid);
            if (job == null) {
                continue;
            }
            if (job.getStatus() == AiJobStatus.QUEUED || job.getStatus() == AiJobStatus.RUNNING) {
                job.setStatus(AiJobStatus.ERROR);
                job.setError("interrupted by a server restart");
                job.setFinishedAt(now());
                writeJob(uuid, job);
                log.warn("AI analysis job '{}' was interrupted by a restart and is marked as failed", uuid);
            }
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    //region Phase 1 - preparation, inside the generation request

    /**
     * Runs the offline part of the analysis over the uploaded results and resolves the previous run.
     * Never throws: a broken analysis must not cost the user their report, so the failure text is
     * carried in {@link Prepared#error()} and the generation continues without AI content.
     *
     * @param resultDir  the uploaded, not yet generated results directory
     * @param reportPath logical report path the new report will belong to
     */
    public Prepared prepare(Path resultDir, String reportPath) {
        sweep();
        final String previousUuid = findPrevious(reportPath).orElse(null);
        final EnrichRequest.Builder request = EnrichRequest.builder(resultDir)
            .llm(false)
            .quiet(true)
            .out(logStream());
        if (previousUuid != null) {
            request.previous(resultsOf(previousUuid));
        }
        try {
            final EnrichOutcome outcome = EnrichRunner.run(request.build());
            final int clusters = outcome.getResult().getClusters().size();
            log.info("AI analysis prepared for '{}': {} cluster(s), previous report '{}'",
                reportPath, clusters, previousUuid);
            return new Prepared(previousUuid, clusters, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedPreparation(previousUuid, e);
        } catch (IOException | RuntimeException e) {
            return failedPreparation(previousUuid, e);
        }
    }

    /**
     * Keeps the results the worker will need and records the job. The originals are moved when the
     * caller asked for them to be deleted (one volume, so a rename - copying hundreds of megabytes
     * would be paid on every nightly run) and copied otherwise.
     *
     * @param reportUuid  uuid of the report just generated from these results
     * @param moveResults {@code true} when the request asked to delete the results afterwards
     */
    public void register(UUID reportUuid,
                         Path resultDir,
                         String reportPath,
                         String baseUrl,
                         boolean moveResults,
                         Prepared prepared) throws IOException {
        final String uuid = reportUuid.toString();
        final Path target = resultsOf(uuid);
        Files.createDirectories(target.getParent());
        if (moveResults) {
            moveDirectory(resultDir, target);
        } else {
            FileUtils.copyDirectory(resultDir.toFile(), target.toFile());
        }

        final AiJob job = new AiJob();
        // A failed preparation counted no clusters either, but that zero means "we do not know", not
        // "there is nothing to analyse": reporting it as done would hide the failure behind a
        // finished-looking job. No clusters after a successful preparation is a real done - the job
        // is over before it starts and the report shows an empty AI summary instead of a button that
        // has nothing to do.
        job.setStatus(prepared.error() != null
            ? AiJobStatus.ERROR
            : prepared.clusters() == 0 ? AiJobStatus.DONE : AiJobStatus.PENDING);
        job.setReportPath(reportPath);
        job.setPreviousUuid(prepared.previousUuid());
        job.setBaseUrl(baseUrl);
        job.setClusters(prepared.clusters());
        job.setCreatedAt(now());
        job.setError(prepared.error());
        writeJob(uuid, job);
        log.info("AI analysis job '{}' registered with status '{}'", uuid, job.getStatus().json());
        // Read point 2: still inside the generation transaction, so the job registered by this
        // request obeys the 'auto' setting as it is now, not as it was when the server started.
        if (settings.effective().auto().value() && job.getStatus() == AiJobStatus.PENDING) {
            startAfterCommit(uuid, baseUrl);
        }
    }

    /**
     * Unattended start of the worker. It must wait for the commit of the generation transaction:
     * {@link JpaReportService} is {@code @Transactional}, and the worker thread would not see the
     * report entity - nor find it as the latest of its path - before that transaction commits.
     */
    private void startAfterCommit(String uuid, String baseUrl) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueue(uuid, baseUrl);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                enqueue(uuid, baseUrl);
            }
        });
    }

    //endregion

    //region Phase 2 - the worker

    /**
     * Accepts a report for analysis. Repeats are cheap on purpose: an already queued or running job
     * is returned as is, a finished one is returned without creating yet another report, and only
     * {@code pending}, {@code partial} and {@code error} actually start a run.
     *
     * @param baseUrl absolute base url of the calling request; the worker has no request to derive
     *                one from and report links must stay absolute
     */
    public synchronized AiJob enqueue(String uuid, String baseUrl) {
        if (!isEnabled()) {
            // Switched off in the admin panel (or in the configuration) after this report was
            // generated: the copy is still there, but nobody may start the model over it.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI analysis is disabled");
        }
        final AiJob job = readJob(uuid);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No AI analysis for report '" + uuid + "': it was generated without 'aiAnalysis'");
        }
        if (!job.getStatus().restartable()) {
            return job;
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            job.setBaseUrl(baseUrl);
        }
        job.setStatus(AiJobStatus.QUEUED);
        job.setError(null);
        job.setResultUuid(null);
        job.setFinishedAt(null);
        writeJob(uuid, job);
        worker.submit(() -> analyse(uuid));
        log.info("AI analysis job '{}' queued", uuid);
        return job;
    }

    /** Current state of the job, {@code none} when this report has no result copy. */
    public AiJob status(String uuid) {
        final AiJob job = readJob(uuid);
        if (job != null) {
            return job;
        }
        final AiJob none = new AiJob();
        none.setStatus(AiJobStatus.NONE);
        return none;
    }

    /**
     * Status of every known job in one pass over the cache directory - the reports grid needs a
     * badge per row and must not read a file per row.
     */
    public Map<String, AiJobStatus> statuses() {
        final Map<String, AiJobStatus> result = new LinkedHashMap<>();
        for (String uuid : jobUuids()) {
            final AiJob job = readJob(uuid);
            if (job != null) {
                result.put(uuid, job.getStatus());
            }
        }
        return result;
    }

    private void analyse(String uuid) {
        final AiJob job = readJob(uuid);
        if (job == null) {
            return;
        }
        job.setStatus(AiJobStatus.RUNNING);
        job.setStartedAt(now());
        writeJob(uuid, job);
        try {
            final Path results = resultsOf(uuid);
            // Read point 3, and the only one in the worker thread: one snapshot for the whole run.
            // A job that is already going keeps the settings it started with - changing the model
            // halfway through would analyse one half of the clusters with one model and the other
            // half with another.
            final AiSettingsService.Effective effective = settings.effective();
            final EnrichOutcome outcome = EnrichRunner.run(llmRequest(results, job, effective).build());
            final int clusters = outcome.getResult().getClusters().size();
            final int withoutAnswer = Math.max(outcome.getClustersWithoutAnswer(), 0);
            job.setClusters(clusters);
            job.setWithoutAnswer(withoutAnswer);
            job.setAnswered(clusters - withoutAnswer);

            if ("error".equals(outcome.getResult().getStatus())) {
                // Not a single answer: regenerating would only republish the same pending report.
                finish(uuid, job, AiJobStatus.ERROR, "the model answered for none of the clusters");
                return;
            }
            if (!isLatestOfPath(uuid, job.getReportPath())) {
                // Someone published a newer report for this path while the model was thinking. The
                // enriched copy stays: it is the previous run for whatever comes next.
                finish(uuid, job, AiJobStatus.DONE, "report not regenerated: a newer report exists for this path");
                return;
            }
            job.setResultUuid(regenerate(uuid, job, results, withoutAnswer));
            finish(uuid, job, AiJobStatus.DONE, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(uuid, job, AiJobStatus.ERROR, String.valueOf(e));
        } catch (Exception e) {
            log.error("AI analysis job '{}' failed", uuid, e);
            finish(uuid, job, AiJobStatus.ERROR, String.valueOf(e));
        }
    }

    /**
     * Publishes a new version of the same report path from the analysed copy and moves the copy
     * under the new uuid, so the next run finds it as its previous run and the button disappears
     * from the old row.
     *
     * @return uuid of the report that now carries the analysis
     */
    private String regenerate(String uuid, AiJob job, Path results, int withoutAnswer) throws IOException {
        final ReportEntity created = reportService.getObject().generate(
            job.getReportPath(),
            List.of(results),
            false,
            executorInfo(results),
            job.getBaseUrl(),
            false
        );
        final String newUuid = created.getUuid().toString();
        Files.createDirectories(cacheDir().resolve(newUuid));
        moveDirectory(results, resultsOf(newUuid));

        final AiJob published = new AiJob();
        published.setStatus(withoutAnswer > 0 ? AiJobStatus.PARTIAL : AiJobStatus.DONE);
        published.setReportPath(job.getReportPath());
        published.setPreviousUuid(job.getPreviousUuid());
        published.setBaseUrl(job.getBaseUrl());
        published.setClusters(job.getClusters());
        published.setAnswered(job.getAnswered());
        published.setWithoutAnswer(withoutAnswer);
        published.setCreatedAt(now());
        published.setFinishedAt(now());
        writeJob(newUuid, published);
        log.info("AI analysis of report '{}' published as report '{}' ({} of {} cluster(s) answered)",
            uuid, newUuid, published.getAnswered(), published.getClusters());
        return newUuid;
    }

    private EnrichRequest.Builder llmRequest(Path results, AiJob job, AiSettingsService.Effective effective) {
        final EnrichRequest.Builder request = EnrichRequest.builder(results)
            .llm(true)
            .quiet(true)
            .opencodeUrl(effective.opencodeUrl().value())
            .provider(effective.provider().value())
            .model(effective.model().value())
            .agent(effective.agent().value())
            .parallel(effective.parallel().value())
            .timeoutSeconds(effective.timeoutSeconds().value())
            .systemPrompt(effective.systemPrompt().value())
            .promptNotes(effective.promptNotes().value())
            .opencodeUsername(effective.opencodeUsername().value())
            .opencodePassword(effective.opencodePassword().value())
            .out(logStream());
        final String previousUuid = job.getPreviousUuid();
        if (previousUuid != null && Files.isDirectory(resultsOf(previousUuid))) {
            request.previous(resultsOf(previousUuid));
        }
        return request;
    }

    //endregion

    //region Housekeeping

    /**
     * Drops result copies whose report is gone. Reports disappear in a dozen places (scheduled
     * clean-up, single and bulk delete, history trimming on every generation), so the copies are
     * collected here instead of hooking into each of them. Copies younger than
     * {@link #MIN_AGE_BEFORE_SWEEP} are left alone whatever the database says.
     */
    @Scheduled(cron = "${allure-ai.sweep-cron:0 30 3 * * *}")
    public void sweep() {
        for (String uuid : jobUuids()) {
            final Path dir = cacheDir().resolve(uuid);
            final Optional<ReportEntity> entity = parseUuid(uuid).flatMap(repository::findOneByUuid);
            if (entity.isEmpty() && olderThanMinAge(dir)) {
                FileUtils.deleteQuietly(dir.toFile());
                jobs.remove(uuid);
                log.info("AI analysis copy '{}' removed: no such report anymore", uuid);
            }
        }
    }

    /** {@code false} also when the age cannot be read: an unreadable copy is kept, not deleted. */
    private static boolean olderThanMinAge(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toInstant()
                .isBefore(Instant.now().minus(MIN_AGE_BEFORE_SWEEP));
        } catch (IOException e) {
            log.warn("Unable to read the age of '{}', keeping it: {}", dir, e.getMessage());
            return false;
        }
    }

    //endregion

    //region Private

    private Prepared failedPreparation(String previousUuid, Exception e) {
        log.error("AI analysis preparation failed, the report is generated without it", e);
        return new Prepared(previousUuid, 0, String.valueOf(e));
    }

    private void finish(String uuid, AiJob job, AiJobStatus status, String error) {
        job.setStatus(status);
        job.setError(error);
        job.setFinishedAt(now());
        writeJob(uuid, job);
        log.info("AI analysis job '{}' finished with status '{}'{}",
            uuid, status.json(), error == null ? "" : ": " + error);
    }

    /** The previous run for the diff: the newest report of this path that still has a copy. */
    private Optional<String> findPrevious(String reportPath) {
        return repository.findByPathOrderByCreatedDateTimeDesc(reportPath).stream()
            .map(entity -> entity.getUuid().toString())
            .filter(uuid -> Files.isDirectory(resultsOf(uuid)))
            .findFirst();
    }

    private boolean isLatestOfPath(String uuid, String reportPath) {
        return repository.findByPathOrderByCreatedDateTimeDesc(reportPath).stream()
            .findFirst()
            .map(entity -> uuid.equals(entity.getUuid().toString()))
            .orElse(false);
    }

    @Nullable
    private ExecutorInfo executorInfo(Path results) {
        final Path file = results.resolve(ExecutorCiPlugin.JSON_FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), ExecutorInfo.class);
        } catch (IOException e) {
            log.warn("Unable to read '{}': {}", file, e.getMessage());
            return null;
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target);
        } catch (IOException e) {
            // Different volumes (a bind-mounted results dir, for instance): fall back to copy+delete.
            log.debug("Rename of '{}' to '{}' failed, copying instead: {}", source, target, e.getMessage());
            FileUtils.moveDirectory(source.toFile(), target.toFile());
        }
    }

    private Path cacheDir() {
        return Paths.get(properties.cacheDir());
    }

    private Path resultsOf(String uuid) {
        return cacheDir().resolve(uuid).resolve(RESULTS);
    }

    private Path jobFile(String uuid) {
        return cacheDir().resolve(uuid).resolve(JOB_FILE);
    }

    private List<String> jobUuids() {
        final Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> parseUuid(name).isPresent())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nullable
    private AiJob readJob(String uuid) {
        final Path file = jobFile(uuid);
        if (!Files.isRegularFile(file)) {
            jobs.remove(uuid);
            return null;
        }
        final AiJob cached = jobs.get(uuid);
        if (cached != null) {
            return cached;
        }
        try {
            final AiJob job = objectMapper.readValue(file.toFile(), AiJob.class);
            jobs.put(uuid, job);
            return job;
        } catch (IOException e) {
            log.error("Unable to read '{}'", file, e);
            return null;
        }
    }

    private void writeJob(String uuid, AiJob job) {
        jobs.put(uuid, job);
        final Path file = jobFile(uuid);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), job);
        } catch (IOException e) {
            log.error("Unable to write '{}'", file, e);
        }
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static PrintStream logStream() {
        return AiLog.printStream();
    }

    private static String now() {
        return Instant.now().toString();
    }

    //endregion

    /** Outcome of the offline phase: what the worker will need and what went wrong, if anything. */
    public record Prepared(@Nullable String previousUuid, int clusters, @Nullable String error) {
    }
}
