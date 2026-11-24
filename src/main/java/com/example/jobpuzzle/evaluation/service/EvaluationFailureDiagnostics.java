package com.example.jobpuzzle.evaluation.service;

import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

// v2 질문 생성(QuestionGenerationV2Service)도 같은 실패 기록 형식을 쓴다.
public final class EvaluationFailureDiagnostics {

    private static final int MAX_FAILURE_DETAIL_LENGTH = 900;
    private static final int MAX_RESPONSE_PREVIEW_LENGTH = 500;

    private EvaluationFailureDiagnostics() {
    }

    static String newTraceId() {
        return "EVAL-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(Locale.ROOT);
    }

    public static String rootCauseSummary(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? "(no message)" : root.getMessage();
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (message.length() > 300) {
            message = message.substring(0, 300);
        }
        return root.getClass().getSimpleName() + ": " + message;
    }

    public static String failureDetail(Throwable failure) {
        List<String> causes = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            String message = current.getMessage() == null ? "(no message)" : singleLine(current.getMessage());
            causes.add(current.getClass().getSimpleName() + ": " + message);
            current = current.getCause();
        }
        return limit(String.join(" <- ", causes), MAX_FAILURE_DETAIL_LENGTH);
    }

    static String responsePreview(String rawResponse) {
        if (rawResponse == null) {
            return "(null)";
        }
        return limit(singleLine(rawResponse), MAX_RESPONSE_PREVIEW_LENGTH);
    }

    public static AiCallLogErrorType errorType(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AiProcessingException processing) {
                return processing.getErrorType();
            }
            current = current.getCause();
        }
        return AiCallLogErrorType.PROVIDER_ERROR;
    }

    private static String singleLine(String value) {
        return value.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private static String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
