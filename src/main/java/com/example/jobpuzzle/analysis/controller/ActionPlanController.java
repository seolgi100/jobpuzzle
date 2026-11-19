package com.example.jobpuzzle.analysis.controller;

import com.example.jobpuzzle.analysis.dto.ActionPlanCompleteResponse;
import com.example.jobpuzzle.analysis.dto.ActionPlanCompletionUpdateRequest;
import com.example.jobpuzzle.analysis.dto.ActionPlanListResponse;
import com.example.jobpuzzle.analysis.dto.DeadlineUpdateRequest;
import com.example.jobpuzzle.analysis.entity.ActionPlanStatus;
import com.example.jobpuzzle.analysis.service.ActionPlanService;
import com.example.jobpuzzle.global.common.ApiResponse;
import com.example.jobpuzzle.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/action-plan")
public class ActionPlanController {

    private final ActionPlanService actionPlanService;

    // 로그인 사용자의 액션플랜 목록을 선택 필터와 함께 반환한다.
    @GetMapping
    public ResponseEntity<ApiResponse<List<ActionPlanListResponse>>> getActionPlans(
            @AuthenticationPrincipal(expression = "user") User user,
            @RequestParam(required = false) ActionPlanStatus status,
            @RequestParam(required = false) Long analysisCaseId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                actionPlanService.getActionPlanList(user.getUserId(), status, analysisCaseId)
        ));
    }

    // 액션플랜 마감일을 설정·변경하거나 null 요청으로 제거한다.
    @PatchMapping("/{actionPlanId}/deadline")
    public ResponseEntity<ApiResponse<ActionPlanListResponse>> updateDeadline(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long actionPlanId,
            @RequestBody DeadlineUpdateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                actionPlanService.updateDeadline(user.getUserId(), actionPlanId, request.getDeadline())
        ));
    }

    // 토글이 아닌 최종 완료 상태를 받아 중복 요청에도 같은 결과를 보장한다.
    @PatchMapping("/{actionPlanId}/completion")
    public ResponseEntity<ApiResponse<ActionPlanCompleteResponse>> updateCompletion(
            @AuthenticationPrincipal(expression = "user") User user,
            @PathVariable Long actionPlanId,
            @Valid @RequestBody ActionPlanCompletionUpdateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                actionPlanService.updateCompletion(
                        user.getUserId(), actionPlanId, request.getCompleted()
                )
        ));
    }
}
