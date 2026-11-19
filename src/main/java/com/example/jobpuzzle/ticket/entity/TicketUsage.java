package com.example.jobpuzzle.ticket.entity;

// 티켓을 쓰는 항목. 면접 모드는 세션 모드(InterviewSessionMode)와 같은 이름을 쓴다.
public enum TicketUsage {
    // v2 평가 명세 1장 티켓 표
    ANALYSIS, KNOWLEDGE, MATERIAL, PERSONALITY, SESSION_RETRY, QUESTION_REGENERATE
}
