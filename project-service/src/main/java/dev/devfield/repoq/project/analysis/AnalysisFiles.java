package dev.devfield.repoq.project.analysis;

import static dev.devfield.repoq.project.analysis.AnalysisSnapshots.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.devfield.repoq.project.github.GitHubSourceClient.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** COLLECTED means a sealed, unpublished file result, not a current public-access approval. */
final class AnalysisFiles {
    record FileResult(String path, String blobSha, int bytes, int attempt, String state, Draft draft) {}
    record Allowance(int files, int bytes) {}
    record Result(Draft draft, int bytes) {}
    private final JdbcTemplate db;
    private final AnalysisJobs jobs;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    AnalysisFiles(JdbcTemplate db, AnalysisJobs jobs, TransactionTemplate tx) {
        this.db = db; this.jobs = jobs; this.tx = tx;
    }

    Map<String, FileResult> read(AnalysisJobs.Execution run, Plan plan, PublicAccessVerifier.Proof proof) {
        return tx.execute(status -> {
            var job = jobs.current(run);
            checkTarget(job, plan.target(), plan.commitSha());
            checkProof(plan, proof);
            var rows = db.query("SELECT file_path,blob_sha,expected_bytes,attempt_count,state,draft::text," +
                    "commit_sha,policy_version,expires_at>clock_timestamp() FROM project.analysis_file_progress " +
                    "WHERE analysis_job_id=? AND project_repository_id=? ORDER BY file_path", (rs, n) -> {
                if (!plan.commitSha().equals(rs.getString(7)) || !policy(job).equals(rs.getString(8)) || !rs.getBoolean(9))
                    throw new Failure("file_progress_invalidated");
                Draft draft = null;
                try { if (rs.getString(6) != null) draft = json.readValue(rs.getString(6), Draft.class); }
                catch (Exception e) { throw new Failure("file_progress_invalidated"); }
                return new FileResult(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5), draft);
            }, run.jobId(), plan.target().repositoryId());
            Map<String, FileResult> result = new LinkedHashMap<>();
            for (FileResult row : rows) {
                if (plan.files().stream().noneMatch(e -> e.path().equals(row.path()) && e.sha().equals(row.blobSha()) && e.size()==row.bytes()))
                    throw new Failure("file_progress_invalidated");
                if (row.draft()!=null) validateFile(plan.target(), plan.commitSha(), row.path(), row.draft());
                result.put(row.path(), row);
            }
            checkProof(plan, proof);
            return Map.copyOf(result);
        });
    }

    Allowance allowance(AnalysisJobs.Execution run, AnalysisJobs.Target target) {
        return tx.execute(status -> allowance(jobs.current(run), target));
    }

    private Allowance allowance(AnalysisJobs.Locked job, AnalysisJobs.Target target) {
        int index = job.members().stream().map(AnalysisJobs.Member::id).toList().indexOf(target.repositoryId());
        if (index<0) throw new IllegalArgumentException("Unknown repository");
        int quota = 40/job.members().size() + (index<40%job.members().size() ? 1 : 0);
        int[] totals = db.queryForObject("SELECT download_count,download_bytes FROM project.analysis_job WHERE id=?",
                (rs,n)->new int[]{rs.getInt(1),rs.getInt(2)}, job.id());
        int[] member = db.queryForObject("SELECT COALESCE(sum(download_count),0),COALESCE(sum(download_count*expected_bytes),0) " +
                "FROM project.analysis_file_progress WHERE analysis_job_id=? AND project_repository_id=?",
                (rs,n)->new int[]{rs.getInt(1),rs.getInt(2)}, job.id(), target.repositoryId());
        return new Allowance(Math.min(40-totals[0],quota-member[0]),
                Math.min(2097152-totals[1],2097152/job.members().size()-member[1]));
    }

    void reserve(AnalysisJobs.Execution run, Plan plan, FileEntry file) {
        tx.executeWithoutResult(status -> {
            var job = jobs.current(run);
            checkTarget(job, plan.target(), plan.commitSha());
            if (!plan.files().contains(file) || file.size()<0 || file.size()>102400 || !AnalysisJobs.validSha(file.sha()))
                throw new IllegalArgumentException("Invalid file reservation");
            var previous = db.query("SELECT attempt_count,state,commit_sha,blob_sha,expected_bytes,policy_version,expires_at>clock_timestamp() " +
                    "FROM project.analysis_file_progress WHERE analysis_job_id=? AND project_repository_id=? AND file_path=?", (rs,n) -> {
                if (rs.getInt(1)>=run.attempt() || !"RESERVED".equals(rs.getString(2)))
                    throw new IllegalStateException("File already reserved or collected");
                if (!plan.commitSha().equals(rs.getString(3)) || !file.sha().equals(rs.getString(4)) || file.size()!=rs.getInt(5) ||
                        !policy(job).equals(rs.getString(6)) || !rs.getBoolean(7)) throw new Failure("file_progress_invalidated");
                return true;
            },run.jobId(),plan.target().repositoryId(),file.path());
            Allowance available = allowance(job,plan.target());
            if (available.files()<1 || available.bytes()<file.size()) throw new Failure("collection_budget_exhausted");
            // Never refund an unknown request: a crash may happen after GitHub sent its complete body.
            jobs.writeCurrent(run,"download_count=download_count+1,download_bytes=download_bytes+?",file.size());
            if (previous.isEmpty()) db.update("INSERT INTO project.analysis_file_progress(analysis_job_id,project_id,project_repository_id," +
                    "membership_version,github_repository_id,commit_sha,blob_sha,file_path,expected_bytes,policy_version,attempt_count," +
                    "download_count,state,expires_at) SELECT ?,?,?,?,?,?,?,?,?,?,?,1,'RESERVED',created_at+make_interval(secs=>?) " +
                    "FROM project.analysis_job WHERE id=?",run.jobId(),run.projectId(),plan.target().repositoryId(),run.membershipVersion(),
                    plan.target().githubRepositoryId(),plan.commitSha(),file.sha(),file.path(),file.size(),policy(job),run.attempt(),jobs.maxJobSeconds(),run.jobId());
            else db.update("UPDATE project.analysis_file_progress SET attempt_count=?,download_count=download_count+1 " +
                    "WHERE analysis_job_id=? AND project_repository_id=? AND file_path=?",run.attempt(),run.jobId(),plan.target().repositoryId(),file.path());
        });
    }

    void collect(AnalysisJobs.Execution run, Plan plan, FileEntry file, Draft draft) {
        validateFile(plan.target(), plan.commitSha(), file.path(), draft);
        tx.executeWithoutResult(status -> {
            var job = jobs.current(run); checkTarget(job,plan.target(),plan.commitSha());
            int changed = db.update("UPDATE project.analysis_file_progress SET state='COLLECTED',draft=CAST(? AS jsonb) " +
                    "WHERE analysis_job_id=? AND project_repository_id=? AND file_path=? AND blob_sha=? AND commit_sha=? " +
                    "AND expected_bytes=? AND attempt_count=? AND state='RESERVED' AND policy_version=? AND expires_at>clock_timestamp()",
                    jobs.encode(draft),run.jobId(),plan.target().repositoryId(),file.path(),file.sha(),plan.commitSha(),file.size(),run.attempt(),policy(job));
            if (changed!=1) throw new IllegalStateException("Stale file result");
            jobs.current(run);
        });
    }

    Result merge(AnalysisJobs.Execution run, Plan original, Map<String,Integer> exclusions, PublicAccessVerifier.Proof proof) {
        var rows = read(run,original,proof).values().stream().filter(r->"COLLECTED".equals(r.state())).sorted(Comparator.comparing(FileResult::path)).toList();
        Member member = new Member(UUID.randomUUID(),original.target().repositoryId(),original.target().githubRepositoryId(),original.commitSha());
        List<Evidence> evidence = new ArrayList<>(); Map<String,Topic> topics = new TreeMap<>(); List<String> paths = new ArrayList<>();
        Map<String,Integer> skipped = new TreeMap<>(exclusions);
        for (FileResult row : rows) {
            var d = row.draft();
            for (Evidence e:d.evidence()) if (evidence.size()<200)
                evidence.add(new Evidence(e.id(),member.id(),e.path(),e.lineStart(),e.lineEnd(),e.claimType(),e.summary()));
            for (Topic t:d.topics()) if(evidence.stream().anyMatch(e->e.id().equals(t.evidenceId()))) topics.putIfAbsent(t.key(),t);
            paths.addAll(d.coverage().getFirst().selectedPaths());
            d.coverage().getFirst().exclusions().forEach((k,v)->skipped.merge(k,v,Integer::sum));
        }
        var draft = new Draft(List.of(member),evidence,List.copyOf(topics.values()),List.of(),List.of(),
                List.of(new Coverage(member.id(),paths.size(),skipped.values().stream().mapToInt(Integer::intValue).sum(),paths,skipped)));
        validate(draft,false);
        return new Result(draft,rows.stream().mapToInt(FileResult::bytes).sum());
    }

    private static String policy(AnalysisJobs.Locked job) { return CHECKPOINT_POLICY + ":" + job.analysisVersion(); }

    private static void checkTarget(AnalysisJobs.Locked job, AnalysisJobs.Target target, String sha) {
        if (job.members().stream().noneMatch(m->m.id().equals(target.repositoryId()) && m.githubId().equals(target.githubRepositoryId())) ||
                job.progress().stream().noneMatch(p->p.repositoryId().equals(target.repositoryId()) && sha.equals(p.commitSha())))
            throw new IllegalStateException("Stale repository");
    }
    private static void checkProof(Plan plan, PublicAccessVerifier.Proof proof) {
        if (proof==null || !proof.validFor(List.of(new PublicAccessVerifier.Source(plan.target().repositoryId(),plan.target().githubRepositoryId(),plan.commitSha()))))
            throw new IllegalStateException("Public access not verified");
    }
    private static void validateFile(AnalysisJobs.Target target, String sha, String path, Draft d) {
        validate(d,false);
        if (d.members().size()!=1 || !d.members().getFirst().repositoryId().equals(target.repositoryId()) ||
                !d.members().getFirst().githubRepositoryId().equals(target.githubRepositoryId()) || !d.members().getFirst().commitSha().equals(sha) ||
                !d.roles().isEmpty() || !d.relations().isEmpty() || d.coverage().getFirst().collectedFiles()>1 ||
                d.coverage().getFirst().selectedPaths().size()!=d.coverage().getFirst().collectedFiles() ||
                d.coverage().getFirst().selectedPaths().stream().anyMatch(p->!p.equals(path)) || d.evidence().stream().anyMatch(e->!e.path().equals(path)))
            throw new IllegalArgumentException("Invalid file evidence");
    }
    void cleanup() {
        db.update("DELETE FROM project.analysis_file_progress WHERE (analysis_job_id,project_repository_id,file_path) IN " +
                "(SELECT f.analysis_job_id,f.project_repository_id,f.file_path FROM project.analysis_file_progress f " +
                "JOIN project.analysis_job j ON j.id=f.analysis_job_id JOIN project.registered_project p ON p.id=f.project_id " +
                "JOIN project.project_repository r ON r.id=f.project_repository_id WHERE f.expires_at<=clock_timestamp() " +
                "OR j.state NOT IN ('QUEUED','RUNNING') OR p.state<>'ACTIVE' OR p.membership_version<>f.membership_version " +
                "OR r.removed_at IS NOT NULL OR r.visibility<>'PUBLIC' OR r.access_state<>'ACTIVE' OR EXISTS " +
                "(SELECT 1 FROM project.project_write_fence w WHERE w.owner_user_id=p.owner_user_id AND w.state<>'ACTIVE' " +
                "AND (w.scope='USER' OR w.project_id=p.id)) LIMIT 100)");
    }
}
