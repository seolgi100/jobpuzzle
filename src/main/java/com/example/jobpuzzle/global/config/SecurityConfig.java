package com.example.jobpuzzle.global.config;

import com.example.jobpuzzle.global.security.CustomAccessDeniedHandler;
import com.example.jobpuzzle.global.security.CustomAuthenticationEntryPoint;
import com.example.jobpuzzle.global.security.CustomOAuth2UserService;
import com.example.jobpuzzle.global.security.CustomUserDetailsService;
import com.example.jobpuzzle.global.security.OAuth2LoginSuccessHandler;
import com.example.jobpuzzle.global.security.SecurityRequestClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

@Configuration // 이 클래스가 스프링 설정 클래스임을 선언
@EnableWebSecurity // 스프링 시큐리티의 웹 보안 기능을 활성화
@RequiredArgsConstructor
public class SecurityConfig {

    // 카카오/구글 로그인 성공 시 회원 조회/생성을 처리하는 서비스
    private final CustomOAuth2UserService customOAuth2UserService;

    // 소셜 로그인 성공 후 직무 설정 여부에 따라 이동할 화면을 결정하는 핸들러
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    // 자동로그인 시 쿠키의 회원을 다시 찾아오는 데 사용
    private final CustomUserDetailsService customUserDetailsService;

    // 자동로그인 토큰을 DB(RefreshToken 테이블)에 저장/조회하는 구현체
    private final PersistentTokenRepository persistentTokenRepository;

    // 로그인은 했지만 권한이 없는 요청(예: 일반 회원의 관리자 화면/API 접근) 처리
    private final CustomAccessDeniedHandler customAccessDeniedHandler;

    // 로그인하지 않은 화면 요청과 기능 요청을 각각 리다이렉트/JSON으로 처리
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;

    // 자동로그인 유지 기간 - 7일
    private static final int REMEMBER_ME_VALID_SECONDS = 60 * 60 * 24 * 7;

    // 정적 리소스 경로 - 인증 없이 접근 허용할 CSS/JS/이미지 경로
    private static final String[] STATIC_URLS = {
        "/css/**",
        "/js/**",
        "/images/**"
    };

    // 로그인 없이 볼 수 있는 화면(뷰) 경로
    private static final String[] PUBLIC_VIEW_URLS = {
            "/",
            "/login",
            "/join",
            "/find-id",
            "/passwd-reset",
            "/unlock",
            "/oauth2/**"
    };

    // 인증 없이 호출 가능한 공개 API 경로
    private static final String[] PUBLIC_API_URLS = {
            "/user/join",
            "/user/join/**",
            "/user/login",
            "/user/check-id",
            "/user/check-email",
            "/user/find-id/**",
            "/user/passwd-reset/**",
            "/user/unlock/**",
            "/job-category"
    };

    // 관리자만 접근 가능한 화면 경로
    private static final String[] ADMIN_VIEW_URLS = {
            "/admin/**"
    };

    // 관리자만 접근 가능한 API 경로
    private static final String[] ADMIN_API_URLS = {
            "/admin-api/**",
            "/guide-admin/**"
    };

    // 비밀번호 암호화에 쓰는 인코더
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 아이디/비밀번호 로그인 시 인증을 처리하는 매니저
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // 자동로그인(remember-me) 토큰 발급/검증 서비스 - UserService가 로그인/로그아웃 시 직접 호출함
    // (폼로그인을 스프링 기본 필터가 아니라 UserService에서 직접 처리하고 있어서, 자동 연동 대신 빈으로 등록해서 수동 호출)
    // 반환 타입을 인터페이스(RememberMeServices)가 아니라 구현체로 둔 이유:
    // logout()은 RememberMeServices가 아니라 이 클래스가 같이 구현하는 LogoutHandler 인터페이스에 있어서,
    // 인터페이스 타입으로 받으면 UserService에서 logout()을 호출할 수 없음
    @Bean
    // 키는 app.security.remember-me-key(환경 변수 REMEMBER_ME_KEY)에서 받는다 - 코드에 비밀값을 두지 않음
    public PersistentTokenBasedRememberMeServices rememberMeServices(
            @Value("${app.security.remember-me-key}") String rememberMeKey) {
        PersistentTokenBasedRememberMeServices services = new PersistentTokenBasedRememberMeServices(
                rememberMeKey, customUserDetailsService, persistentTokenRepository);
        services.setTokenValiditySeconds(REMEMBER_ME_VALID_SECONDS);
        // 로그인 요청이 JSON body라 AbstractRememberMeServices가 찾는 "remember-me" 파라미터가 항상 없음.
        // alwaysRemember로 그 체크를 끄고, 호출 여부는 UserService.login()의 autoLogin 분기로 제어함.
        services.setAlwaysRemember(true);
        return services;
    }

    /** 로그인 후 원래 화면으로 돌아갈 수 있도록 화면 GET 요청만 세션에 저장한다. */
    @Bean
    public RequestCache requestCache() {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(request ->
                "GET".equalsIgnoreCase(request.getMethod())
                        && !SecurityRequestClassifier.isFunctionRequest(request));
        return requestCache;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           PersistentTokenBasedRememberMeServices rememberMeServices) throws Exception{
        http
                .csrf(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(STATIC_URLS).permitAll()
                        .requestMatchers(PUBLIC_VIEW_URLS).permitAll()
                        .requestMatchers(PUBLIC_API_URLS).permitAll()
                        // 관리자 전용 경로는 인증 여부보다 먼저 role을 검사해야 해서 anyRequest()보다 위에 둠
                        .requestMatchers(ADMIN_VIEW_URLS).hasRole("ADMIN")
                        .requestMatchers(ADMIN_API_URLS).hasRole("ADMIN")
                        // 위에서 허용 안 한 나머지 요청은 전부 인증(로그인) 필요
                        .anyRequest().authenticated()
                )
                // 로그인은 했지만 권한이 없는 요청(관리자 전용 경로에 일반 회원 접근 등) 처리
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                )
                .requestCache(requestCache -> requestCache.requestCache(requestCache()))
                // 세션이 없을 때 자동로그인 쿠키로 재인증을 시도하는 필터 등록
                .rememberMe(rememberMe -> rememberMe
                        .rememberMeServices(rememberMeServices)
                )
                // 카카오/구글 로그인 설정 - 로그인 성공 시 customOAuth2UserService가 회원 조회/생성 처리
                .oauth2Login(oauth2 -> oauth2
                        // 커스텀 로그인 페이지 지정 - 안 하면 스프링이 /login을 가로채서 자체 기본 로그인 화면을 띄워버림
                        .loginPage("/login")
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                        )
                        // 로그인 성공 후 이동할 화면 - 직무 미설정 회원은 설정 화면으로, 그 외엔 메인 화면으로 분기
                        .successHandler(oAuth2LoginSuccessHandler)
                );
        return http.build();

    }
  }
