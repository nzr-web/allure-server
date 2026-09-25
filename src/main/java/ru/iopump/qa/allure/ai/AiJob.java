package ru.iopump.qa.allure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * State of one analysis job, persisted as {@code <cache-dir>/<report uuid>/ai-job.json} next to the
 * result copy the worker feeds to the model. The file is the source of truth across restarts; the
 * in-memory map in {@link AiAnalysisService} is only a cache of it.
 * <p>
 * {@code baseUrl} is captured from the HTTP request that created or enqueued the job: the worker
 * runs outside any request, where {@code Util.url} would throw, yet report links must still be
 * absolute. {@code previousUuid} is resolved once at preparation time — by the time the worker
 * runs, "the previous report of this path" would resolve to the pending report itself.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiJob {

    AiJobStatus status = AiJobStatus.PENDING;

    /** Logical report path the report belongs to. */
    String reportPath;

    /** Report whose result copy was used as the previous run for the diff; may be null. */
    String previousUuid;

    /** Absolute base url captured from the request thread. */
    String baseUrl;

    /** Failure clusters found without the model. */
    int clusters;

    /**
     * Clusters with a model answer; -1 until the model has run. While a repeat run is in flight these
     * are the figures of the last finished attempt: {@code enqueue} deliberately does not reset them,
     * because stale-but-real numbers say more than two zeroes.
     */
    int answered = -1;

    /** Clusters left without an answer; -1 until the model has run. Stale like {@link #answered}. */
    int withoutAnswer = -1;

    /** Report that carries the analysis: the job's own report once it has been rebuilt in place. */
    String resultUuid;

    String createdAt;
    String startedAt;
    String finishedAt;

    /** Failure text. */
    String error;
}
