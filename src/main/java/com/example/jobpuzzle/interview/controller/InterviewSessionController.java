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
    private final SessionScoreAggregationService sessionScoreAggregationService;

    @GetMapping("/interview-modes/availability")
    public ResponseEntity<ApiResponse<InterviewModeAvailabilityResponse>> getAvailableModes(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getAvailableModes(user.getUserId())
        ));
    }

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

    @PostMapping("/interview-sessions/{sessionId}/start")
    public ResponseEntity<ApiResponse<SessionResponse>> startSession(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.startSession(user.getUserId(), sessionId)
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

    @GetMapping("/interview-sessions/{sessionId}/questions/next")
    public ResponseEntity<ApiResponse<SessionQuestionResponse>> getNextQuestion(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getNextQuestion(user.getUserId(), sessionId)
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

    @PostMapping("/interview-sessions/{sessionId}/cancel")
    public ResponseEntity<ApiResponse<SessionResponse>> cancelSession(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.cancelSession(user.getUserId(), sessionId)
        ));
    }

    @PostMapping("/interview-sessions/{sessionId}/questions/finish-selected")
    public ResponseEntity<ApiResponse<SessionResponse>> finishSelectedQuestions(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.finishSelectedQuestions(user.getUserId(), sessionId)
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

    @PostMapping("/interview-session-questions/{sessionQuestionId}/defer")
    public ResponseEntity<ApiResponse<SessionQuestionResponse>> deferCurrentQuestion(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long sessionQuestionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.deferCurrentQuestion(user.getUserId(), sessionQuestionId)
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

    @GetMapping("/interview-weakness-tags")
    public ResponseEntity<ApiResponse<List<String>>> getUnresolvedWeaknessTags(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getUnresolvedWeaknessTags(user.getUserId())
        ));
    }

    @GetMapping("/interview-weakness-tags/details")
    public ResponseEntity<ApiResponse<List<WeaknessTagResponse>>> getUnresolvedWeaknessTagDetails(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getUnresolvedWeaknessTagDetails(user.getUserId())
        ));
    }

    @GetMapping("/interview-sessions/active")
    public ResponseEntity<ApiResponse<SessionResponse>> getActiveSession(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getActiveSession(user.getUserId())
        ));
    }

    // 대시보드 '해결된 약점 태그' 목록
    @GetMapping("/interview-weakness-tags/resolved")
    public ResponseEntity<ApiResponse<List<WeaknessTagResponse>>> getResolvedWeaknessTags(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                interviewSessionService.getResolvedWeaknessTags(user.getUserId())
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
