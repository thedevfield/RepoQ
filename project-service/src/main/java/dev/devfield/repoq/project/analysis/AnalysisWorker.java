package dev.devfield.repoq.project.analysis;

import dev.devfield.repoq.project.github.GitHubSourceClient;
import dev.devfield.repoq.project.analysis.AnalysisJobs.Target;
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
                    snapshots.cleanupCheckpoints();
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
            Budget budget = new Budget(settings.maxRequests(), () -> jobs.heartbeat(execution));
            Set<UUID> completed = snapshots.completed(execution);
            List<Target> pending = targets.stream().filter(t -> !completed.contains(t.repositoryId())).toList();
            List<Target> batch = client.batch(pending, budget);
            List<Plan> plans = new ArrayList<>();
            for (Target target : batch) {
                activeRepository = target.repositoryId();
                int index = targets.indexOf(target);
                int files = GitHubSourceClient.MAX_FILES / targets.size() + (index < GitHubSourceClient.MAX_FILES % targets.size() ? 1 : 0);
                plans.add(client.prepare(target, files, GitHubSourceClient.TOTAL_BYTES / targets.size(), budget,
                        sha -> jobs.progress(execution, new AnalysisJobs.Progress(target.repositoryId(), "RUNNING", sha, null))));
            }
            Map<UUID,Access> access = new HashMap<>();
            Map<UUID,Plan> original = new HashMap<>();
            List<Plan> pendingFiles = new ArrayList<>();
            for (Plan plan:plans) {
                activeRepository = plan.target().repositoryId();
                original.put(activeRepository,plan);
                var opened = client.openCollection(plan,budget); access.put(activeRepository,opened);
                var saved = snapshots.files().read(execution,plan,opened.proof());
                var allowance = snapshots.files().allowance(execution,plan.target());
                int count=0,bytes=0;
                List<FileEntry> selected = new ArrayList<>(); Map<String,Integer> exclusions = new TreeMap<>(plan.exclusions());
                for (FileEntry file:plan.files()) {
                    if (saved.containsKey(file.path()) && "COLLECTED".equals(saved.get(file.path()).state())) continue;
                    if (count>=allowance.files() || bytes+file.size()>allowance.bytes()) exclusions.merge("download_budget",1,Integer::sum);
                    else { selected.add(file); count++; bytes+=file.size(); }
                }
                if (!plan.files().isEmpty() && selected.isEmpty() && saved.values().stream().noneMatch(r->"COLLECTED".equals(r.state())))
                    throw new Failure("collection_budget_exhausted");
                pendingFiles.add(new Plan(plan.target(),plan.fullName(),plan.commitSha(),List.copyOf(selected),Map.copyOf(exclusions),plan.byteQuota()));
            }
            List<Plan> fitted;
            try { fitted = client.fitPlans(pendingFiles,budget,batch.size()==pending.size()?targets.size():0); }
            catch (Failure failure) {
                if (!Set.of("github_rate_limited","analysis_window_complete").contains(failure.code())) throw failure;
                fitted = client.fitPlans(pendingFiles,budget,0);
            }
            for (Plan plan:fitted) {
                activeRepository=plan.target().repositoryId();
                Plan full = original.get(activeRepository);
                for (FileEntry file:plan.files()) {
                    access.put(activeRepository,client.refreshCollection(plan,access.get(activeRepository),budget));
                    var downloaded = client.download(access.get(activeRepository),file,budget,()->snapshots.files().reserve(execution,full,file));
                    var fileDraft = analyzer.analyze(List.of(new Collected(plan.target(),plan.commitSha(),
                            downloaded.file()==null?List.of():List.of(downloaded.file()),
                            downloaded.exclusion()==null?Map.of():Map.of(downloaded.exclusion(),1))));
                    snapshots.files().collect(execution,full,file,fileDraft);
                }
                var proof = client.checkpointProof(plan,budget);
                var result = snapshots.files().merge(execution,full,plan.exclusions(),proof);
                snapshots.checkpoint(execution,result.draft(),result.bytes(),proof);
            }
            if (snapshots.completed(execution).size() != targets.size()) {
                jobs.retry(execution, "analysis_window_complete", 1);
                return true;
            }
            activeRepository = null;
            client.publicationCapacity(targets.size(), budget);
            jobs.heartbeat(execution);
            snapshots.publish(execution);
        } catch (Failure failure) {
            try {
                if (activeRepository != null && !Set.of("github_rate_limited", "analysis_window_complete").contains(failure.code())) {
                    UUID repository = activeRepository;
                    var target = jobs.targets(execution).stream().filter(t -> t.repositoryId().equals(repository)).findFirst().orElseThrow();
                    jobs.progress(execution, new AnalysisJobs.Progress(repository, "FAILED", target.commitSha(), failure.code()));
                }
                if (failure.retrySeconds() >= 0) jobs.retry(execution, failure.code(),
                        Math.min(86400, Set.of("github_rate_limited", "analysis_window_complete").contains(failure.code()) ?
                                Math.max(1, failure.retrySeconds()) : Math.max(failure.retrySeconds(), 5 * (1 << Math.min(execution.attempt(), 10)))));
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
