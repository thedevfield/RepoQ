package dev.devfield.repoq.project.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AnalysisSettings(boolean enabled, int maxFailures, int leaseSeconds, int maxRunSeconds, int maxRequests, int maxJobSeconds) {
    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisSettings(
            @Value("${devfield.project.analysis.enabled:false}") boolean enabled,
            @Value("${devfield.project.analysis.max-failures:${devfield.project.analysis.max-attempts:3}}") int maxFailures,
            @Value("${devfield.project.analysis.lease-seconds:60}") int leaseSeconds,
            @Value("${devfield.project.analysis.max-run-seconds:300}") int maxRunSeconds,
            @Value("${devfield.project.analysis.max-requests:60}") int maxRequests,
            @Value("${devfield.project.analysis.max-job-seconds:86400}") int maxJobSeconds) {
        if (maxJobSeconds < 60 || maxJobSeconds > 604800 || maxRequests < 20 || maxRequests > 60 || maxFailures < 1 || maxFailures > 10 || leaseSeconds < 1 || leaseSeconds > 600 ||
                maxRunSeconds < leaseSeconds || maxRunSeconds > 3600)
            throw new IllegalArgumentException("Invalid analysis execution limits");
        this.maxJobSeconds = maxJobSeconds;
        this.maxRequests = maxRequests;
        this.enabled = enabled;
        this.maxFailures = maxFailures;
        this.leaseSeconds = leaseSeconds;
        this.maxRunSeconds = maxRunSeconds;
    }
    public AnalysisSettings(boolean enabled, int maxFailures, int leaseSeconds, int maxRunSeconds, int maxRequests) {
        this(enabled, maxFailures, leaseSeconds, maxRunSeconds, maxRequests, 86400);
    }
    public AnalysisSettings(boolean enabled, int maxFailures, int leaseSeconds, int maxRunSeconds) {
        this(enabled, maxFailures, leaseSeconds, maxRunSeconds, 60);
    }
}
