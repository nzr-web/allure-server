package ru.iopump.qa.allure.service;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.qameta.allure.entity.ExecutorInfo;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.iopump.qa.allure.ai.AiAnalysisService;
import ru.iopump.qa.allure.config.BrandingService;
import ru.iopump.qa.allure.entity.ReportEntity;
import ru.iopump.qa.allure.helper.AllureReportGenerator;
import ru.iopump.qa.allure.helper.ServeRedirectHelper;
import ru.iopump.qa.allure.properties.AllureProperties;
import ru.iopump.qa.allure.repo.JpaReportRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Optional.ofNullable;
import static org.apache.commons.io.FileUtils.deleteQuietly;
import static ru.iopump.qa.allure.helper.ExecutorCiPlugin.JSON_FILE_NAME;
import static ru.iopump.qa.allure.helper.Util.join;
import static ru.iopump.qa.allure.service.PathUtil.str;

@Component
@Slf4j
@Transactional
public class JpaReportService {

    @Getter
    private final Path reportsDir;
    private final AllureProperties cfg;
    private final ObjectMapper objectMapper;
    private final AllureReportGenerator reportGenerator;
    private final ServeRedirectHelper redirection;
    private final JpaReportRepository repository;
    private final ResultService resultService;
    private final BrandingService branding;

    private final AtomicBoolean init = new AtomicBoolean();

    /** Suffixes of the two sibling directories {@link #regenerateInPlace} swaps the report through. */
    private static final String IN_PLACE_TMP_SUFFIX = ".ai-tmp";
    private static final String IN_PLACE_OLD_SUFFIX = ".ai-old";

    /**
     * Optional AI-analysis add-on. Injected through a setter and not the constructor: the add-on
     * needs this service back (its worker regenerates the report in place), and setter
     * injection resolves that cycle without changing the existing constructor contract.
     */
    private AiAnalysisService aiAnalysisService;

    @Autowired(required = false)
    public void setAiAnalysisService(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    public JpaReportService(AllureProperties cfg,
                            ObjectMapper objectMapper,
                            JpaReportRepository repository,
                            AllureReportGenerator reportGenerator,
                            ServeRedirectHelper redirection,
                            ResultService resultService,
                            BrandingService branding
    ) {
        this.reportsDir = cfg.reports().dirPath();
        this.cfg = cfg;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.reportGenerator = reportGenerator;
        this.redirection = redirection;
        this.resultService = resultService;
        this.branding = branding;
    }

    @PostConstruct
    protected void initRedirection() {
        repository.findByActiveTrue().forEach(
            e -> redirection.mapRequestTo(join(cfg.reports().path(), e.getPath()), reportsDir.resolve(e.getUuid().toString()).toString())
        );
    }

    public Collection<ReportEntity> clearAllHistory() {
        final Collection<ReportEntity> entitiesActive = repository.findByActiveTrue();
        final Collection<ReportEntity> entitiesInactive = repository.deleteByActiveFalse();

        // delete active history
        entitiesActive
            .forEach(e -> deleteQuietly(reportsDir.resolve(e.getUuid().toString()).resolve("history").toFile()));

        // delete active history
        entitiesInactive
            .forEach(e -> deleteQuietly(reportsDir.resolve(e.getUuid().toString()).toFile()));

        return entitiesInactive;
    }

    public void internalDeleteByUUID(UUID uuid) throws IOException {
        repository.deleteById(uuid);
        FileUtils.deleteDirectory(reportsDir.resolve(uuid.toString()).toFile());
    }

    /**
     * Delete a single report by UUID. Fails with HTTP 404 if the report does not exist.
     *
     * @param uuid report UUID as string (validated by caller)
     * @return the deleted entity
     * @throws ResponseStatusException 404 if the report is not found
     * @throws IOException             if the report directory cannot be removed
     */
    public ReportEntity deleteByUuid(@NonNull String uuid) throws IOException {
        final UUID id = UUID.fromString(uuid);
        final ReportEntity entity = repository.findOneByUuid(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report '" + uuid + "' not found"));
        repository.deleteById(id);
        FileUtils.deleteDirectory(reportsDir.resolve(id.toString()).toFile());
        log.info("Report '{}' deleted", id);
        return entity;
    }

    public Collection<ReportEntity> deleteAll() throws IOException {
        var res = getAll();
        repository.deleteAll();
        FileUtils.deleteDirectory(reportsDir.toFile());
        return res;
    }

    public Collection<ReportEntity> deleteAllOlderThanDate(LocalDateTime date) {
        final Collection<ReportEntity> res = repository.findAllByCreatedDateTimeIsBefore(date);
        res.forEach(e -> {
            repository.deleteById(e.getUuid());
            deleteQuietly(reportsDir.resolve(e.getUuid().toString()).toFile());
        });
        return res;
    }

    public Collection<ReportEntity> getAll() {
        return repository.findAll(Sort.by("createdDateTime").descending());
    }

    /**
     * On-disk size (bytes) for a legacy report whose persisted {@link ReportEntity#getSize()} is
     * unset (zero). Computed once via a directory walk and backfilled so subsequent renders read
     * the persisted value instead of walking the report directory again. Callers with a non-zero
     * persisted size should use it directly and never reach here.
     *
     * @param uuid report UUID as string
     * @return size in bytes, or 0 when the report or its directory is gone
     */
    public long backfillReportSize(@NonNull String uuid) {
        final UUID id = UUID.fromString(uuid);
        return repository.findOneByUuid(id)
            .map(entity -> {
                final long computedKb = ReportEntity.sizeKB(reportsDir.resolve(id.toString()));
                if (computedKb > 0L) {
                    entity.setSize(computedKb);
                    repository.saveAndFlush(entity);
                }
                return computedKb * 1024L;
            })
            .orElse(0L);
    }

    @SneakyThrows
    public ReportEntity uploadReport(@NonNull String reportPath,
                                     @NonNull InputStream archiveInputStream,
                                     @Nullable ExecutorInfo executorInfo,
                                     String baseUrl) {

        // New report destination and entity — unzip pre-built report archive into the reports dir
        // (uploadReport consumes an already-generated Allure report, not raw results).
        final Path destination = resultService.unzipAndStore(archiveInputStream, reportsDir);
        final UUID uuid = UUID.fromString(destination.getFileName().toString());
        final boolean hasIndex;
        try (var paths = Files.list(destination)) {
            hasIndex = paths.anyMatch(path -> path.getFileName().toString().equals("index.html"));
        }
        Preconditions.checkArgument(hasIndex, "Uploaded archive is not an Allure Report");

        // Uploaded reports skip generation, so branding is applied here to match generated reports
        // instead of waiting for the next startup sweep. Idempotent (marker-file guarded).
        branding.applyBranding(destination);

        // Find prev report if present
        final Optional<ReportEntity> prevEntity = repository.findByPathOrderByCreatedDateTimeDesc(reportPath)
            .stream()
            .findFirst();

        // Add CI executor information
        var safeExecutorInfo = addExecutionInfo(
            destination,
            executorInfo,
            baseUrl + str(reportsDir.resolve(uuid.toString())) + "/index.html",
            uuid
        );

        log.info("Report '{}' loaded", destination);

        // New report entity
        final ReportEntity newEntity = ReportEntity.builder()
            .uuid(uuid)
            .path(reportPath)
            .createdDateTime(LocalDateTime.now(ZoneOffset.UTC))
            .url(join(baseUrl, cfg.reports().dir(), uuid.toString()) + "/")
            .level(prevEntity.map(e -> e.getLevel() + 1).orElse(0L))
            .active(true)
            .size(ReportEntity.sizeKB(destination))
            .buildUrl(
                // Взять Build Url
                ofNullable(safeExecutorInfo.getBuildUrl())
                    // Or Build Name
                    .or(() -> ofNullable(safeExecutorInfo.getBuildName()))
                    // Or Executor Name
                    .or(() -> ofNullable(safeExecutorInfo.getName()))
                    // Or Executor Type
                    .orElse(safeExecutorInfo.getType())
            )
            .build();

        // Add request mapping
        redirection.mapRequestTo(newEntity.getPath(), reportsDir.resolve(uuid.toString()).toString());

        // Persist
        handleMaxHistory(newEntity);
        repository.saveAndFlush(newEntity);

        // Disable prev report
        prevEntity.ifPresent(e -> e.setActive(false));

        return newEntity;
    }

    public ReportEntity generate(@NonNull String reportPath,
                                 @NonNull List<Path> resultDirs,
                                 boolean clearResults,
                                 @Nullable ExecutorInfo executorInfo,
                                 String baseUrl
    ) throws IOException {
        return generate(reportPath, resultDirs, clearResults, executorInfo, baseUrl, false);
    }

    public ReportEntity generate(@NonNull String reportPath,
                                 @NonNull List<Path> resultDirs,
                                 boolean clearResults,
                                 @Nullable ExecutorInfo executorInfo,
                                 String baseUrl,
                                 boolean singleFile
    ) throws IOException {
        // Preconditions
        Preconditions.checkArgument(!resultDirs.isEmpty());
        resultDirs.forEach(i -> Preconditions.checkArgument(Files.exists(i), "Result '%s' doesn't exist", i));

        // New report destination and entity
        final UUID uuid = UUID.randomUUID();

        // Find prev report if present
        final Optional<ReportEntity> prevEntity = repository.findByPathOrderByCreatedDateTimeDesc(reportPath)
            .stream()
            .findFirst();

        // New uuid directory
        final Path destination = reportsDir.resolve(uuid.toString());

        // Copy history from prev report
        final Optional<Path> historyO = prevEntity
            .flatMap(e -> copyHistory(reportsDir.resolve(e.getUuid().toString()), uuid.toString()))
            .or(Optional::empty);

        // Add CI executor information
        var safeExecutorInfo = addExecutionInfo(
            resultDirs.get(0),
            executorInfo,
            baseUrl + str(reportsDir.resolve(uuid.toString())) + "/index.html",
            uuid
        );

        var reportUrl = join(baseUrl, cfg.reports().dir(), uuid.toString()) + "/";
        try {
            // Add history to results if exists
            final List<Path> resultDirsToGenerate = historyO
                .map(history -> (List<Path>) ImmutableList.<Path>builder().addAll(resultDirs).add(history).build())
                .orElse(resultDirs);

            // Generate new report with history
            reportGenerator.generate(destination, resultDirsToGenerate, reportUrl, singleFile);

            log.info("Report '{}' generated according to results '{}'", destination, resultDirsToGenerate);
        } finally {

            // Delete tmp history
            historyO.ifPresent(h -> deleteQuietly(h.toFile()));
            if (clearResults) {
                resultDirs.forEach(r -> deleteQuietly(r.toFile()));
            }
        }

        // New report entity
        final ReportEntity newEntity = ReportEntity.builder()
            .uuid(uuid)
            .path(reportPath)
            .createdDateTime(LocalDateTime.now(ZoneOffset.UTC))
            .url(reportUrl)
            .level(prevEntity.map(e -> e.getLevel() + 1).orElse(0L))
            .active(true)
            .size(ReportEntity.sizeKB(destination))
            .buildUrl(
                // Взять Build Url
                ofNullable(safeExecutorInfo.getBuildUrl())
                    // Or Build Name
                    .or(() -> ofNullable(safeExecutorInfo.getBuildName()))
                    // Or Executor Name
                    .or(() -> ofNullable(safeExecutorInfo.getName()))
                    // Or Executor Type
                    .orElse(safeExecutorInfo.getType())
            )
            .build();

        // Add request mapping
        redirection.mapRequestTo(newEntity.getPath(), reportsDir.resolve(uuid.toString()).toString());

        // Persist
        handleMaxHistory(newEntity);
        repository.saveAndFlush(newEntity);

        // Disable prev report
        prevEntity.ifPresent(e -> e.setActive(false));

        return newEntity;
    }

    /**
     * Same generation, plus the AI analysis of the failures.
     * <p>
     * The offline part of the analysis runs BEFORE the generator, over the very results the report
     * is about to be built from, so its output (clusters, {@code [AI]} categories, per-test sections,
     * {@code ai-analysis.json} with status {@code pending}) is part of the report from the first
     * second. The model is deliberately NOT invoked here - it would hold the HTTP request open for
     * minutes; it runs later, from {@code POST /api/report/{uuid}/ai}, over the copy of the results
     * this method keeps.
     * <p>
     * {@code clearResults} therefore stops meaning "delete the results after generation" and starts
     * meaning "the caller does not need them anymore": they are moved into the analysis cache
     * instead of being deleted, because the model still has to read them.
     *
     * @param aiAnalysis when false - or when the add-on is absent or disabled - this is exactly the
     *                   six-argument generation
     * @throws ResponseStatusException 400 for the two combinations the analysis cannot serve
     */
    public ReportEntity generate(@NonNull String reportPath,
                                 @NonNull List<Path> resultDirs,
                                 boolean clearResults,
                                 @Nullable ExecutorInfo executorInfo,
                                 String baseUrl,
                                 boolean singleFile,
                                 boolean aiAnalysis
    ) throws IOException {
        if (!aiAnalysis || aiAnalysisService == null || !aiAnalysisService.isEnabled()) {
            if (aiAnalysis && aiAnalysisService != null) {
                // Asked for and switched off: without this line the report simply comes back without
                // any AI content and the caller has nothing to look at to find out why.
                log.info("AI analysis requested for {} but disabled in settings/configuration, skipped", reportPath);
            }
            return generate(reportPath, resultDirs, clearResults, executorInfo, baseUrl, singleFile);
        }
        if (singleFile) {
            // A standalone index.html embeds no plugin data files, so the AI Analysis tab would be
            // missing from exactly the report that asked for it.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "aiAnalysis is not compatible with singleFile");
        }
        if (resultDirs.size() != 1) {
            // The analysis reads one results directory as one test run; merging several is out of scope.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "aiAnalysis requires exactly one results directory");
        }

        final Path resultDir = resultDirs.get(0);
        final AiAnalysisService.Prepared prepared = aiAnalysisService.prepare(resultDir, reportPath);
        final ReportEntity entity = generate(reportPath, resultDirs, false, executorInfo, baseUrl, false);
        aiAnalysisService.register(entity.getUuid(), resultDir, reportPath, baseUrl, clearResults, prepared);
        return entity;
    }

    /**
     * Rebuilds an existing report from new results under the same uuid: URL, level, path, creation
     * time, build url and redirect stay, only the files and {@link ReportEntity#getSize()} change.
     * <p>
     * Used by the AI analysis to put the model answers into the report the user already has open,
     * instead of publishing the same night as a new version of the path. History is NOT looked up
     * here: {@code resultDirs} goes to the generator as is, so the caller passes the history the
     * report was first generated with, or nothing - taking {@code reports/<uuid>/history} would feed
     * the report its own night a second time.
     * <p>
     * The report is built next to the old one and swapped in by two renames; any failure before the
     * second rename leaves the old report untouched, a failure of the second one moves it back.
     *
     * @param uuid       report to rebuild; must exist
     * @param resultDirs results to build it from; the first one receives {@code executor.json}
     * @param baseUrl    absolute base url for the links inside the report
     * @return the same entity with the new size
     * @throws ResponseStatusException 404 if the report is not found
     * @throws IOException             if the swap fails; the old report is still in place
     */
    public ReportEntity regenerateInPlace(@NonNull UUID uuid,
                                         @NonNull List<Path> resultDirs,
                                         String baseUrl
    ) throws IOException {
        Preconditions.checkArgument(!resultDirs.isEmpty());
        resultDirs.forEach(i -> Preconditions.checkArgument(Files.exists(i), "Result '%s' doesn't exist", i));
        final ReportEntity entity = repository.findOneByUuid(uuid)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report '" + uuid + "' not found"));

        final Path destination = reportsDir.resolve(uuid.toString());
        final Path fresh = reportsDir.resolve(uuid + IN_PLACE_TMP_SUFFIX);
        final Path old = reportsDir.resolve(uuid + IN_PLACE_OLD_SUFFIX);

        // 1. Leftovers of an interrupted swap. A process killed between the two renames leaves the
        // report only aside: it goes back first, otherwise the clean-up below would delete it
        if (!Files.exists(destination) && Files.isDirectory(old)) {
            Files.move(old, destination);
            log.warn("restored report '{}' from a previous interrupted rebuild", uuid);
        }
        deleteQuietly(fresh.toFile());
        deleteQuietly(old.toFile());

        // 2. Build next to the report the users are looking at
        final Path resultWithInfo = resultDirs.get(0);
        addExecutionInfo(
            resultWithInfo,
            readExecutorInfo(resultWithInfo),
            baseUrl + str(reportsDir.resolve(uuid.toString())) + "/index.html",
            uuid
        );
        final String reportUrl = join(baseUrl, cfg.reports().dir(), uuid.toString()) + "/";
        boolean generated = false;
        try {
            reportGenerator.generate(fresh, resultDirs, reportUrl, false);
            generated = true;
        } finally {
            if (!generated) {
                deleteQuietly(fresh.toFile());
            }
        }

        // 3. Old report aside. On Windows an open handle on the directory makes this fail
        try {
            Files.move(destination, old);
        } catch (IOException e) {
            deleteQuietly(fresh.toFile());
            throw e;
        }

        // 4. New report in
        try {
            Files.move(fresh, destination);
        } catch (IOException e) {
            try {
                Files.move(old, destination);
            } catch (IOException rollback) {
                e.addSuppressed(rollback);
                log.error("Report '{}' could not be restored from '{}'", destination, old, rollback);
            }
            throw e;
        }

        // 5. Only now the row: a failed swap must not change the size of the report that stayed. Before
        // the clean-up, which on a full night takes long enough to be interrupted
        entity.setSize(ReportEntity.sizeKB(destination));
        repository.saveAndFlush(entity);

        // 6. The new report is served and counted already; a leftover is removed by the next swap or
        // by the sweep of the AI analysis
        try {
            FileUtils.deleteDirectory(old.toFile());
        } catch (IOException e) {
            log.warn("Unable to delete the previous copy '{}' of report '{}': {}", old, uuid, e.getMessage());
        }
        log.info("Report '{}' regenerated in place according to results '{}'", destination, resultDirs);
        return entity;
    }

    ///// PRIVATE /////

    //region Private
    private void handleMaxHistory(ReportEntity created) {
        var max = cfg.reports().historyLevel();

        if (created.getLevel() >= max) { // Check reports count in history
            // Get all sorted reports
            var allReports = repository.findByPathOrderByCreatedDateTimeDesc(created.getPath());

            // If size more than max history
            if (allReports.size() >= max) {
                log.info("Current report count '{}' exceed max history report count '{}'",
                    allReports.size(),
                    max
                );

                // Delete last after max history
                final var toDelete = allReports.stream().skip(max).toList();
                toDelete.forEach(e -> {
                    log.info("Report '{}' will be deleted", e);
                    deleteQuietly(reportsDir.resolve(e.getUuid().toString()).toFile());
                    repository.delete(e);
                });

                // Update level (safety)
                created.setLevel(Math.max(created.getLevel() - (long) toDelete.size(), 0L));
            }
        }
    }

    @SneakyThrows
    private Optional<Path> copyHistory(Path reportPath, String prevReportWithHistoryUuid) {
        // History dir in report dir
        final Path sourceHistory = reportPath.resolve("history");

        // If History dir exists
        if (Files.exists(sourceHistory) && Files.isDirectory(sourceHistory)) {
            // Create tmp history dir
            final Path tmpHistory = reportsDir.resolve("history").resolve(prevReportWithHistoryUuid);
            FileUtils.moveDirectoryToDirectory(sourceHistory.toFile(), tmpHistory.toFile(), true);
            log.info("Report '{}' history is '{}'", reportPath, tmpHistory);
            return Optional.of(tmpHistory);
        } else {
            // Or nothing
            return Optional.empty();
        }
    }

    /** The {@code executor.json} a previous generation left in the results, so a rebuild keeps it. */
    @Nullable
    private ExecutorInfo readExecutorInfo(Path resultDir) {
        final Path file = resultDir.resolve(JSON_FILE_NAME);
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

    @NotNull
    private ExecutorInfo addExecutionInfo(Path resultPathWithInfo,
                                          ExecutorInfo executor,
                                          String reportUrl,
                                          UUID uuid) throws IOException {

        var executorInfo = ofNullable(executor).orElse(new ExecutorInfo());
        executorInfo.setReportUrl(reportUrl);

        if (StringUtils.isBlank(executorInfo.getName())) {
            executorInfo.setName("Remote executor");
        }
        if (StringUtils.isBlank(executorInfo.getType())) {
            executorInfo.setType("CI");
        }
        if (StringUtils.isBlank(executorInfo.getReportName())) {
            executorInfo.setName("Allure server generated " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        if (StringUtils.isBlank(executorInfo.getReportName())) {
            executorInfo.setReportName(uuid.toString());
        }
        final ObjectWriter writer = objectMapper.writer(new DefaultPrettyPrinter());
        final Path executorPath = resultPathWithInfo.resolve(JSON_FILE_NAME);
        writer.writeValue(executorPath.toFile(), executorInfo);
        log.info("Executor information added to '{}' : {}", executorPath, executorInfo);
        return executorInfo;
    }
    //endregion
}
