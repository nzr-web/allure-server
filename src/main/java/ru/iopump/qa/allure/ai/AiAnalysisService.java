package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 *       then asks {@link JpaReportService} to rebuild the same report in place.</li>
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
     * History the report was first generated with, laid out as the generator reads it
     * ({@code history-in/history/history.json}, ...). The in-place rebuild needs it: by then the
     * report's own {@code history/} already contains this very night.
     */
    private static final String HISTORY_IN = "history-in";
    private static final String HISTORY = "history";
    /** Prefix of the directory {@link #prepare} keeps the history in until the report uuid exists. */
    private static final String PREPARE_PREFIX = "prepare-";
    /** Siblings {@link JpaReportService#regenerateInPlace} swaps a report through. */
    private static final List<String> SWAP_SUFFIXES = List.of(".ai-old", ".ai-tmp");

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
        final Path historyIn = keepHistory(reportPath);
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
            return new Prepared(previousUuid, clusters, null, historyIn);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedPreparation(previousUuid, historyIn, e);
        } catch (IOException | RuntimeException e) {
            return failedPreparation(previousUuid, historyIn, e);
        }
    }

    /**
     * Copies - not moves - the history the coming generation is about to take from the newest report
     * of the path. It has to happen here: the generation moves that {@code history/} away and hands
     * the report it creates a history that already contains this night, which is useless as an input
     * for the rebuild after the analysis. The newest report is chosen exactly as the generation
     * chooses it, which is not the report {@link #findPrevious} returns for the diff.
     *
     * @return {@code <cache-dir>/prepare-<random>/history-in}, or {@code null} when the path has no
     *         report, the report has no history, or the copy failed
     */
    @Nullable
    private Path keepHistory(String reportPath) {
        final Optional<ReportEntity> newest = repository.findByPathOrderByCreatedDateTimeDesc(reportPath).stream()
            .findFirst();
        if (newest.isEmpty()) {
            return null;
        }
        final Path source = reportService.getObject().getReportsDir()
            .resolve(newest.get().getUuid().toString())
            .resolve(HISTORY);
        if (!Files.isDirectory(source)) {
            return null;
        }
        final Path historyIn = cacheDir().resolve(PREPARE_PREFIX + UUID.randomUUID()).resolve(HISTORY_IN);
        try {
            FileUtils.copyDirectory(source.toFile(), historyIn.resolve(HISTORY).toFile());
            return historyIn;
        } catch (IOException e) {
            // The analysis does not need the history, only the rebuild after it does; losing it costs
            // the trend of one report, failing here would cost the report.
            log.warn("Unable to keep the history of '{}', the analysed report will start its trend anew: {}",
                source, e.getMessage());
            FileUtils.deleteQuietly(historyIn.getParent().toFile());
            return null;
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
        keepHistoryUnder(uuid, prepared.historyIn());

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

    /** Moves the history {@link #prepare} kept to {@code <cache-dir>/<uuid>/history-in}. */
    private void keepHistoryUnder(String uuid, @Nullable Path historyIn) {
        if (historyIn == null || !Files.isDirectory(historyIn)) {
            return;
        }
        try {
            moveDirectory(historyIn, historyInOf(uuid));
        } catch (IOException e) {
            log.warn("Unable to keep the history of report '{}', the analysed report will start its trend anew: {}",
                uuid, e.getMessage());
        } finally {
            FileUtils.deleteQuietly(historyIn.getParent().toFile());
        }
    }

    /**
     * Unattended start of the worker. It must wait for the commit of the generation transaction:
     * {@link JpaReportService} is {@code @Transactional}, and the worker thread would not see the
     * report entity before that transaction commits.
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
     * is returned as is, a finished one is returned without rebuilding the report again, and only
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
            regenerate(uuid, job, results);
            job.setResultUuid(uuid);
            finish(uuid, job, withoutAnswer > 0 ? AiJobStatus.PARTIAL : AiJobStatus.DONE, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(uuid, job, AiJobStatus.ERROR, String.valueOf(e));
        } catch (Exception e) {
            log.error("AI analysis job '{}' failed", uuid, e);
            finish(uuid, job, AiJobStatus.ERROR, String.valueOf(e));
        }
    }

    /**
     * Rebuilds the same report from the analysed copy: same uuid, URL and place in the path, so the
     * night is not published twice and the copy stays where the next run looks for its previous run.
     * The history goes in as it was before this night, when {@link #prepare} managed to keep it.
     */
    private void regenerate(String uuid, AiJob job, Path results) throws IOException {
        final Path historyIn = historyInOf(uuid);
        final List<Path> resultDirs = Files.isDirectory(historyIn) ? List.of(results, historyIn) : List.of(results);
        reportService.getObject().regenerateInPlace(UUID.fromString(uuid), resultDirs, job.getBaseUrl());
        log.info("AI analysis of report '{}' published in place ({} of {} cluster(s) answered)",
            uuid, job.getAnswered(), job.getClusters());
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
     * <p>
     * The same age rule removes {@code prepare-*} directories: {@link #register} moves each one under
     * its report, so one that outlived the hour belongs to a generation that failed after
     * {@link #prepare}. And the {@code <uuid>.ai-old} / {@code <uuid>.ai-tmp} siblings an interrupted
     * in-place rebuild leaves in the reports directory - a whole report each, hundreds of megabytes.
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
        for (Path dir : prepareDirs()) {
            if (olderThanMinAge(dir)) {
                FileUtils.deleteQuietly(dir.toFile());
                log.info("AI analysis history '{}' removed: its generation never registered it", dir);
            }
        }
        for (Path dir : swapLeftovers()) {
            if (olderThanMinAge(dir)) {
                FileUtils.deleteQuietly(dir.toFile());
                log.info("Leftover '{}' of an interrupted in-place rebuild removed", dir);
            }
        }
    }

    /**
     * {@code <uuid>.ai-old} and {@code <uuid>.ai-tmp} in the reports directory. Only these two
     * suffixes after a uuid: everything else there belongs to the report server.
     */
    private List<Path> swapLeftovers() {
        final Path dir = reportService.getObject().getReportsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                .filter(path -> isSwapLeftover(path.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isSwapLeftover(String name) {
        for (String suffix : SWAP_SUFFIXES) {
            if (name.endsWith(suffix) && parseUuid(name.substring(0, name.length() - suffix.length())).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private List<Path> prepareDirs() {
        final Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith(PREPARE_PREFIX))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

    private Prepared failedPreparation(String previousUuid, @Nullable Path historyIn, Exception e) {
        log.error("AI analysis preparation failed, the report is generated without it", e);
        return new Prepared(previousUuid, 0, String.valueOf(e), historyIn);
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

    private Path historyInOf(String uuid) {
        return cacheDir().resolve(uuid).resolve(HISTORY_IN);
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

    /**
     * Outcome of the offline phase: what the worker will need and what went wrong, if anything.
     *
     * @param historyIn history kept for the rebuild after the analysis, {@code null} when there is none
     */
    public record Prepared(@Nullable String previousUuid, int clusters, @Nullable String error,
                           @Nullable Path historyIn) {

        /** Without a kept history: the first report of its path, or nothing to keep. */
        public Prepared(@Nullable String previousUuid, int clusters, @Nullable String error) {
            this(previousUuid, clusters, error, null);
        }
    }
}
