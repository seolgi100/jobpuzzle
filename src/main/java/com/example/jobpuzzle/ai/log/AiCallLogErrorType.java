package com.example.jobpuzzle.ai.log;

public enum AiCallLogErrorType {
    NONE,
    EMPTY_RESPONSE,
    JSON_PARSE_FAIL,
    MISSING_FIELD,
    INVALID_ENUM,
    INVALID_RANGE,
    TIMEOUT,
    PROVIDER_ERROR,
    RATE_LIMIT,
    COST_LIMIT,
    PROMPT_RENDER_FAILED,
    RESPONSE_PARSE_FAILED,
    RESPONSE_VALIDATION_FAILED,
    SOURCE_REFERENCE_INVALID,
    OUTPUT_LIMIT_EXCEEDED,
    // Provider를 호출하기 전에 stage 입력 계약을 초과한 경우다. 공개 API ErrorCode는 이번 단계에서 추가하지 않는다.
    INPUT_LIMIT_EXCEEDED,
    RESULT_PERSIST_FAILED,
    STALE_RUNNING,
    // AI 응답은 정상이지만 공고에서 자격요건·우대사항을 하나도 찾지 못한 경우다 (입력 공고 문제). 같은 입력으로 재시도하지 않는다.
    REQUIREMENTS_NOT_FOUND
}
