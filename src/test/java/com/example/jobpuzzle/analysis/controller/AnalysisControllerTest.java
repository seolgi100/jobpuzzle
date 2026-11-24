package com.example.jobpuzzle.analysis.controller;

import com.example.jobpuzzle.analysis.dto.AnalysisCaseResponse;
import com.example.jobpuzzle.analysis.dto.AnalysisResultResponse;
import com.example.jobpuzzle.analysis.dto.AnalysisStatusResponse;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.service.AnalysisCaseService;
import com.example.jobpuzzle.analysis.service.AnalysisResultQueryService;
import com.example.jobpuzzle.analysis.service.AnalysisRunDispatcher;
import com.example.jobpuzzle.analysis.service.AnalysisStatusQueryService;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.global.security.CustomUserDetails;
import com.example.jobpuzzle.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisController.class)
class AnalysisControllerTest {

    private static final long USER_ID = 17L;
    private static final long CASE_ID = 31L;

    @Autowired private MockMvc mockMvc;
    @MockBean private AnalysisRunDispatcher analysisRunDispatcher;
    @MockBean private AnalysisCaseService analysisCaseService;
    @MockBean private AnalysisResultQueryService resultQueryService;
    @MockBean private AnalysisStatusQueryService statusQueryService;

    @Test
    void runAcceptsTheRequestAndReturnsTheCurrentCaseStatus() throws Exception {
        // 백그라운드로 넘어간 /run은 202와 현재 case 상태(ANALYZING)를 바로 반환한다.
        AnalysisCaseResponse response = org.mockito.Mockito.mock(AnalysisCaseResponse.class);
        when(response.getStatus()).thenReturn(AnalysisCaseStatus.ANALYZING);
        when(analysisCaseService.getCase(USER_ID, CASE_ID)).thenReturn(response);

        when(analysisRunDispatcher.dispatch(USER_ID, CASE_ID)).thenReturn(new AnalysisRunDispatcher.Result(CASE_ID, true));
        mockMvc.perform(post("/analysis/cases/{caseId}/run", CASE_ID)
                .with(authenticatedUser())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("success"))
                .andExpect(jsonPath("$.data.status").value("ANALYZING"));

        verify(analysisRunDispatcher).dispatch(USER_ID, CASE_ID);
        verify(analysisCaseService).getCase(USER_ID, CASE_ID);
    }

    @Test
    void runReturnsOkWhenDispatchResolvesImmediately() throws Exception {
        // 동시 요청이거나 시뮬레이션 재사용이면 디스패치가 그 자리에서 끝나고, /run도 200으로 바로 결과를 반환한다.
        AnalysisCaseResponse response = org.mockito.Mockito.mock(AnalysisCaseResponse.class);
        when(response.getStatus()).thenReturn(AnalysisCaseStatus.COMPLETED);
        when(analysisCaseService.getCase(USER_ID, CASE_ID)).thenReturn(response);

        when(analysisRunDispatcher.dispatch(USER_ID, CASE_ID)).thenReturn(new AnalysisRunDispatcher.Result(CASE_ID, false));
        mockMvc.perform(post("/analysis/cases/{caseId}/run", CASE_ID)
                        .with(authenticatedUser()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void runPreservesGuideMissingCodeAndMessage() throws Exception {
        // 가이드 부재는 전역 예외 처리기를 거쳐 GUIDE_003 계약을 그대로 노출한다.
        assertRunFailure(ErrorCode.GUIDE_ACTIVE_NOT_FOUND);
    }

    @Test
    void runPreservesRetrievalIntegrityCodeAndMessage() throws Exception {
        // retrieval 무결성 오류는 ANALYSIS_010으로 일반 서버 오류와 구분한다.
        assertRunFailure(ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT);
    }

    @Test
    void runPreservesAiResponseCodeAndMessage() throws Exception {
        // AI 처리 오류는 AI_001과 서버가 지정한 메시지를 그대로 반환한다.
        assertRunFailure(ErrorCode.AI_RESPONSE_INVALID);
    }

    @Test
    void statusReturnsTheServiceResponseForTheAuthenticatedOwner() throws Exception {
        // /status는 인증 userId를 서비스에 전달하고 단계 상태 DTO를 감싼다.
        when(statusQueryService.getStatus(USER_ID, CASE_ID)).thenReturn(AnalysisStatusResponse.builder()
                .analysisCaseId(CASE_ID).analysisCaseStatus("FAILED").build());

        mockMvc.perform(get("/analysis/cases/{caseId}/status", CASE_ID).with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisCaseStatus").value("FAILED"));

        verify(statusQueryService).getStatus(USER_ID, CASE_ID);
    }

    @Test
    void resultReturnsTheCompletedResultForTheAuthenticatedOwner() throws Exception {
        // /result는 완료 결과 DTO를 변경 없이 ApiResponse data에 넣는다.
        when(resultQueryService.getResult(USER_ID, CASE_ID)).thenReturn(AnalysisResultResponse.builder()
                .analysisCaseId(CASE_ID).status("COMPLETED").build());

        mockMvc.perform(get("/analysis/cases/{caseId}/result", CASE_ID).with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        verify(resultQueryService).getResult(USER_ID, CASE_ID);
    }

    private void assertRunFailure(ErrorCode errorCode) throws Exception {
        // /run 예외가 HTTP status와 code/message로 손실 없이 변환되는지 검증한다.
        doThrow(new CustomException(errorCode)).when(analysisRunDispatcher).dispatch(USER_ID, CASE_ID);

        mockMvc.perform(post("/analysis/cases/{caseId}/run", CASE_ID)
                        .with(authenticatedUser()).with(csrf()))
                .andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()));
    }

    private RequestPostProcessor authenticatedUser() {
        // 실제 Controller와 같은 CustomUserDetails principal을 요청에 넣는다.
        User user = User.createLocalUser("controller-test", "encoded", "controller@test.local", "컨트롤러", null);
        ReflectionTestUtils.setField(user, "userId", USER_ID);
        return SecurityMockMvcRequestPostProcessors.user(new CustomUserDetails(user));
    }
}
