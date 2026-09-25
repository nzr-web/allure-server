package ru.iopump.qa.allure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.iopump.qa.allure.ai.AiJob;

/**
 * State of the model analysis of one report, as returned by {@code /api/report/{uuid}/ai}.
 * A projection of {@link AiJob}: the stored job also carries the base url and the report path,
 * which are internals of the worker and not part of the API.
 *
 * @param status        one of {@code none, pending, queued, running, done, partial, error}
 * @param clusters      failure clusters found in the results
 * @param answered      clusters the model answered for; {@code -1} before the model has run
 * @param withoutAnswer clusters still without an answer; {@code -1} before the model has run
 * @param resultUuid    report that carries the analysis: the same report, once rebuilt in place
 * @param error         failure text
 */
@Schema(description = "State of the AI analysis of a generated report")
public record AiJobResponse(
    String status,
    int clusters,
    int answered,
    int withoutAnswer,
    String resultUuid,
    String startedAt,
    String finishedAt,
    String error
) {

    public static AiJobResponse of(AiJob job) {
        return new AiJobResponse(
            job.getStatus().json(),
            job.getClusters(),
            job.getAnswered(),
            job.getWithoutAnswer(),
            job.getResultUuid(),
            job.getStartedAt(),
            job.getFinishedAt(),
            job.getError()
        );
    }
}
