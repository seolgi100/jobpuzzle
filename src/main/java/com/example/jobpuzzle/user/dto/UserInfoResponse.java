package com.example.jobpuzzle.user.dto;

import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.user.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;

// 내 정보 조회 응답 (/jobpuzzle/user/me)
@Getter
@AllArgsConstructor
public class UserInfoResponse {

    private Long userId;
    private String loginId;
    private String email;
    private String name;
    private UserRole role;
    private Long defaultJobCategoryId;
    private String socialProvider;
    // 시연용 시뮬레이션 계정이면 true (분석 삭제 버튼 등). app.simulation.login-ids
    private boolean simulation;

    public static UserInfoResponse from(User user, boolean simulation) {
        // 카카오/구글로 가입한 회원은 기본 관심 직무를 아직 선택한 적이 없어서 null일 수 있음
        Long jobCategoryId = user.getDefaultJobCategory() != null
                ? user.getDefaultJobCategory().getJobCategoryId()
                : null;
        return new UserInfoResponse(
                user.getUserId(),
                user.getLoginId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                jobCategoryId,
                user.getSocialProvider(),
                simulation
        );
    }
}
