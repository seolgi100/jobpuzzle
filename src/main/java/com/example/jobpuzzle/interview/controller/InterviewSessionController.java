package com.example.jobpuzzle.interview.controller;

import com.example.jobpuzzle.global.common.ApiResponse;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.interview.dto.*;
import com.example.jobpuzzle.interview.service.InterviewSessionService;
import com.example.jobpuzzle.evaluation.dto.SessionScoreSummary;
import com.example.jobpuzzle.evaluation.service.SessionScoreAggregationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class InterviewSessionController {

    private final InterviewSessionService interviewSessionService;
    private final com.example.jobpuzzle.interview.service.QuestionGenerationV2Service questionGenerationV2Service;
    private final com.example.jobpuzzle.interview.service.InterviewRoundService interviewRoundService;
    private final com.example.jobpuzzle.evaluation.service.SessionReportV2Service sessionReportV2Service;
    private final SessionScoreAggregationService sessionScoreAggregationService;

    @PostMapping("/interview-sessions")
    public ResponseEntity<ApiResponse<SessionResponse>> createSession(
            @AuthenticationPrincipal(expression = "user") User user,
            @Valid @RequestBody SessionCreateRequest request
    ) {
        SessionResponse response = interviewSessionService.createSession(
                user.getUserId(),
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/interview-sessions/{sessionId}")
    public ResponseEntity<ApiResponse<SessionResponse>> getSession(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getSession(user.getUserId(), sessionId)
        ));
    }

    @GetMapping("/interview-sessions/{sessionId}/questions")
    public ResponseEntity<ApiResponse<List<SessionQuestionResponse>>> getQuestions(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getSessionQuestions(user.getUserId(), sessionId)
        ));
    }

    @PostMapping("/interview-session-questions/{sessionQuestionId}/answers")
    public ResponseEntity<ApiResponse<AnswerSubmitResponse>> submitAnswer(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionQuestionId,
            @Valid @RequestBody AnswerSubmitRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.submitAnswer(
                        user.getUserId(),
                        sessionQuestionId,
                        request
                )
        ));
    }

    @PostMapping("/interview-sessions/{sessionId}/complete")
    public ResponseEntity<ApiResponse<SessionResponse>> completeSession(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.completeSession(user.getUserId(), sessionId)
        ));
    }

    // 평가 명세 v2 다회차: 끝난 면접을 같은 질문으로 다시 진행 (티켓은 서비스에서 차감·반환)
    @PostMapping("/interview-sessions/{sessionId}/retry")
    public ResponseEntity<ApiResponse<SessionResponse>> retrySession(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                interviewRoundService.retry(user.getUserId(), sessionId)));
    }

    // 끝난 면접을 새 질문으로 다시 진행
    @PostMapping("/interview-sessions/{sessionId}/regenerate")
    public ResponseEntity<ApiResponse<SessionResponse>> regenerateSession(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                interviewRoundService.regenerate(user.getUserId(), sessionId)));
    }

    @PostMapping("/interview-sessions/{sessionId}/cancel")
    public ResponseEntity<ApiResponse<SessionResponse>> cancelSession(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.cancelSession(user.getUserId(), sessionId)
        ));
    }

    @PostMapping("/interview-session-questions/{sessionQuestionId}/finish-current")
    public ResponseEntity<ApiResponse<SessionQuestionResponse>> finishCurrentQuestion(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionQuestionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.finishCurrentQuestion(
                        user.getUserId(),
                        sessionQuestionId
                )
        ));
    }

    @PostMapping("/interview-session-questions/{sessionQuestionId}/skip")
    public ResponseEntity<ApiResponse<SessionQuestionResponse>> skipQuestion(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionQuestionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.skipQuestion(user.getUserId(), sessionQuestionId)
        ));
    }

    @GetMapping("/interview-sessions/{sessionId}/questions/remaining")
    public ResponseEntity<ApiResponse<List<RemainingQuestionResponse>>> getRemainingQuestions(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getRemainingQuestions(user.getUserId(), sessionId)
        ));
    }

    @PostMapping("/interview-sessions/{sessionId}/questions")
    public ResponseEntity<ApiResponse<List<SessionQuestionResponse>>> addQuestions(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId,
            @Valid @RequestBody SessionQuestionAddRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.addQuestions(
                        user.getUserId(),
                        sessionId,
                        request.getQuestionIds()
                )
        ));
    }

    @GetMapping("/interview-sessions/active-list")
    public ResponseEntity<ApiResponse<List<SessionResponse>>> getActiveSessions(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getActiveSessions(user.getUserId())
        ));
    }

    @GetMapping("/interview-sessions/review-list")
    public ResponseEntity<ApiResponse<List<SessionResponse>>> getReviewReadySessions(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getReviewReadySessions(user.getUserId())
        ));
    }

    @GetMapping("/interview-sessions/history")
    public ResponseEntity<ApiResponse<List<SessionResponse>>> getCompletedSessions(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getCompletedSessions(user.getUserId())
        ));
    }

    // 평가 명세 v2 6장 최종 보고서 (AI 호출 없음). 끝나기 전에도 중간 결과로 볼 수 있다
    @GetMapping("/interview-sessions/{sessionId}/report")
    public ResponseEntity<ApiResponse<com.example.jobpuzzle.evaluation.dto.SessionReportV2Response>> getReport(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(sessionReportV2Service.report(user.getUserId(), sessionId)));
    }

    // 면접 종료 직후 화면과 JSON-07 리포트 입력에서 함께 사용하는 확정 집계값
    @GetMapping("/interview-sessions/{sessionId}/score")
    public ResponseEntity<ApiResponse<SessionScoreSummary>> getScore(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                sessionScoreAggregationService.aggregate(user.getUserId(), sessionId)
        ));
    }
}
