package dev.devfield.repoq.project.analysis;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.Set;

/** B-stage must verify current GitHub public access and stable identity without private credentials. */
public interface PublicAccessVerifier {
    record Source(UUID repositoryId, String githubRepositoryId, String commitSha) {}
    record Proof(Set<Source> sources, Instant checkedAt) {
        public Proof { sources = Set.copyOf(sources); }
        public boolean validFor(List<Source> expected) {
            Instant now = Instant.now();
            return checkedAt != null && !checkedAt.isAfter(now) && checkedAt.plusSeconds(30).isAfter(now)
                    && sources.size() == expected.size() && sources.equals(Set.copyOf(expected));
        }
    }
    Proof verify(List<Source> sources);
}
