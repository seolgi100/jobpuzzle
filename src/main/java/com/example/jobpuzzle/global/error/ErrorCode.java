package com.example.jobpuzzle.global.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    // 본인 파트별로 주석 다시고 안에 추가하세요. 중복되는 건 찾아보시고 재사용합시다.

    // Common
    COMMON_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_001", "요청한 리소스를 찾을 수 없습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_002", "요청 값이 올바르지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_003", "서버 내부 오류가 발생했습니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "COMMON_004", "접근 권한이 없습니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "COMMON_005", "로그인이 필요합니다."),

    //AI
    AI_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "AI_001", "AI 응답이 유효하지 않습니다."),
    AI_PROMPT_TEMPLATE_NOT_FOUND(HttpStatus.CONFLICT, "AI_002", "실행 단계에 사용할 활성 프롬프트 템플릿이 없습니다."),
    AI_CALL_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "AI_003", "AI 호출 로그를 찾을 수 없습니다."),

    //analysis
    JSON05_RESULT_INTEGRITY_CONFLICT(HttpStatus.CONFLICT, "ANALYSIS_010", "JSON-05 result integrity conflict"),
    VECTOR_INDEX_NOT_READY(HttpStatus.CONFLICT, "ANALYSIS_011", "분석 자료의 vector 색인이 준비되지 않았습니다."),
    EMBEDDING_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "ANALYSIS_012", "임베딩 제공자 호출에 실패했습니다."),
    EMBEDDING_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "ANALYSIS_013", "임베딩 제공자 요청 한도를 초과했습니다."),
    VECTOR_STORE_ERROR(HttpStatus.BAD_GATEWAY, "ANALYSIS_014", "vector 저장소 호출에 실패했습니다."),
    VECTOR_COLLECTION_INCOMPATIBLE(HttpStatus.CONFLICT, "ANALYSIS_015", "vector collection의 모델 또는 차원 계약이 일치하지 않습니다."),
    ACTION_PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "ANALYSIS_016", "액션플랜을 찾을 수 없습니다."),
    // Qdrant Cloud point 한도(app.rag.qdrant.capacity)를 초과할 색인 요청을 /index 단계에서 차단할 때 사용한다.
    // Qdrant 연결 실패 등 저장소 자체 오류(VECTOR_STORE_ERROR)와 구분해, 사용자가 "지금은 저장 용량이 부족해
    // 재시도하면 될 수 있는 상황"임을 알 수 있게 한다. 현재 point 수·hard limit 등 판단 근거 수치는
    // 서버 로그에만 남기고 이 코드의 메시지에는 포함하지 않는다.
    VECTOR_CAPACITY_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "ANALYSIS_017", "현재 분석 자료를 저장할 수 있는 용량이 부족합니다. 잠시 후 다시 시도해 주세요."),
    SNAPSHOT_NOT_FOUND(HttpStatus.NOT_FOUND, "ANALYSIS_001", "확정된 분석 스냅샷을 찾을 수 없습니다."),
    ANALYSIS_CASE_NOT_FOUND(HttpStatus.NOT_FOUND, "ANALYSIS_002", "존재하지 않는 분석 작업입니다."),
    ANALYSIS_CASE_NOT_DRAFT(HttpStatus.BAD_REQUEST, "ANALYSIS_003", "DRAFT 상태의 분석 작업만 자료·기준을 변경할 수 있습니다."),
    ANALYSIS_CASE_SOURCE_DUPLICATE(HttpStatus.BAD_REQUEST, "ANALYSIS_004", "이미 연결된 자료입니다."),
    ANALYSIS_CASE_JOB_POSTING_ALREADY_SELECTED(HttpStatus.BAD_REQUEST, "ANALYSIS_005", "채용공고는 1건만 선택할 수 있습니다. 기존 자료를 먼저 제거해주세요."),
    ANALYSIS_CASE_SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "ANALYSIS_006", "연결된 자료를 찾을 수 없습니다."),
    ANALYSIS_CASE_JOB_POSTING_REQUIRED(HttpStatus.BAD_REQUEST, "ANALYSIS_007", "채용공고를 정확히 1건 선택해야 합니다."),
    ANALYSIS_CASE_USER_MATERIAL_REQUIRED(HttpStatus.BAD_REQUEST, "ANALYSIS_008", "이력서·자기소개서·포트폴리오·경험정리 중 최소 1건을 선택해야 합니다."),
    ANALYSIS_CASE_NOT_READY(HttpStatus.BAD_REQUEST, "ANALYSIS_009", "입력 확정 또는 분석 진행 상태의 분석 작업만 실행할 수 있습니다."),

    //user - 회원가입/로그인
    USER_LOGIN_ID_DUPLICATE(HttpStatus.CONFLICT, "USER_001", "이미 사용 중인 아이디입니다."),
    USER_EMAIL_DUPLICATE(HttpStatus.CONFLICT, "USER_002", "이미 사용 중인 이메일입니다."),
    USER_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "USER_003", "아이디 또는 비밀번호가 올바르지 않습니다."),
    USER_ACCOUNT_LOCKED(HttpStatus.LOCKED, "USER_004", "로그인 실패 횟수 초과로 잠긴 계정입니다. \n이메일 인증으로 잠금 해제할 수 있습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_005", "존재하지 않는 회원입니다."),
    USER_INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "USER_006", "비밀번호는 영문/숫자를 포함해 8~20자로 입력해주세요."),
    USER_EMAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_007", "등록되지 않은 이메일입니다."),
    EMAIL_CODE_INCORRECT(HttpStatus.BAD_REQUEST, "USER_008", "올바르지 않은 인증 코드입니다."),
    USER_LOGIN_ID_EMAIL_MISMATCH(HttpStatus.NOT_FOUND, "USER_009", "아이디 또는 이메일을 확인해주세요."),
    USER_ACCOUNT_NOT_LOCKED(HttpStatus.BAD_REQUEST, "USER_010", "잠기지 않은 계정입니다."),
    // 문구는 USER_LOGIN_FAILED와 동일하게 유지하되, 로그인 화면 힌트 분기를 위해 코드만 분리
    USER_LOGIN_ID_NOT_FOUND(HttpStatus.UNAUTHORIZED, "USER_011", "아이디 또는 비밀번호가 올바르지 않습니다."),

    //document
    FILE_STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_001", "파일을 저장하거나 불러오는 중 오류가 발생했습니다."),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "DOCUMENT_002", "존재하지 않는 자료입니다."),
    EXTRACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "DOCUMENT_003", "존재하지 않는 추출 버전입니다."),
    EXTRACTION_NOT_LATEST_DRAFT(HttpStatus.BAD_REQUEST, "DOCUMENT_004", "최신 DRAFT 버전만 확정할 수 있습니다."),
    EXTRACTION_NOT_CONFIRMABLE(HttpStatus.BAD_REQUEST, "DOCUMENT_005", "확정할 수 있는 DRAFT 버전이 없습니다."),
    EXTRACTION_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "DOCUMENT_006", "확정되지 않은 추출본이 포함되어 있습니다."),
    INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "DOCUMENT_007", "지원하지 않는 파일 형식입니다. PDF, JPG, PNG 파일만 업로드할 수 있습니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "DOCUMENT_009", "파일 크기는 10MB를 초과할 수 없습니다."),
    CHANGE_TYPE_REQUIRED(HttpStatus.BAD_REQUEST, "DOCUMENT_010", "이미 확정된 적 있는 자료를 수정할 때는 자잘한 수정/큰 수정 여부를 선택해야 합니다."),
    EXTRACTION_ALREADY_VERSIONED(HttpStatus.BAD_REQUEST, "DOCUMENT_011", "이미 확정 이력이 있는 자료는 재추출할 수 없습니다. 수정 저장을 이용해주세요."),
    MIXED_FILE_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "DOCUMENT_012", "PDF와 이미지를 함께 업로드할 수 없습니다."),
    PDF_MULTIPLE_FILES_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "DOCUMENT_013", "PDF는 한 번에 1개 파일만 업로드할 수 있습니다."),
    IMAGE_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "DOCUMENT_014", "이미지는 최대 20장까지 업로드할 수 있습니다."),

    //job category
    JOB_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND,"job_category_001","존재하지 않는 직업 분류 입니다."),
    JOB_CATEGORY_DUPLICATE(HttpStatus.CONFLICT, "JOB_CATEGORY_002", "이미 등록된 대분류·중분류·경력수준 조합입니다."),
    JOB_CATEGORY_IN_USE(HttpStatus.CONFLICT, "JOB_CATEGORY_003", "다른 데이터에서 사용 중인 직무 분류는 삭제할 수 없습니다."),

    // guide
    GUIDE_ACTIVE_DUPLICATED(HttpStatus.CONFLICT, "GUIDE_001", "동일 검색 범위에 사용 가능한 가이드가 여러 건 존재합니다."),
    GUIDE_SCOPE_INVALID(HttpStatus.BAD_REQUEST, "GUIDE_002", "가이드 적용 범위와 분류 값 조합이 올바르지 않습니다."),
    GUIDE_ACTIVE_NOT_FOUND(HttpStatus.CONFLICT, "GUIDE_003", "해당 직무에 사용할 활성 분석 가이드가 없습니다."),
    GUIDE_NOT_FOUND(HttpStatus.NOT_FOUND, "GUIDE_004", "가이드를 찾을 수 없습니다."),
    GUIDE_NOT_DRAFT(HttpStatus.CONFLICT, "GUIDE_005", "초안 상태의 가이드만 변경할 수 있습니다."),
    GUIDE_CODE_DUPLICATED(HttpStatus.CONFLICT, "GUIDE_006", "이미 사용 중인 가이드 코드입니다."),
    GUIDE_VERSION_DUPLICATED(HttpStatus.CONFLICT, "GUIDE_007", "같은 가이드 코드에 동일한 버전이 이미 존재합니다."),
    GUIDE_NOT_LATEST_VERSION(HttpStatus.CONFLICT, "GUIDE_008", "최신 가이드 버전에서만 새 버전을 만들거나 활성화할 수 있습니다."),
    GUIDE_CHUNKS_REQUIRED(HttpStatus.CONFLICT, "GUIDE_009", "가이드를 활성화하려면 검수된 청크가 하나 이상 필요합니다."),
    GUIDE_CHUNK_ORDER_INVALID(HttpStatus.BAD_REQUEST, "GUIDE_010", "가이드 청크 순서는 0부터 빠짐없이 이어져야 합니다."),
    GUIDE_INDEXING_IN_PROGRESS(HttpStatus.CONFLICT, "GUIDE_017", "이미 가이드 인덱싱이 진행 중입니다."),
    GUIDE_INDEXING_FAILED(HttpStatus.BAD_GATEWAY, "GUIDE_018", "가이드 벡터 인덱싱에 실패했습니다."),
    GUIDE_INDEX_NOT_READY(HttpStatus.CONFLICT, "GUIDE_019", "벡터 인덱싱이 완료된 가이드만 활성화할 수 있습니다."),
    GUIDE_VECTOR_RESULT_INVALID(HttpStatus.CONFLICT, "GUIDE_020", "가이드 벡터 검색 결과가 현재 버전의 청크와 일치하지 않습니다."),
    GUIDE_CODE_VERSION_DUPLICATE(HttpStatus.CONFLICT, "GUIDE_021", "이미 등록된 가이드 코드·버전 조합입니다."),
    GUIDE_SOURCE_REQUIRED(HttpStatus.CONFLICT, "GUIDE_022", "청크로 분할할 가이드 원문이 없습니다."),


    // interview - 면접 세션·질문 선택·답변 진행
    QUESTION_SET_NOT_READY(HttpStatus.CONFLICT, "INTERVIEW_001", "사용할 수 있는 질문 묶음이 준비되지 않았습니다."),
    INVALID_QUESTION_SELECTION(HttpStatus.UNPROCESSABLE_ENTITY, "INTERVIEW_002", "선택한 질문이 질문 묶음과 일치하지 않습니다."),
    INTERVIEW_SESSION_ALREADY_EXISTS(HttpStatus.CONFLICT, "INTERVIEW_003", "해당 질문 묶음에 진행 중인 면접 세션이 있습니다."),
    INTERVIEW_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "INTERVIEW_004", "면접 세션을 찾을 수 없습니다."),
    SESSION_QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "INTERVIEW_005", "면접 세션 질문을 찾을 수 없습니다."),
    SESSION_NOT_EDITABLE(HttpStatus.CONFLICT, "INTERVIEW_006", "완료 또는 취소된 세션은 변경할 수 없습니다."),
    SESSION_CANNOT_BE_CANCELED(HttpStatus.CONFLICT, "INTERVIEW_007", "답변을 제출한 세션은 취소할 수 없습니다."),
    SESSION_CANNOT_BE_COMPLETED(HttpStatus.CONFLICT, "INTERVIEW_008", "완료 조건을 충족하지 못한 세션입니다."),
    ANSWER_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "INTERVIEW_009", "이미 확정된 답변입니다."),
    FOLLOW_UP_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "INTERVIEW_010", "추가 꼬리질문을 생성할 수 없습니다."),
    WEAKNESS_NOT_AVAILABLE(HttpStatus.CONFLICT, "INTERVIEW_011", "약점 보완에 사용할 미해결 약점이 없습니다."),
    ANSWER_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "INTERVIEW_012", "답변 기준 메시지를 찾을 수 없습니다."),
    ANSWER_SUBMISSION_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "INTERVIEW_013", "이 질문의 답변 제출 기회를 모두 사용했습니다."),

    // interview / report
    INTERVIEW_SESSION_NOT_COMPLETED(HttpStatus.BAD_REQUEST, "REPORT_002", "완료되지 않은 세션은 최종 리포트를 생성할 수 없습니다."),
    FINAL_REPORT_NOT_GENERATABLE(HttpStatus.BAD_REQUEST, "REPORT_003", "평가에 성공한 답변이 없어 최종 리포트를 생성할 수 없습니다."),
    FINAL_REPORT_GENERATION_IN_PROGRESS(HttpStatus.CONFLICT, "REPORT_004", "최종 리포트를 생성하는 중입니다. 잠시 후 다시 시도해주세요."),
    FINAL_REPORT_GENERATION_FAILED(HttpStatus.BAD_GATEWAY, "REPORT_005", "최종 리포트 생성에 실패했습니다. 잠시 후 다시 시도해주세요."),

    // ticket / payment
    TICKET_INSUFFICIENT(HttpStatus.PAYMENT_REQUIRED, "TICKET_001", "티켓이 부족합니다. 상단 메뉴의 티켓 충전에서 충전해 주세요."),
    PAYMENT_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_001", "결제 주문을 찾을 수 없습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAYMENT_002", "결제 금액이 주문 금액과 다릅니다."),
    PAYMENT_CONFIRM_FAILED(HttpStatus.BAD_GATEWAY, "PAYMENT_003", "결제 승인에 실패했습니다."),
    PAYMENT_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_004", "결제 설정이 되어 있지 않습니다. 관리자에게 문의해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message){
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
