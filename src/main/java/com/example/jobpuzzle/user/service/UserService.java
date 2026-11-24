package com.example.jobpuzzle.user.service;

import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.repository.JobCategoryRepository;
import com.example.jobpuzzle.user.dto.JoinRequest;
import com.example.jobpuzzle.user.dto.LoginRequest;
import com.example.jobpuzzle.user.dto.MyInfoUpdateRequest;
import com.example.jobpuzzle.user.dto.UserInfoResponse;
import com.example.jobpuzzle.simulation.SimulationProperties;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.user.repository.EmailVerificationStore;
import com.example.jobpuzzle.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final SimulationProperties simulationProperties;
    private final JobCategoryRepository jobCategoryRepository;

    // 비밀번호 암호화/검증에 쓰는 인코더 (SecurityConfig에 등록된 빈)
    private final PasswordEncoder passwordEncoder;

    // 아이디/비밀번호가 맞는지 실제로 검증해주는 스프링 시큐리티 컴포넌트
    private final AuthenticationManager authenticationManager;

    // 자동로그인(리멤버미) 쿠키 발급/취소 - SecurityConfig에 등록된 빈
    // (인터페이스가 아니라 구현체 타입인 이유는 SecurityConfig의 rememberMeServices() 빈 주석 참고 - logout()이 LogoutHandler 쪽에 있어서 필요함)
    private final PersistentTokenBasedRememberMeServices rememberMeServices;

    // 자동로그인 토큰 DB 삭제용 - rememberMeServices.logout()은 쿠키만 지우고 DB 토큰은 안 지워서 별도로 호출
    private final PersistentTokenRepository persistentTokenRepository;

    // 인증 코드 메일 발송 서비스
    private final MailService mailService;
    private final EmailVerificationStore emailVerificationStore;

    // 회원 탈퇴 시 다른 도메인에 흩어진 회원 소유 데이터를 지우는 서비스
    private final
    UserWithdrawalService userWithdrawalService;

    // 비밀번호 정책 - 영문/숫자 각각 1자 이상 포함, 8~20자
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,20}$");

    // 아이디/비밀번호 회원가입
    public void join(JoinRequest request) {
        // 아이디 중복 체크
        if (checkIdDuplicate(request.getLoginId())) {
            throw new CustomException(ErrorCode.USER_LOGIN_ID_DUPLICATE);
        }
        // 이메일 중복 체크
        if (checkEmailDuplicate(request.getEmail())) {
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
        // 이메일 인증 코드 재검증 - /join/verify로 미리 확인했더라도 가입 처리 시점에 다시 확인
        if (!emailVerificationStore.verify(request.getEmail(), request.getCode())) {
            throw new CustomException(ErrorCode.EMAIL_CODE_INCORRECT);
        }
        // 비밀번호 정책 검증
        validatePassword(request.getPassword());

        // 비밀번호는 평문 그대로 저장하면 안 되므로 암호화
        String encodedPassword = passwordEncoder.encode(request.getPassword());

        // 직무 선택 UI가 아직 없어서 안 보내는 경우가 많음 - null이면 조회 안 하고 그대로 null로 저장
        JobCategory jobCategory = null;
        if (request.getDefaultJobCategoryId() != null) {
            jobCategory = jobCategoryRepository.findById(request.getDefaultJobCategoryId())
                    .orElseThrow(() -> new CustomException(ErrorCode.JOB_CATEGORY_NOT_FOUND));
        }

        User user = User.createLocalUser(request.getLoginId(), encodedPassword, request.getEmail(),
                request.getName(), jobCategory);
        userRepository.save(user);
        emailVerificationStore.invalidate(request.getEmail());
    }

    // 회원가입 - 아직 가입되지 않은 이메일인지 확인 후 인증 코드 발송
    public void sendJoinEmailCode(String email) {
        if (checkEmailDuplicate(email)) {
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
        mailService.sendMail(email);
    }

    // 회원가입 - 이메일 인증 코드 검증 (검증만 하고 코드는 소비하지 않음, 실제 가입 시점에 재검증됨)
    public void verifyJoinEmailCode(String email, int inputCode) {
        if (!emailVerificationStore.verify(email, inputCode)) {
            throw new CustomException(ErrorCode.EMAIL_CODE_INCORRECT);
        }
    }

    // 아이디 중복 확인 - true면 이미 사용 중인 아이디 (회원가입 화면에서 실시간 체크용)
    public boolean checkIdDuplicate(String loginId) {
        return userRepository.existsByLoginId(loginId);
    }

    // 이메일 중복 확인 - true면 이미 사용 중인 이메일
    public boolean checkEmailDuplicate(String email) {
        return userRepository.existsByEmail(email);
    }

    // 비밀번호 길이/조합 정책 검증 - 정책 위반 시 예외
    public void validatePassword(String password) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new CustomException(ErrorCode.USER_INVALID_PASSWORD);
        }
    }

    // 아이디/비밀번호 로그인
    @Transactional(noRollbackFor = CustomException.class)
    public void login(LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // 회원 존재 여부 확인
        User user = userRepository.findByLoginId(request.getLoginId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_LOGIN_ID_NOT_FOUND));

        // 로그인 실패 누적으로 잠긴 계정이면 비밀번호 확인도 안 하고 바로 차단
        if (Boolean.TRUE.equals(user.getIsLocked())) {
            throw new CustomException(ErrorCode.USER_ACCOUNT_LOCKED);
        }

        try {
            // 실제 아이디/비밀번호 일치 여부 검증
            // 내부적으로 CustomUserDetailsService가 회원을 찾고, PasswordEncoder로 비밀번호를 비교함
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getLoginId(), request.getPassword())
            );

            // 로그인 성공했으니 실패 횟수 초기화
            user.resetLoginFailCount();

            // 로그인 성공 정보를 세션에 저장해서 이후 요청에서도 로그인 상태가 유지되게 함
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            httpRequest.getSession(true)
                    .setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

            // 자동로그인 체크한 경우 - 리멤버미 쿠키 + DB 토큰 발급
            if (Boolean.TRUE.equals(request.getAutoLogin())) {
                rememberMeServices.loginSuccess(httpRequest, httpResponse, authentication);
            }

        } catch (AuthenticationException e) {
            // 비밀번호가 틀린 경우 - 실패 횟수를 올리고(일정 횟수 넘으면 자동 잠금) 에러를 던짐
            user.increaseLoginFailCount();
            String message = "로그인 실패 : " + user.getLoginFailCount() + "\n(로그인 실패 5번 초과시 계정이 잠깁니다.)";
            throw new CustomException(ErrorCode.USER_LOGIN_FAILED, message);
        }
    }

    // 로그아웃 - 세션 무효화 + 시큐리티 컨텍스트 정리 + 자동로그인 쿠키/토큰 정리
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 쿠키 만료 처리 (브라우저에 남은 자동로그인 쿠키 제거)
        rememberMeServices.logout(httpRequest, httpResponse, authentication);
        // DB에 저장된 자동로그인 토큰도 함께 삭제
        if (authentication != null) {
            persistentTokenRepository.removeUserTokens(authentication.getName());
        }

        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    // 인증 코드 검증 및 이메일로 로그인 아이디 반환
    public String verifyCodeAndFindLoginId(String email, int inputCode) {
        if(!emailVerificationStore.verify(email, inputCode)) {
            throw new CustomException(ErrorCode.EMAIL_CODE_INCORRECT);
        }
        return userRepository.findLoginIdByEmail(email);
    }

    // User에 존재하는 이메일로 인증 코드 발송
    public void sendVerificationCode(String email) {
        if(userRepository.existsByEmail(email)) {
            mailService.sendMail(email);
        } else {
            throw new CustomException(ErrorCode.USER_EMAIL_NOT_FOUND);
        }
    }

    // 비밀번호 재설정 - 아이디+이메일이 같은 계정인지 확인 후 인증 코드 발송
    public void sendPasswordResetCode(String loginId, String email) {
        if (!userRepository.existsByLoginIdAndEmail(loginId, email)) {
            throw new CustomException(ErrorCode.USER_LOGIN_ID_EMAIL_MISMATCH);
        }
        mailService.sendMail(email);
    }

    // 비밀번호 재설정 - 인증 코드 검증 (검증만 하고 코드는 소비하지 않음)
    public void verifyPasswordResetCode(String loginId, String email, int inputCode) {
        if (!userRepository.existsByLoginIdAndEmail(loginId, email)) {
            throw new CustomException(ErrorCode.USER_LOGIN_ID_EMAIL_MISMATCH);
        }
        if (!emailVerificationStore.verify(email, inputCode)) {
            throw new CustomException(ErrorCode.EMAIL_CODE_INCORRECT);
        }
    }

    // 비밀번호 재설정 - 인증 코드 재확인 후 비밀번호 변경, 완료 시점에 인증 코드 무효화
    public void resetPassword(String loginId, String email, int inputCode, String newPassword) {
        User user = userRepository.findByLoginId(loginId)
                .filter(u -> u.getEmail().equals(email))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_LOGIN_ID_EMAIL_MISMATCH));

        if (!emailVerificationStore.verify(email, inputCode)) {
            throw new CustomException(ErrorCode.EMAIL_CODE_INCORRECT);
        }

        validatePassword(newPassword);
        user.changePassword(passwordEncoder.encode(newPassword));
        emailVerificationStore.invalidate(email);
    }

    // 계정 잠금 해제 - 아이디+이메일이 같은 계정인지, 실제로 잠긴 계정인지 확인 후 인증 코드 발송
    public void sendUnlockCode(String loginId, String email) {
        User user = userRepository.findByLoginId(loginId)
                .filter(u -> u.getEmail().equals(email))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_LOGIN_ID_EMAIL_MISMATCH));

        if (!Boolean.TRUE.equals(user.getIsLocked())) {
            throw new CustomException(ErrorCode.USER_ACCOUNT_NOT_LOCKED);
        }

        mailService.sendMail(email);
    }

    // 계정 잠금 해제 - 인증 코드 검증 성공 시 그 자리에서 잠금 해제 처리, 완료 시점에 인증 코드 무효화
    public void verifyAndUnlock(String loginId, String email, int inputCode) {
        User user = userRepository.findByLoginId(loginId)
                .filter(u -> u.getEmail().equals(email))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_LOGIN_ID_EMAIL_MISMATCH));

        if (!Boolean.TRUE.equals(user.getIsLocked())) {
            throw new CustomException(ErrorCode.USER_ACCOUNT_NOT_LOCKED);
        }

        if (!emailVerificationStore.verify(email, inputCode)) {
            throw new CustomException(ErrorCode.EMAIL_CODE_INCORRECT);
        }

        user.unlock();
        emailVerificationStore.invalidate(email);
    }

    // 내 정보 조회 - 로그인된 회원 기준
    // principal은 로그인(세션) 시점에 조회된 스냅샷이라 그 이후 변경사항(예: 직무 설정)이 반영 안 될 수 있어서,
    // updateMyInfo/withdraw와 동일하게 매번 DB에서 최신 상태로 다시 조회함
    public UserInfoResponse getMyInfo(User principal) {
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserInfoResponse.from(user, simulationProperties.getLoginIds().contains(user.getLoginId()));
    }

    // 내 정보 수정 - 이름/이메일/기본 관심 직무
    // 컨트롤러에서 넘어온 User는 인증 시점에 조회된 엔티티라 현재 트랜잭션에서 영속 상태가 아닐 수 있어서,
    // userId로 다시 조회한 영속 엔티티를 수정해야 변경 감지(dirty checking)로 실제 반영됨
    public void updateMyInfo(MyInfoUpdateRequest request, User principal) {
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        JobCategory jobCategory = null;
        if (request.getDefaultJobCategoryId() != null) {
            jobCategory = jobCategoryRepository.findById(request.getDefaultJobCategoryId())
                    .orElseThrow(() -> new CustomException(ErrorCode.JOB_CATEGORY_NOT_FOUND));
        }

        // 이메일을 바꾸는 경우에만 중복 체크 (본인 이메일은 중복으로 안 침)
        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())
                && userRepository.existsByEmailAndUserIdNot(request.getEmail(), user.getUserId())) {
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }

        user.updateProfile(request.getName(), request.getEmail(), jobCategory);
    }

    // 회원 탈퇴 - 회원 소유 데이터를 DB에서 완전히 삭제해서 같은 아이디/이메일로 재가입할 수 있게 함
    // (updateMyInfo와 같은 이유로 principal을 다시 조회해서 씀)
    public void withdraw(User principal, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        userWithdrawalService.deleteAllDataAndUser(user.getUserId());
        logout(httpRequest, httpResponse);
    }
}
