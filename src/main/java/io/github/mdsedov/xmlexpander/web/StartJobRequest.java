package io.github.mdsedov.xmlexpander.web;

import java.util.UUID;

record StartJobRequest(UUID planId, String outputPath, boolean overwrite) {
}
