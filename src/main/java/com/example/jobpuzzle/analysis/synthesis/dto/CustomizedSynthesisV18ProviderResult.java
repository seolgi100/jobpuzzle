package com.example.jobpuzzle.analysis.synthesis.dto;

import com.example.jobpuzzle.interview.entity.InterviewQuestionEvaluationFocus;
import com.example.jobpuzzle.interview.entity.InterviewQuestionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** JSON-05 v1.8: compact decision과 필수 narrative 객체를 결합한 wire response. */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CustomizedSynthesisV18ProviderResult {
    private Readiness readiness;
    private Map<String, String> decisionById;
    private Map<String, Narrative> narrativesById;
    private List<QuestionSlot> questions;

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Narrative {
        private String reason;
        private String missingPoint;
        private String candidateEvidence;
        private String taskSuggestion;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Readiness {
        private String reason;
        private List<String> limitations;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class QuestionSlot {
        private String relatedRequirementId;
        private InterviewQuestionType questionType;
        private String question;
        private String intent;
        private InterviewQuestionEvaluationFocus evaluationFocus;
        private String evidenceId;
    }
}
