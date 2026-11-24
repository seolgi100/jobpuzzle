package com.example.jobpuzzle.analysis.controller;

import com.example.jobpuzzle.analysis.dto.AnalysisResultResponse;
import com.example.jobpuzzle.analysis.dto.AnalysisCaseResponse;
import com.example.jobpuzzle.analysis.dto.AnalysisStatusResponse;
import com.example.jobpuzzle.analysis.service.AnalysisCaseService;
import com.example.jobpuzzle.analysis.service.AnalysisResultQueryService;
import com.example.jobpuzzle.analysis.service.AnalysisService;
import com.example.jobpuzzle.analysis.service.AnalysisStatusQueryService;
import com.example.jobpuzzle.global.common.ApiResponse;
import com.example.jobpuzzle.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/analysis")
public class AnalysisController {
    private final AnalysisService analysisService;
    private final AnalysisCaseService analysisCaseService;
    private final AnalysisResultQueryService queryService;
    private final AnalysisStatusQueryService statusQueryService;

    @PostMapping("/cases/{analysisCaseId}/run")
    public ResponseEntity<ApiResponse<AnalysisCaseResponse>> run(@PathVariable Long analysisCaseId, @AuthenticationPrincipal(expression = "user") User user) {
        Long resultCaseId = analysisService.runCustomizedAnalysis(user.getUserId(), analysisCaseId);
        return ResponseEntity.ok(ApiResponse.success(analysisCaseService.getCase(user.getUserId(), resultCaseId)));
    }

    @GetMapping("/cases/{analysisCaseId}/result")
    public ResponseEntity<ApiResponse<AnalysisResultResponse>> result(@PathVariable Long analysisCaseId, @AuthenticationPrincipal(expression = "user") User user) {
        return ResponseEntity.ok(ApiResponse.success(queryService.getResult(user.getUserId(), analysisCaseId)));
    }

    @GetMapping("/cases/{analysisCaseId}/status")
    public ResponseEntity<ApiResponse<AnalysisStatusResponse>> status(@PathVariable Long analysisCaseId, @AuthenticationPrincipal(expression = "user") User user) {
        return ResponseEntity.ok(ApiResponse.success(statusQueryService.getStatus(user.getUserId(), analysisCaseId)));
    }

}
