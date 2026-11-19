package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.dto.CustomizedAnalysisGenerationResult;
import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.analysis.entity.ActionPlan;
import com.example.jobpuzzle.analysis.entity.ActionPlanMatchLevel;
import com.example.jobpuzzle.analysis.entity.ActionPlanStatus;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.entity.MatchAnalysisResult;
import com.example.jobpuzzle.analysis.repository.ActionPlanRepository;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionPlanServiceTest {

    private static final long USER_ID = 7L;
    private static final long ACTION_PLAN_ID = 31L;

    @Mock
    private ActionPlanRepository actionPlanRepository;

    @InjectMocks
    private ActionPlanService actionPlanService;

    // 로그인 사용자와 선택 필터가 Repository 조회 조건에 그대로 포함되는지 검증한다.
    @Test
    void listsOnlyPlansSelectedByAuthenticatedUserAndFilters() {
        when(actionPlanRepository.findOwnedActionPlans(USER_ID, ActionPlanStatus.PENDING, 15L))
                .thenReturn(List.of());

        assertThat(actionPlanService.getActionPlanList(
                USER_ID, ActionPlanStatus.PENDING, 15L
        )).isEmpty();

        verify(actionPlanRepository).findOwnedActionPlans(
                USER_ID, ActionPlanStatus.PENDING, 15L
        );
    }

    // 소유 액션플랜 완료 시 상태와 완료 시각이 함께 응답되는지 검증한다.
    @Test
    void completesOwnedActionPlanAndRecordsCompletionTime() {
        ActionPlan actionPlan = actionPlan();
        when(actionPlanRepository.findByActionPlanIdAndSnapshot_User_UserId(
                ACTION_PLAN_ID, USER_ID
        )).thenReturn(Optional.of(actionPlan));

        var response = actionPlanService.updateCompletion(
                USER_ID, ACTION_PLAN_ID, true
        );

        assertThat(response.getActionPlanId()).isEqualTo(ACTION_PLAN_ID);
        assertThat(response.getStatus()).isEqualTo(ActionPlanStatus.DONE);
        assertThat(response.getCompletedAt()).isNotNull();
    }

    // 완료 취소 시 상태와 완료 시각이 함께 초기화되는지 검증한다.
    @Test
    void reopensOwnedActionPlanAndClearsCompletionTime() {
        ActionPlan actionPlan = actionPlan();
        actionPlan.complete(java.time.LocalDateTime.of(2026, 7, 30, 12, 0));
        when(actionPlanRepository.findByActionPlanIdAndSnapshot_User_UserId(
                ACTION_PLAN_ID, USER_ID
        )).thenReturn(Optional.of(actionPlan));

        var response = actionPlanService.updateCompletion(
                USER_ID, ACTION_PLAN_ID, false
        );

        assertThat(response.getStatus()).isEqualTo(ActionPlanStatus.PENDING);
        assertThat(response.getCompletedAt()).isNull();
    }

    // 미소유 ID와 없는 ID를 같은 NOT_FOUND 응답으로 숨기는지 검증한다.
    @Test
    void hidesMissingOrAnotherUsersActionPlanBehindNotFound() {
        when(actionPlanRepository.findByActionPlanIdAndSnapshot_User_UserId(
                ACTION_PLAN_ID, USER_ID
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> actionPlanService.updateCompletion(
                USER_ID, ACTION_PLAN_ID, true
        ))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.ACTION_PLAN_NOT_FOUND);
    }

    // 서비스 상태 변경 테스트에 사용할 실제 액션플랜 fixture를 만든다.
    private ActionPlan actionPlan() {
        ActionPlan actionPlan = ActionPlan.from(
                mock(AnalysisInputSnapshot.class),
                mock(MatchAnalysisResult.class),
                mock(AiCallLog.class),
                CustomizedAnalysisGenerationResult.Task.builder()
                        .taskId("task-1")
                        .relatedRequirementId("requirement-1")
                        .matchLevel(ActionPlanMatchLevel.LOW)
                        .missingPoint("운영 경험이 부족함")
                        .suggestion("장애 대응 사례를 정리하기")
                        .build()
        );
        ReflectionTestUtils.setField(actionPlan, "actionPlanId", ACTION_PLAN_ID);
        return actionPlan;
    }
}
