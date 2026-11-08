package dev.devfield.repoq.interview.session;

import java.util.List;
import java.util.UUID;

interface FirstQuestionGenerator {
    record Input(String topicKey, UUID evidenceId, String evidenceSummary, List<String> previousQuestions) {
        Input(String topicKey, UUID evidenceId, String evidenceSummary) {
            this(topicKey, evidenceId, evidenceSummary, List.of());
        }
    }

    // A network adapter must bound its call duration below the worker's lease.
    String generate(Input input);

    final class Retryable extends RuntimeException {}
}
