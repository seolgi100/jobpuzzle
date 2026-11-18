package com.example.jobpuzzle.analysis.controller;

import com.example.jobpuzzle.analysis.dto.AnalysisQuestionRequest;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.service.QuestionGenerationV2Service;
import com.example.jobpuzzle.analysis.dto.QuestionSetResponse;
import com.example.jobpuzzle.analysis.dto.PreparedQuestionSetResponse;
import com.example.jobpuzzle.analysis.service.QuestionGenerationService;
import com.example.jobpuzzle.global.common.ApiResponse;
import com.example.jobpuzzle.ticket.entity.TicketUsage;
import com.example.jobpuzzle.ticket.service.TicketService;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.interview.dto.QuestionListResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/question-sets")
public class QuestionSetController {

    private final QuestionGenerationService questionGenerationService;
    private final QuestionGenerationV2Service questionGenerationV2Service;
    private final TicketService ticketService;

    // 평가 명세 v2 1장: 기초 지식·자료 기반·인성 면접 질문 생성. AI가 최종 실패하면 spend가 티켓을 돌려준다.
    @PostMapping("/knowledge")
    public ResponseEntity<ApiResponse<QuestionSetResponse>> generateKnowledge(
            @AuthenticationPrincipal(expression = "user") User user,
            @Valid @RequestBody AnalysisQuestionRequest request
    ) {
        return created(ticketService.spend(user.getUserId(), TicketUsage.KNOWLEDGE, ticketService.cost(TicketUsage.KNOWLEDGE),
                () -> questionGenerationV2Service.generate(user.getUserId(), InterviewSessionMode.KNOWLEDGE, request.analysisCaseId())));
    }

    @PostMapping("/material")
    public ResponseEntity<ApiResponse<QuestionSetResponse>> generateMaterial(
            @AuthenticationPrincipal(expression = "user") User user,
            @Valid @RequestBody AnalysisQuestionRequest request
    ) {
        return created(ticketService.spend(user.getUserId(), TicketUsage.MATERIAL, ticketService.cost(TicketUsage.MATERIAL),
                () -> questionGenerationV2Service.generate(user.getUserId(), InterviewSessionMode.MATERIAL, request.analysisCaseId())));
    }

    @PostMapping("/personality")
    public ResponseEntity<ApiResponse<QuestionSetResponse>> generatePersonality(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return created(ticketService.spend(user.getUserId(), TicketUsage.PERSONALITY, ticketService.cost(TicketUsage.PERSONALITY),
                () -> questionGenerationV2Service.generatePersonality(user.getUserId())));
    }

    private static ResponseEntity<ApiResponse<QuestionSetResponse>> created(QuestionSetResponse response) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{questionSetId}")
    public ResponseEntity<ApiResponse<QuestionSetResponse>> getQuestionSet(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long questionSetId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                questionGenerationService.getQuestionSet(
                        user.getUserId(),
                        questionSetId
                )
        ));
    }

    @GetMapping("/prepared")
    public ResponseEntity<ApiResponse<List<PreparedQuestionSetResponse>>> getPreparedQuestionSets(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                questionGenerationService.getUnusedGeneratedQuestionSets(user.getUserId())
        ));
    }

    @GetMapping("/{questionSetId}/questions")
    public ResponseEntity<ApiResponse<List<QuestionListResponse>>> getQuestions(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long questionSetId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                questionGenerationService.getQuestionList(
                        user.getUserId(),
                        questionSetId
                )
        ));
    }
}
