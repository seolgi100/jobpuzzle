package com.example.jobpuzzle.evaluation.v2;

import com.example.jobpuzzle.interview.entity.InterviewSessionMode;

import java.util.Arrays;
import java.util.List;

/** 평가 명세 v2의 평가 항목. 모드 = null 이면 모든 모드 공통 항목. 기준 문장은 프롬프트 쪽에 둔다. */
public enum EvaluationCriterion {
    INTENT_UNDERSTANDING("질문 의도 이해", null),
    LOGICAL_STRUCTURE("논리적 구성", null),
    CLARITY("표현 명확성", null),

    CONCEPT_ACCURACY("개념 정확성", InterviewSessionMode.KNOWLEDGE),
    PRINCIPLE_UNDERSTANDING("원리 이해", InterviewSessionMode.KNOWLEDGE),
    PRACTICAL_APPLICATION("실무 연결", InterviewSessionMode.KNOWLEDGE),

    EXPERIENCE_SPECIFICITY("경험 구체성", InterviewSessionMode.MATERIAL),
    OUTCOME("성과 및 결과", InterviewSessionMode.MATERIAL),
    JOB_POSTING_ALIGNMENT("공고 요구사항 연결", InterviewSessionMode.MATERIAL),

    ROLE_FIT("직무·조직 적합성", InterviewSessionMode.PERSONALITY),
    AUTHENTICITY("진정성과 일관성", InterviewSessionMode.PERSONALITY),
    SELF_AWARENESS("자기 인식", InterviewSessionMode.PERSONALITY);

    private final String label;
    private final InterviewSessionMode mode;

    EvaluationCriterion(String label, InterviewSessionMode mode) {
        this.label = label;
        this.mode = mode;
    }

    public String label() {
        return label;
    }

    /** 공통 3개 + 모드별 3개 = 6개, 선언 순서대로. v1 모드는 평가 항목이 없다. */
    public static List<EvaluationCriterion> forMode(InterviewSessionMode mode) {
        if (mode == null || !mode.isV2()) {
            throw new IllegalArgumentException("v2 평가 항목이 없는 모드: " + mode);
        }
        return Arrays.stream(values()).filter(c -> c.mode == null || c.mode == mode).toList();
    }
}
