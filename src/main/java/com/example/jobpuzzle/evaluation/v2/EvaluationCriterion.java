package com.example.jobpuzzle.evaluation.v2;

import com.example.jobpuzzle.interview.entity.InterviewSessionMode;

import java.util.Arrays;
import java.util.List;

/**
 * 평가 명세 v2 2장의 평가 항목과 5·3·1점 기준 문장. 문장은 명세 원문 그대로이며 임의로 고치지 않는다 (명세 "하지 말 것").
 * 모드 = null 이면 모든 모드 공통 항목.
 */
public enum EvaluationCriterion {
    INTENT_UNDERSTANDING("질문 의도 이해", null, "질문이 확인하려는 것에 맞게 답했는가",
            "질문의 핵심을 정확히 짚고 답변 전체가 그에 맞춰져 있음",
            "질문에 답하지만 일부가 엇나가거나 묻지 않은 내용이 많음",
            "질문과 다른 내용을 답함"),
    LOGICAL_STRUCTURE("논리적 구성", null, "결론과 근거가 순서 있게 이어지는가",
            "결론이 먼저 나오고 근거가 순서대로 뒷받침함",
            "내용은 있지만 순서가 섞여 있어 다시 읽어야 이해됨",
            "문장끼리 연결이 없거나 앞뒤가 맞지 않음"),
    CLARITY("표현 명확성", null, "간결하고 정확한 말로 전달하는가 (텍스트 답변 기준)",
            "불필요한 반복 없이 짧고 분명하게 씀",
            "뜻은 통하지만 반복이나 모호한 표현이 있음",
            "무엇을 말하려는지 알기 어려움"),

    CONCEPT_ACCURACY("개념 정확성", InterviewSessionMode.KNOWLEDGE, "설명한 개념이 맞는가",
            "핵심 개념을 틀린 부분 없이 설명함",
            "대체로 맞지만 일부가 부정확하거나 빠짐",
            "개념을 잘못 알고 있음"),
    PRINCIPLE_UNDERSTANDING("원리 이해", InterviewSessionMode.KNOWLEDGE, "\"무엇\"에서 그치지 않고 \"왜\"까지 설명하는가",
            "동작 원리나 이유까지 설명함",
            "정의는 말하지만 이유 설명이 얕음",
            "용어만 나열함"),
    PRACTICAL_APPLICATION("실무 연결", InterviewSessionMode.KNOWLEDGE, "개념을 실제 쓰임이나 예시와 연결하는가",
            "직무 상황의 구체적 예시로 연결함",
            "일반적인 예시만 듦",
            "예시나 쓰임이 없음"),

    EXPERIENCE_SPECIFICITY("경험 구체성", InterviewSessionMode.MATERIAL, "상황, 본인의 행동, 결과가 드러나는가 (본인 역할 포함)",
            "상황·본인이 한 행동·결과가 모두 구체적이고, 팀 성과와 본인 기여가 구분됨",
            "행동은 있지만 상황이나 본인 역할이 흐릿함",
            "\"열심히 했다\" 같은 일반론만 있음"),
    OUTCOME("성과 및 결과", InterviewSessionMode.MATERIAL, "결과를 확인 가능한 형태로 말하는가",
            "수치나 비교로 결과를 제시하고 배운 점까지 말함",
            "결과는 말하지만 막연함 (\"좋아졌다\")",
            "결과 언급이 없음"),
    JOB_POSTING_ALIGNMENT("공고 요구사항 연결", InterviewSessionMode.MATERIAL, "경험을 지원한 공고의 요구 역량과 연결하는가",
            "공고의 특정 요구 역량을 짚어 경험과 연결함",
            "직무와는 관련 있지만 이 공고와의 연결이 약함",
            "공고와 관련 없는 경험만 말함"),

    ROLE_FIT("직무·조직 적합성", InterviewSessionMode.PERSONALITY, "직무와 조직에서 중요하게 보는 태도와 맞는가",
            "직무에서 요구하는 태도를 근거 있는 사례로 보여줌",
            "태도는 맞지만 근거가 약함",
            "직무·조직과 어긋나는 태도가 드러남"),
    AUTHENTICITY("진정성과 일관성", InterviewSessionMode.PERSONALITY,
            "꾸민 답이 아니라 본인의 생각이며, 앞선 답변·등록 자료와 어긋나지 않는가",
            "본인 경험에 근거하고 앞선 답변·자료와 일치함",
            "모범 답안처럼 일반적임",
            "앞선 답변이나 자료와 모순됨"),
    SELF_AWARENESS("자기 인식", InterviewSessionMode.PERSONALITY, "자신의 강점과 부족한 점, 배운 점을 정확히 아는가",
            "강점과 약점을 구체적으로 알고 개선 행동까지 말함",
            "강점·약점을 말하지만 막연함",
            "자기 평가가 없거나 회피함");

    private final String label;
    private final InterviewSessionMode mode;
    private final String question;
    private final String score5;
    private final String score3;
    private final String score1;

    EvaluationCriterion(String label, InterviewSessionMode mode, String question, String score5, String score3, String score1) {
        this.label = label;
        this.mode = mode;
        this.question = question;
        this.score5 = score5;
        this.score3 = score3;
        this.score1 = score1;
    }

    public String label() {
        return label;
    }

    public String question() {
        return question;
    }

    public String score5() {
        return score5;
    }

    public String score3() {
        return score3;
    }

    public String score1() {
        return score1;
    }

    /** 공통 3개 + 모드별 3개 = 6개, 선언 순서대로. v1 모드는 평가 항목이 없다. */
    public static List<EvaluationCriterion> forMode(InterviewSessionMode mode) {
        if (mode == null || !mode.isV2()) {
            throw new IllegalArgumentException("v2 평가 항목이 없는 모드: " + mode);
        }
        return Arrays.stream(values()).filter(c -> c.mode == null || c.mode == mode).toList();
    }
}
