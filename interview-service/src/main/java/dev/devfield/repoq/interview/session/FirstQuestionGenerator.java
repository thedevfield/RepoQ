package dev.devfield.repoq.interview.session;

import java.util.UUID;

interface FirstQuestionGenerator {
    record Input(String topicKey, UUID evidenceId, String evidenceSummary) {}

    // A network adapter must bound its call duration below the worker's lease.
    String generate(Input input);

    final class Retryable extends RuntimeException {}
}
