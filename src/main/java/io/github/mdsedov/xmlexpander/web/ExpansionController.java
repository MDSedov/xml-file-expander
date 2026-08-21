package io.github.mdsedov.xmlexpander.web;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.xml.stream.XMLStreamException;

import io.github.mdsedov.xmlexpander.core.ExpansionOptions;
import io.github.mdsedov.xmlexpander.core.SizeParser;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
class ExpansionController {

    private final PlanManager planManager;
    private final ExpansionJobService jobService;

    ExpansionController(PlanManager planManager, ExpansionJobService jobService) {
        this.planManager = planManager;
        this.jobService = jobService;
    }

    @PostMapping(value = "/plans", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PlanResponse createPlan(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "target") String mode,
            @RequestParam(defaultValue = "1.5GiB") String targetSize,
            @RequestParam(defaultValue = "1") String fixedExtraCopies,
            @RequestParam(defaultValue = ExpansionOptions.DEFAULT_AUTO_PARENT) String autoParent,
            @RequestParam(defaultValue = ExpansionOptions.DEFAULT_AUTO_ITEM) String autoItem,
            @RequestParam(defaultValue = "") String paths,
            @RequestParam(defaultValue = "") String uniqueFields)
            throws IOException, XMLStreamException {
        List<String> targetPaths = lines(paths);
        List<String> uniqueFieldPaths = lines(uniqueFields);
        ExpansionOptions options;
        if ("fixed".equalsIgnoreCase(mode)) {
            long copies;
            try {
                copies = Long.parseLong(fixedExtraCopies.trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Количество дополнительных копий должно быть целым числом", exception);
            }
            options = ExpansionOptions.fixedCopies(
                    copies, targetPaths, autoParent, autoItem, uniqueFieldPaths);
        } else {
            options = ExpansionOptions.targetSize(
                    SizeParser.parse(targetSize),
                    targetPaths,
                    autoParent,
                    autoItem,
                    uniqueFieldPaths);
        }

        return PlanResponse.from(planManager.createPlan(file, options));
    }

    @PostMapping(value = "/jobs", consumes = MediaType.APPLICATION_JSON_VALUE)
    JobSnapshot startJob(@RequestBody StartJobRequest request) {
        if (request == null || request.planId() == null) {
            throw new IllegalArgumentException("Не указан идентификатор плана");
        }
        PlanContext context = planManager.claim(request.planId());
        try {
            return jobService.start(context, request.outputPath(), request.overwrite());
        } catch (RuntimeException exception) {
            planManager.restore(context);
            throw exception;
        }
    }

    @GetMapping("/jobs/{jobId}")
    JobSnapshot getJob(@PathVariable UUID jobId) {
        return jobService.get(jobId);
    }

    @DeleteMapping("/plans/{planId}")
    void discardPlan(@PathVariable UUID planId) throws IOException {
        planManager.discard(planId);
    }

    private static List<String> lines(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\R"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
