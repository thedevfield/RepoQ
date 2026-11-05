package dev.devfield.repoq.project.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AnalysisSettings(boolean enabled, int maxAttempts, int leaseSeconds, int maxRunSeconds) {
    public AnalysisSettings(
            @Value("${devfield.project.analysis.enabled:false}") boolean enabled,
            @Value("${devfield.project.analysis.max-attempts:3}") int maxAttempts,
            @Value("${devfield.project.analysis.lease-seconds:60}") int leaseSeconds,
            @Value("${devfield.project.analysis.max-run-seconds:300}") int maxRunSeconds) {
        if (enabled) throw new IllegalStateException("Analysis execution requires the B-stage collector and analyzer");
        if (maxAttempts < 1 || maxAttempts > 10 || leaseSeconds < 1 || leaseSeconds > 600 ||
                maxRunSeconds < leaseSeconds || maxRunSeconds > 3600)
            throw new IllegalArgumentException("Invalid analysis execution limits");
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
        this.maxRunSeconds = maxRunSeconds;
    }
}
