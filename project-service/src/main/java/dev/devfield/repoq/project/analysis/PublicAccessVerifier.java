package dev.devfield.repoq.project.analysis;

import java.util.List;
import java.util.UUID;

/** B-stage must verify current GitHub public access and stable identity without private credentials. */
public interface PublicAccessVerifier {
    record Source(UUID repositoryId, String githubRepositoryId, String commitSha) {}
    boolean verify(List<Source> sources);
}
