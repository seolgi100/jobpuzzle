package com.example.jobpuzzle.evaluation.controller;

import com.example.jobpuzzle.evaluation.dto.UserSummaryResponse;
import com.example.jobpuzzle.evaluation.service.UserSummaryService;
import com.example.jobpuzzle.global.common.ApiResponse;
import com.example.jobpuzzle.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserSummaryController {

    private final UserSummaryService userSummaryService;

    // 평가 명세 v2 4-3 사용자 종합 평가 (AI 없음)
    @GetMapping("/user/me/summary")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> getSummary(
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        return ResponseEntity.ok(ApiResponse.success(userSummaryService.summary(user.getUserId())));
    }
}
