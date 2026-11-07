package dev.devfield.repoq.interview.project;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public record ProjectSnapshot(String snapshotId, long membershipVersion, List<Member> members,
                              List<Topic> topics, List<Relation> relations, List<Evidence> evidenceReferences) {
    public record Member(String memberId, String repositoryId, String githubRepositoryId, String commitSha) {}
    public record Topic(String key, String primaryEvidenceId) {}
    public record Evidence(String id, String memberId, String repositoryId, String githubRepositoryId,
                           String commitSha, String filePath, Integer lineStart, Integer lineEnd,
                           String claimType, String summary) {}
    public record Relation(String fromMemberId, String toMemberId, String claimType,
                           List<String> evidenceIds, String summary) {
        public Relation { evidenceIds = List.copyOf(evidenceIds); }
    }

    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern GITHUB_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern TOPIC = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\x80-\\x9f]");

    public ProjectSnapshot {
        members = List.copyOf(members);
        topics = List.copyOf(topics);
        relations = List.copyOf(relations);
        evidenceReferences = List.copyOf(evidenceReferences);
        require(matches(UUID, snapshotId) && membershipVersion > 0 &&
                !members.isEmpty() && members.size() <= 20 && !topics.isEmpty() && topics.size() <= 64 &&
                !evidenceReferences.isEmpty() && evidenceReferences.size() <= 200 && relations.size() <= 100);
        var memberById = index(members, Member::memberId);
        index(members, Member::repositoryId);
        index(members, Member::githubRepositoryId);
        for (Member member : members) {
            require(matches(UUID, member.memberId()) && matches(UUID, member.repositoryId()) &&
                    matches(GITHUB_ID, member.githubRepositoryId()) && matches(SHA, member.commitSha()));
        }
        var evidenceById = index(evidenceReferences, Evidence::id);
        for (Evidence evidence : evidenceReferences) {
            Member member = memberById.get(evidence.memberId());
            require(matches(UUID, evidence.id()) && member != null &&
                    member.repositoryId().equals(evidence.repositoryId()) &&
                    member.githubRepositoryId().equals(evidence.githubRepositoryId()) &&
                    member.commitSha().equals(evidence.commitSha()) &&
                    "CODE_FACT".equals(evidence.claimType()) && text(evidence.summary(), 1000) &&
                    path(evidence.filePath()) && (evidence.lineStart() == null
                    ? evidence.lineEnd() == null
                    : evidence.lineEnd() != null && evidence.lineStart() > 0 && evidence.lineEnd() >= evidence.lineStart()));
        }
        index(topics, Topic::key);
        Set<String> usedEvidence = new HashSet<>();
        for (Topic topic : topics) {
            require(matches(TOPIC, topic.key()) && evidenceById.containsKey(topic.primaryEvidenceId()));
            usedEvidence.add(topic.primaryEvidenceId());
        }
        for (Relation relation : relations) {
            require("CODE_FACT".equals(relation.claimType()) && text(relation.summary(), 1000) &&
                    memberById.containsKey(relation.fromMemberId()) && memberById.containsKey(relation.toMemberId()) &&
                    !relation.fromMemberId().equals(relation.toMemberId()) && !relation.evidenceIds().isEmpty() &&
                    relation.evidenceIds().size() <= 200);
            Set<String> sources = new HashSet<>();
            for (String evidenceId : Set.copyOf(relation.evidenceIds())) {
                Evidence evidence = evidenceById.get(evidenceId);
                require(evidence != null);
                sources.add(evidence.memberId());
            }
            require(new HashSet<>(relation.evidenceIds()).size() == relation.evidenceIds().size() &&
                    sources.equals(Set.of(relation.fromMemberId(), relation.toMemberId())));
            usedEvidence.addAll(relation.evidenceIds());
        }
        require(usedEvidence.equals(evidenceById.keySet()));
    }

    private static <T> Map<String, T> index(List<T> items, Function<T, String> key) {
        return items.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && !CONTROL.matcher(value).find();
    }

    private static boolean path(String value) {
        return text(value, 1024) && !value.contains("\\") && !value.contains(":") &&
                Arrays.stream(value.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid X-01 snapshot");
    }
}
