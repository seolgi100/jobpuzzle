package com.example.jobpuzzle.user.controller;

import com.example.jobpuzzle.global.common.ApiResponse;
import com.example.jobpuzzle.global.security.CustomUserDetails;
import com.example.jobpuzzle.user.dto.AccountUnlockSendRequest;
import com.example.jobpuzzle.user.dto.AccountUnlockVerifyRequest;
import com.example.jobpuzzle.user.dto.EmailSendRequest;
import com.example.jobpuzzle.user.dto.EmailVerifyRequest;
import com.example.jobpuzzle.user.dto.JoinEmailSendRequest;
import com.example.jobpuzzle.user.dto.JoinEmailVerifyRequest;
import com.example.jobpuzzle.user.dto.JoinRequest;
import com.example.jobpuzzle.user.dto.LoginRequest;
import com.example.jobpuzzle.user.dto.MyInfoUpdateRequest;
import com.example.jobpuzzle.user.dto.PasswordResetRequest;
import com.example.jobpuzzle.user.dto.PasswordResetSendRequest;
import com.example.jobpuzzle.user.dto.PasswordResetVerifyRequest;
import com.example.jobpuzzle.user.dto.UserInfoResponse;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user")
public class UserController {

    private final UserService userService;
    private final RequestCache requestCache;

    // 회원가입
    // POST /jobpuzzle/user/join { loginId, password, email, name }
    @PostMapping("/join")
    public ResponseEntity<ApiResponse<Void>> join(@Valid @RequestBody JoinRequest request) {
        userService.join(request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 회원가입 - 이메일 인증 코드 발송
    // POST /jobpuzzle/user/join/send-code { email }
    @PostMapping("/join/send-code")
    public ResponseEntity<ApiResponse<Void>> sendJoinEmailCode(@Valid @RequestBody JoinEmailSendRequest request) {
        userService.sendJoinEmailCode(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 회원가입 - 이메일 인증 코드 검증 (검증만 하고 코드는 소비하지 않음, 실제 가입 시점에 재검증됨)
    // POST /jobpuzzle/user/join/verify { email, code }
    @PostMapping("/join/verify")
    public ResponseEntity<ApiResponse<Void>> verifyJoinEmailCode(@Valid @RequestBody JoinEmailVerifyRequest request) {
        userService.verifyJoinEmailCode(request.getEmail(), request.getCode());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 아이디 중복 확인 (회원가입 화면에서 아이디 입력할 때 실시간으로 호출)
    // GET /jobpuzzle/user/check-id?loginId=xxx -> true(중복) / false(사용 가능)
    @GetMapping("/check-id")
    public ResponseEntity<ApiResponse<Boolean>> checkIdDuplicate(@RequestParam String loginId) {
        return ResponseEntity.ok(ApiResponse.success(userService.checkIdDuplicate(loginId)));
    }

    // 아이디/비밀번호 로그인
    // POST /jobpuzzle/user/login { loginId, password, autoLogin }
    // 성공하면 세션에 로그인 상태가 저장되고, 이후 요청부터는 로그인된 상태로 인식됨
    // autoLogin이 true면 자동로그인 쿠키도 함께 발급됨
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<String>> login(@Valid @RequestBody LoginRequest request,
                                                       HttpServletRequest httpRequest,
                                                       HttpServletResponse httpResponse) {
        userService.login(request, httpRequest, httpResponse);
        SavedRequest savedRequest = requestCache.getRequest(httpRequest, httpResponse);
        String redirectUrl = savedRequest == null
                ? httpRequest.getContextPath() + "/"
                : savedRequest.getRedirectUrl();
        if (savedRequest != null) {
            requestCache.removeRequest(httpRequest, httpResponse);
        }
        return ResponseEntity.ok(ApiResponse.success(redirectUrl));
    }

    // 로그아웃
    // POST /jobpuzzle/user/logout - 세션 + 자동로그인 쿠키/토큰을 모두 정리해서 로그인 상태를 해제함
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        userService.logout(httpRequest, httpResponse);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 내 정보 조회 - 로그인된 사용자만 호출 가능 (프론트에서 로그인 상태 판단할 때도 사용)
    // GET /jobpuzzle/user/me
    // expression="user"로 받는 이유: 아이디/비번 로그인은 CustomUserDetails, 카카오/구글 로그인은 CustomOAuth2User가
    // principal로 들어오는데, 두 타입 다 getUser()를 갖고 있어서 SpEL로 공통 추출함
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserInfoResponse>> getMyInfo(
            @AuthenticationPrincipal(expression = "user") User user) {
        return ResponseEntity.ok(ApiResponse.success(userService.getMyInfo(user)));
    }

    // 내 정보 수정 - 이름/이메일/기본 관심 직무
    // PUT /jobpuzzle/user/me { name, email, defaultJobCategoryId }
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<Void>> updateMyInfo(@Valid @RequestBody MyInfoUpdateRequest request,
                                                            @AuthenticationPrincipal(expression = "user") User user) {
        userService.updateMyInfo(request, user);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 회원 탈퇴
    // DELETE /jobpuzzle/user/me - 상태를 WITHDRAWN으로 바꾸고 로그인 상태도 함께 해제
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> withdraw(@AuthenticationPrincipal(expression = "user") User user,
                                                        HttpServletRequest httpRequest,
                                                        HttpServletResponse httpResponse) {
        userService.withdraw(user, httpRequest, httpResponse);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 로그인 아이디 찾기 - 인증 코드 발송
    // POST /jobpuzzle/user/find-id/send-code { email }
    @PostMapping("/find-id/send-code")
    public ResponseEntity<ApiResponse<Void>> sendVerificationCode(@Valid @RequestBody EmailSendRequest request) {
        userService.sendVerificationCode(request.getEmail());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 로그인 아이디 찾기 - 인증 코드 검증 후 로그인 아이디 반환
    // POST /jobpuzzle/user/find-id/verify { email, code }
    @PostMapping("/find-id/verify")
    public ResponseEntity<ApiResponse<String>> verifyVerificationCode(@Valid @RequestBody EmailVerifyRequest request) {
        String loginId = userService.verifyCodeAndFindLoginId(request.getEmail(), request.getCode());
        return ResponseEntity.ok(ApiResponse.success(loginId));
    }

    // 로그인 비밀번호 재설정 - 인증 코드 발송
    // POST /jobpuzzle/user/passwd-reset/send-code { loginId, email }
    @PostMapping("/passwd-reset/send-code")
    public ResponseEntity<ApiResponse<Void>> sendPasswordResetCode(@Valid @RequestBody PasswordResetSendRequest request) {
        userService.sendPasswordResetCode(request.getLoginId(), request.getEmail());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 로그인 비밀번호 재설정 - 인증 코드 검증 (검증만 하고 코드는 소비하지 않음, 유효시간 내에 재설정 완료해야 함)
    // POST /jobpuzzle/user/passwd-reset/verify { loginId, email, code }
    @PostMapping("/passwd-reset/verify")
    public ResponseEntity<ApiResponse<Void>> verifyPasswordResetCode(@Valid @RequestBody PasswordResetVerifyRequest request) {
        userService.verifyPasswordResetCode(request.getLoginId(), request.getEmail(), request.getCode());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 로그인 비밀번호 재설정 - 인증 코드 재확인 후 새 비밀번호로 변경
    // POST /jobpuzzle/user/passwd-reset/reset { loginId, email, code, newPassword }
    @PostMapping("/passwd-reset/reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        userService.resetPassword(request.getLoginId(), request.getEmail(), request.getCode(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 계정 잠금 해제 - 인증 코드 발송
    // POST /jobpuzzle/user/unlock/send-code { loginId, email }
    @PostMapping("/unlock/send-code")
    public ResponseEntity<ApiResponse<Void>> sendUnlockCode(@Valid @RequestBody AccountUnlockSendRequest request) {
        userService.sendUnlockCode(request.getLoginId(), request.getEmail());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 계정 잠금 해제 - 인증 코드 검증 (검증 성공 시 그 자리에서 잠금 해제까지 처리됨)
    // POST /jobpuzzle/user/unlock/verify { loginId, email, code }
    @PostMapping("/unlock/verify")
    public ResponseEntity<ApiResponse<Void>> verifyAndUnlock(@Valid @RequestBody AccountUnlockVerifyRequest request) {
        userService.verifyAndUnlock(request.getLoginId(), request.getEmail(), request.getCode());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
