package dev.devfield.repoq.project.analysis;

import dev.devfield.repoq.project.github.GitHubSourceClient;
import dev.devfield.repoq.project.github.GitHubSourceClient.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
    private final AnalysisSettings settings;
    private final AnalysisJobs jobs;
    private final AnalysisSnapshots snapshots;
    private final Optional<GitHubSourceClient> source;
    private final StaticEvidenceAnalyzer analyzer;
    private final AtomicBoolean busy = new AtomicBoolean();
    private ScheduledExecutorService poller;
    private ThreadPoolExecutor executor;

    public AnalysisWorker(AnalysisSettings settings, AnalysisJobs jobs, AnalysisSnapshots snapshots,
                          Optional<GitHubSourceClient> source, StaticEvidenceAnalyzer analyzer) {
        this.settings = settings; this.jobs = jobs; this.snapshots = snapshots; this.source = source; this.analyzer = analyzer;
    }

    @PostConstruct
    public void start() {
        if (!settings.enabled()) return;
        if (source.isEmpty() || !analyzer.available()) throw new IllegalStateException("Analysis requires the public source client and JDK compiler");
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> new Thread(runnable, "repoq-analysis-worker"), new ThreadPoolExecutor.AbortPolicy());
        poller = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "repoq-analysis-poll"));
        poller.scheduleWithFixedDelay(this::poll, 5, 5, TimeUnit.SECONDS);
    }

    void poll() {
        if (!busy.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    for (UUID expired : jobs.expired(20)) {
                        try { jobs.recover(expired); } catch (RuntimeException e) { log.debug("Analysis recovery deferred"); }
                    }
                    for (UUID id : jobs.queued(20)) if (runJob(id)) break;
                } catch (RuntimeException e) { log.warn("Analysis polling failed"); }
                finally { busy.set(false); }
            });
        } catch (RejectedExecutionException e) { busy.set(false); }
    }

    // Explicit entry point also used by the isolated tests; no HTTP endpoint calls it.
    public boolean runJob(UUID id) {
        GitHubSourceClient client = source.orElseThrow(() -> new IllegalStateException("Public source unavailable"));
        AnalysisJobs.Execution execution = jobs.claim(id);
        if (execution == null) return false;
        UUID activeRepository = null;
        try {
            var targets = jobs.targets(execution);
            Budget budget = new Budget(140, () -> jobs.heartbeat(execution));
            List<Plan> plans = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                var target = targets.get(i); activeRepository = target.repositoryId();
                int files = GitHubSourceClient.MAX_FILES / targets.size() + (i < GitHubSourceClient.MAX_FILES % targets.size() ? 1 : 0);
                int bytes = GitHubSourceClient.TOTAL_BYTES / targets.size();
                plans.add(client.prepare(target, files, bytes, budget, sha -> jobs.progress(execution,
                        new AnalysisJobs.Progress(target.repositoryId(), "RUNNING", sha, null))));
            }
            List<Collected> collected = new ArrayList<>();
            for (Plan plan : plans) {
                activeRepository = plan.target().repositoryId();
                collected.add(client.collect(plan, budget));
                jobs.progress(execution, new AnalysisJobs.Progress(activeRepository, "READY", plan.commitSha(), null));
            }
            jobs.heartbeat(execution);
            var draft = analyzer.analyze(collected);
            jobs.heartbeat(execution);
            if (draft.topics().stream().noneMatch(AnalysisSnapshots.Topic::active))
                jobs.finish(execution, "INSUFFICIENT_EVIDENCE", "insufficient_code_evidence");
            else snapshots.publish(execution, draft);
        } catch (Failure failure) {
            try {
                if (activeRepository != null) {
                    UUID repository = activeRepository;
                    var target = jobs.targets(execution).stream().filter(t -> t.repositoryId().equals(repository)).findFirst().orElseThrow();
                    jobs.progress(execution, new AnalysisJobs.Progress(repository, "FAILED", target.commitSha(), failure.code()));
                }
                if (failure.retrySeconds() >= 0) jobs.retry(execution, failure.code(),
                        Math.min(86400, Math.max(failure.retrySeconds(), 5 * (1 << execution.attempt()))));
                else jobs.finish(execution, failure.code().equals("empty_repository") ? "INSUFFICIENT_EVIDENCE" : "FAILED", failure.code());
            } catch (RuntimeException stale) { log.debug("Analysis result discarded after execution changed"); }
        } catch (RuntimeException failure) {
            try { jobs.finish(execution, "FAILED", "analysis_failed"); }
            catch (RuntimeException stale) { log.debug("Analysis result discarded after execution changed"); }
        }
        return true;
    }

    @PreDestroy
    public void stop() {
        if (poller != null) poller.shutdownNow();
        if (executor != null) executor.shutdownNow();
    }
}
