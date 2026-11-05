package com.example.jobpuzzle.analysis.controller;

import com.example.jobpuzzle.analysis.dto.BasicQuestionRequest;
import com.example.jobpuzzle.analysis.dto.QuestionSetResponse;
import com.example.jobpuzzle.analysis.dto.WeaknessQuestionRequest;
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
    private final TicketService ticketService;

    @PostMapping("/basic")
    public ResponseEntity<ApiResponse<QuestionSetResponse>> generateBasic(
            @AuthenticationPrincipal(expression = "user") User user,
            @Valid @RequestBody BasicQuestionRequest request
    ) {
        // 첫 기본 면접은 무료, 이후 티켓 차감. 질문 생성에 실패하면 돌려준다.
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                ticketService.spend(user.getUserId(), TicketUsage.BASIC, ticketService.cost(TicketUsage.BASIC),
                        () -> questionGenerationService.generateBasicQuestionSet(user.getUserId(), request))
        ));
    }

    @PostMapping("/weakness")
    public ResponseEntity<ApiResponse<QuestionSetResponse>> generateWeakness(
            @AuthenticationPrincipal(expression = "user") User user,
            @Valid @RequestBody WeaknessQuestionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                ticketService.spend(user.getUserId(), TicketUsage.WEAKNESS_REVIEW,
                        ticketService.cost(TicketUsage.WEAKNESS_REVIEW),
                        () -> questionGenerationService.generateWeaknessQuestionSet(user.getUserId(), request))
        ));
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
