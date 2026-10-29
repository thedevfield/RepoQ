package dev.devfield.repoq.project.github;

import dev.devfield.repoq.project.api.ApiProblem;
import java.net.URI;
import org.springframework.http.HttpStatus;

public record GitHubUrl(String owner, String repo) {
    public static GitHubUrl parse(String value) {
        if (value == null || value.length() > 300) throw invalid();
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getRawAuthority()) ||
                    uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null ||
                    uri.getPort() != -1) throw invalid();
            String path = uri.getRawPath();
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String[] parts = path.split("/", -1);
            if (parts.length != 3 || !parts[0].isEmpty() ||
                    !parts[1].matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}") ||
                    !parts[2].matches("[A-Za-z0-9_.-]{1,100}") ||
                    parts[2].equals(".") || parts[2].equals("..") || parts[2].endsWith(".git")) throw invalid();
            return new GitHubUrl(parts[1], parts[2]);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ApiProblem invalid() { return new ApiProblem(HttpStatus.BAD_REQUEST, "invalid_repository_url"); }
}
