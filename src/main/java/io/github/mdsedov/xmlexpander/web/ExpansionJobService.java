package io.github.mdsedov.xmlexpander.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import io.github.mdsedov.xmlexpander.core.ExpansionResult;
import io.github.mdsedov.xmlexpander.core.ProgressUpdate;
import io.github.mdsedov.xmlexpander.core.XmlExpansionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class ExpansionJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpansionJobService.class);

    private final XmlExpansionService expansionService;
    private final ExecutorService executor;
    private final Map<UUID, JobState> jobs = new ConcurrentHashMap<>();

    ExpansionJobService(XmlExpansionService expansionService, ExecutorService expansionExecutor) {
        this.expansionService = expansionService;
        this.executor = expansionExecutor;
    }

    JobSnapshot start(PlanContext context, String requestedOutputPath, boolean overwrite) {
        Path outputPath = requestedOutputPath == null || requestedOutputPath.isBlank()
                ? context.suggestedOutputPath()
                : Path.of(requestedOutputPath.trim()).toAbsolutePath().normalize();

        UUID jobId = UUID.randomUUID();
        JobState state = new JobState(jobId, outputPath);
        jobs.put(jobId, state);
        try {
            executor.submit(() -> runJob(context, outputPath, overwrite, state));
        } catch (RuntimeException exception) {
            jobs.remove(jobId);
            throw exception;
        }
        return state.snapshot();
    }

    JobSnapshot get(UUID jobId) {
        JobState state = jobs.get(jobId);
        if (state == null) {
            throw new IllegalArgumentException("Задание не найдено: " + jobId);
        }
        return state.snapshot();
    }

    private void runJob(
            PlanContext context,
            Path outputPath,
            boolean overwrite,
            JobState state) {
        state.start();
        try {
            ExpansionResult result = expansionService.expand(
                    context.uploadedFile(),
                    outputPath,
                    context.options(),
                    context.plan(),
                    overwrite,
                    state::progress);
            state.complete(result);
        } catch (Exception exception) {
            LOGGER.error("XML expansion job {} failed", state.jobId, exception);
            state.fail(userMessage(exception));
        } finally {
            try {
                Files.deleteIfExists(context.uploadedFile());
            } catch (IOException exception) {
                LOGGER.warn("Cannot delete temporary upload {}", context.uploadedFile(), exception);
            }
        }
    }

    private static String userMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? "Неизвестная ошибка расширения"
                : current.getMessage();
    }

    private static final class JobState {
        private final UUID jobId;
        private final Path outputPath;
        private volatile String status = "QUEUED";
        private volatile int progress;
        private volatile String message = "Задание поставлено в очередь";
        private volatile Long outputBytes;
        private volatile Long duplicatesWritten;
        private volatile Long mutatedFields;
        private volatile String error;

        private JobState(UUID jobId, Path outputPath) {
            this.jobId = jobId;
            this.outputPath = outputPath;
        }

        private void start() {
            status = "RUNNING";
            progress = 0;
            message = "Расширение запущено";
        }

        private void progress(ProgressUpdate update) {
            if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
                progress = update.percentage();
                message = update.message();
            }
        }

        private void complete(ExpansionResult result) {
            outputBytes = result.outputBytes();
            duplicatesWritten = result.duplicatesWritten();
            mutatedFields = result.mutatedFields();
            progress = 100;
            message = "Файл успешно создан";
            status = "COMPLETED";
        }

        private void fail(String failure) {
            error = failure;
            message = "Расширение завершилось с ошибкой";
            status = "FAILED";
        }

        private JobSnapshot snapshot() {
            return new JobSnapshot(
                    jobId,
                    status,
                    progress,
                    message,
                    outputPath.toString(),
                    outputBytes,
                    duplicatesWritten,
                    mutatedFields,
                    error);
        }
    }
}
