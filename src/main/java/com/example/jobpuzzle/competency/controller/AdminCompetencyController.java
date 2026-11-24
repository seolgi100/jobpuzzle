package com.example.jobpuzzle.competency.controller;

import com.example.jobpuzzle.competency.dto.CompetencyDtos.JobCompetencyResponse;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.JobRef;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.QuestionRequest;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.QuestionResponse;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.TagRequest;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.TagResponse;
import com.example.jobpuzzle.competency.service.CompetencyService;
import com.example.jobpuzzle.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 관리자: 직무별 역량 태그·인성 질문 관리. /admin-api 아래라 기존 관리자 권한 설정을 그대로 따른다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin-api/competency")
public class AdminCompetencyController {

    private final CompetencyService competencyService;

    @GetMapping("/jobs")
    public ResponseEntity<ApiResponse<List<JobRef>>> getJobs() {
        return ResponseEntity.ok(ApiResponse.success(competencyService.getJobs()));
    }

    // GET /admin-api/competency?mainCategory=IT·개발&subCategory=백엔드 개발
    @GetMapping
    public ResponseEntity<ApiResponse<JobCompetencyResponse>> get(@RequestParam String mainCategory,
                                                                  @RequestParam String subCategory) {
        return ResponseEntity.ok(ApiResponse.success(competencyService.get(mainCategory, subCategory)));
    }

    @PostMapping("/tags")
    public ResponseEntity<ApiResponse<TagResponse>> createTag(@Valid @RequestBody TagRequest request) {
        return ResponseEntity.ok(ApiResponse.success(competencyService.createTag(request)));
    }

    @PutMapping("/tags/{tagId}")
    public ResponseEntity<ApiResponse<TagResponse>> updateTag(@PathVariable Long tagId, @Valid @RequestBody TagRequest request) {
        return ResponseEntity.ok(ApiResponse.success(competencyService.updateTag(tagId, request)));
    }

    @DeleteMapping("/tags/{tagId}")
    public ResponseEntity<ApiResponse<Void>> deleteTag(@PathVariable Long tagId) {
        competencyService.deleteTag(tagId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/questions")
    public ResponseEntity<ApiResponse<QuestionResponse>> createQuestion(@Valid @RequestBody QuestionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(competencyService.createQuestion(request)));
    }

    @PutMapping("/questions/{questionId}")
    public ResponseEntity<ApiResponse<QuestionResponse>> updateQuestion(@PathVariable Long questionId,
                                                                        @Valid @RequestBody QuestionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(competencyService.updateQuestion(questionId, request)));
    }

    @DeleteMapping("/questions/{questionId}")
    public ResponseEntity<ApiResponse<Void>> deleteQuestion(@PathVariable Long questionId) {
        competencyService.deleteQuestion(questionId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
