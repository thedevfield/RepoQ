package dev.devfield.repoq.project.github;

public interface GitHubRepositoryClient {
    record Repository(String id, String fullName, String name) {}
    Repository fetch(String owner, String repo);
}
