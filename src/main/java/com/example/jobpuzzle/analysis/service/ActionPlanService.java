package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.analysis.dto.ActionPlanCompleteResponse;
import com.example.jobpuzzle.analysis.dto.ActionPlanListResponse;
import com.example.jobpuzzle.analysis.entity.ActionPlan;
import com.example.jobpuzzle.analysis.entity.ActionPlanStatus;
import com.example.jobpuzzle.analysis.repository.ActionPlanRepository;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionPlanService {

    private final ActionPlanRepository actionPlanRepository;

    // 로그인 사용자의 액션플랜을 선택 상태와 분석 작업 조건으로 조회한다.
    public List<ActionPlanListResponse> getActionPlanList(
            Long userId, ActionPlanStatus status, Long analysisCaseId
    ) {
        return actionPlanRepository.findOwnedActionPlans(userId, status, analysisCaseId)
                .stream()
                .map(ActionPlanListResponse::from)
                .toList();
    }

    // 소유권을 확인한 액션플랜의 마감일을 설정하거나 null로 제거한다.
    @Transactional
    public ActionPlanListResponse updateDeadline(Long userId, Long actionPlanId, LocalDate deadline) {
        ActionPlan actionPlan = findOwnedActionPlan(userId, actionPlanId);
        actionPlan.updateDeadline(deadline);
        return ActionPlanListResponse.from(actionPlan);
    }

    // 소유권을 확인한 액션플랜을 요청된 최종 완료 상태로 변경한다.
    @Transactional
    public ActionPlanCompleteResponse updateCompletion(
            Long userId, Long actionPlanId, boolean completed
    ) {
        ActionPlan actionPlan = findOwnedActionPlan(userId, actionPlanId);
        if (completed) {
            actionPlan.complete(LocalDateTime.now());
        } else {
            actionPlan.reopen();
        }
        return ActionPlanCompleteResponse.from(actionPlan);
    }

    // 존재 여부와 타 사용자 소유 여부를 구분해 노출하지 않고 동일한 404로 처리한다.
    private ActionPlan findOwnedActionPlan(Long userId, Long actionPlanId) {
        return actionPlanRepository
                .findByActionPlanIdAndSnapshot_User_UserId(actionPlanId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACTION_PLAN_NOT_FOUND));
    }
}
