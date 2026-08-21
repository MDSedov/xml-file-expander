package io.github.mdsedov.xmlexpander.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.stream.XMLStreamException;

import io.github.mdsedov.xmlexpander.core.ExpansionOptions;
import io.github.mdsedov.xmlexpander.core.ExpansionPlan;
import io.github.mdsedov.xmlexpander.core.ProgressListener;
import io.github.mdsedov.xmlexpander.core.XmlExpansionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
class PlanManager {

    private final XmlExpansionService expansionService;
    private final Path workDirectory;
    private final Path defaultOutputDirectory;
    private final Map<UUID, PlanContext> plans = new ConcurrentHashMap<>();

    PlanManager(
            XmlExpansionService expansionService,
            @Value("${xml-expander.work-directory}") String workDirectory,
            @Value("${xml-expander.default-output-directory}") String defaultOutputDirectory) {
        this.expansionService = expansionService;
        this.workDirectory = Path.of(workDirectory).toAbsolutePath().normalize();
        this.defaultOutputDirectory = Path.of(defaultOutputDirectory).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initializeDirectories() throws IOException {
        Files.createDirectories(workDirectory);
        Files.createDirectories(defaultOutputDirectory);
    }

    PlanContext createPlan(MultipartFile multipartFile, ExpansionOptions options)
            throws IOException, XMLStreamException {
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new IllegalArgumentException("Выберите непустой XML-файл");
        }

        String originalName = safeOriginalName(multipartFile.getOriginalFilename());
        Path uploadedFile = Files.createTempFile(workDirectory, "uploaded-", ".xml");
        try {
            try (InputStream input = multipartFile.getInputStream()) {
                Files.copy(input, uploadedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ExpansionPlan plan = expansionService.buildPlan(
                    uploadedFile, options, ProgressListener.NONE);
            UUID planId = UUID.randomUUID();
            Path suggestedOutput = defaultOutputDirectory.resolve(
                    baseName(originalName) + "-expanded.xml");
            PlanContext context = new PlanContext(
                    planId,
                    uploadedFile,
                    originalName,
                    Files.size(uploadedFile),
                    options,
                    plan,
                    suggestedOutput,
                    Instant.now());
            plans.put(planId, context);
            return context;
        } catch (IOException | XMLStreamException | RuntimeException exception) {
            Files.deleteIfExists(uploadedFile);
            throw exception;
        }
    }

    PlanContext claim(UUID planId) {
        PlanContext context = plans.remove(planId);
        if (context == null) {
            throw new IllegalArgumentException(
                    "План не найден или уже запущен. Загрузите файл и постройте план заново.");
        }
        return context;
    }

    void restore(PlanContext context) {
        plans.putIfAbsent(context.id(), context);
    }

    void discard(UUID planId) throws IOException {
        PlanContext context = plans.remove(planId);
        if (context != null) {
            Files.deleteIfExists(context.uploadedFile());
        }
    }

    @PreDestroy
    void cleanup() {
        plans.values().forEach(context -> {
            try {
                Files.deleteIfExists(context.uploadedFile());
            } catch (IOException ignored) {
                // Best-effort cleanup during shutdown.
            }
        });
        plans.clear();
    }

    private static String safeOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "source.xml";
        }
        String fileName = Path.of(originalName.replace('\\', '/')).getFileName().toString();
        return fileName.isBlank() ? "source.xml" : fileName;
    }

    private static String baseName(String fileName) {
        int extension = fileName.toLowerCase().endsWith(".xml")
                ? fileName.length() - 4
                : fileName.length();
        String base = fileName.substring(0, extension)
                .replaceAll("[^A-Za-zА-Яа-яЁё0-9._-]", "_");
        return base.isBlank() ? "expanded" : base;
    }
}
