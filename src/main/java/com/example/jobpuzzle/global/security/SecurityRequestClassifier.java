package com.example.jobpuzzle.global.security;

import jakarta.servlet.http.HttpServletRequest;

/** 화면 이동과 JSON 기능 요청을 같은 보안 규칙 안에서 구분한다. */
public final class SecurityRequestClassifier {

    private SecurityRequestClassifier() {
    }

    /** context path를 제외한 실제 요청 URI로 화면 요청과 JSON 기능 요청을 구분한다. */
    public static boolean isFunctionRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.startsWith("/user/")
                || path.startsWith("/documents")
                || path.startsWith("/document-extractions")
                || path.startsWith("/analysis-cases")
                || path.startsWith("/analysis/cases")
                || path.startsWith("/question-sets")
                || path.startsWith("/questions")
                || path.startsWith("/interview-sessions")
                || path.startsWith("/interview-session-questions")
                || path.startsWith("/interview-modes")
                || path.startsWith("/interview-weakness-tags")
                || path.startsWith("/interview-history")
                || path.startsWith("/answer-evaluations")
                // 전용 화면 /action-plans는 화면 요청으로 남기고 단수 API 경로만 JSON 요청으로 분류한다.
                || path.equals("/action-plan")
                || path.startsWith("/action-plan/")
                || path.startsWith("/job-category")
                || path.startsWith("/admin-api")
                || path.startsWith("/guide-admin");
    }
}
