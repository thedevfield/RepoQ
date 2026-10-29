package dev.devfield.repoq.project.github;

import static org.junit.jupiter.api.Assertions.*;

import dev.devfield.repoq.project.api.ApiProblem;
import org.junit.jupiter.api.Test;

class GitHubUrlTest {
    @Test
    void acceptsOnlyCanonicalPublicRepositoryPaths() {
        assertEquals(new GitHubUrl("owner", "repo.name"), GitHubUrl.parse("https://github.com/owner/repo.name/"));
        for (String value : new String[] {
                "http://github.com/owner/repo", "https://github.com.evil.test/owner/repo",
                "https://github.com:443/owner/repo", "https://user@github.com/owner/repo",
                "https://github.com/owner/repo/tree/main", "https://github.com/owner/repo?x=1",
                "https://github.com/owner/%2e%2e", "https://github.com/owner/repo#frag",
                "https://github.com/../repo", "https://github.com/owner/repo.git",
                "https://127.0.0.1/owner/repo", "https://github.com/owner//repo"
        }) assertEquals("invalid_repository_url", assertThrows(ApiProblem.class, () -> GitHubUrl.parse(value)).code(), value);
    }
}
