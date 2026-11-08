package dev.devfield.repoq.interview.session;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"devfield.interview.sessions.enabled", "devfield.interview.first-question.fake-enabled"},
        havingValue = "true")
class FakeFirstQuestionGenerator implements FirstQuestionGenerator {
    @Override
    public String generate(Input input) {
        String fact = input.evidenceSummary();
        int end = fact.offsetByCodePoints(0, Math.min(fact.codePointCount(0, fact.length()), 180));
        String question = "분석에서 확인한 사실입니다. " + fact.substring(0, end)
                + " 이 구현을 선택한 이유와 고려한 대안은 무엇인가요?";
        return JsonNodeFactory.instance.objectNode()
                .put("question", question).put("evidenceId", input.evidenceId().toString()).toString();
    }
}
