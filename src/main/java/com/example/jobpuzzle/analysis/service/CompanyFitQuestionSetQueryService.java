package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.analysis.dto.CompanyFitQuestionSetResponse;
import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.entity.ReadinessResult;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.analysis.repository.ReadinessResultRepository;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.guide.entity.GuideContextInputReferenceType;
import com.example.jobpuzzle.guide.entity.GuideContextPurpose;
import com.example.jobpuzzle.guide.entity.GuideContextResult;
import com.example.jobpuzzle.guide.repository.GuideContextResultRepository;
import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.InterviewQuestionReviewStatus;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CompanyFitQuestionSetQueryService {

    private final AnalysisCaseRepository analysisCaseRepository;
    private final AnalysisInputSnapshotRepository snapshotRepository;
    private final ReadinessResultRepository readinessResultRepository;
    private final QuestionSetRepository questionSetRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final GuideContextResultRepository guideContextResultRepository;

    /**
     * 분석 팀이 제공하는 COMPANY_FIT 인수인계 경계.
     * interview 서비스는 이 메서드로 분석 완료·소유권·JSON-05 무결성을 먼저 검증한다.
     */
    @Transactional(readOnly = true)
    public CompanyFitQuestionSetResponse getCompanyFitQuestionSet(Long userId, Long analysisCaseId) {
        AnalysisCase analysisCase = analysisCaseRepository
                .findByAnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_CASE_NOT_FOUND));
        if (analysisCase.getStatus() != AnalysisCaseStatus.COMPLETED) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_NOT_READY);
        }

        AnalysisInputSnapshot snapshot = snapshotRepository
                .findByAnalysisCase_AnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SNAPSHOT_NOT_FOUND));
        ReadinessResult readiness = readinessResultRepository
                .findBySnapshot_SnapshotId(snapshot.getSnapshotId())
                .orElseThrow(this::integrityConflict);
        GuideContextResult guideContext = guideContextResultRepository
                .findByPurposeAndInputReferenceTypeAndInputReferenceId(
                        GuideContextPurpose.CUSTOMIZED_SYNTHESIS,
                        GuideContextInputReferenceType.ANALYSIS_SNAPSHOT,
                        String.valueOf(snapshot.getSnapshotId())
                )
                .orElseThrow(this::integrityConflict);

        QuestionSet questionSet = questionSetRepository
                .findBySnapshot_SnapshotIdAndInterviewMode(
                        snapshot.getSnapshotId(),
                        InterviewSessionMode.COMPANY_FIT
                )
                .orElse(null);
        validateQuestionSetPresence(readiness, snapshot, questionSet);

        List<InterviewQuestion> questions = questionSet == null
                ? List.of()
                : interviewQuestionRepository
                        .findByQuestionSet_QuestionSetIdOrderByDisplayOrderAsc(
                                questionSet.getQuestionSetId()
                        );
        validateQuestions(readiness, snapshot, questionSet, questions);

        return CompanyFitQuestionSetResponse.builder()
                .analysisCaseId(analysisCaseId)
                .snapshotId(snapshot.getSnapshotId())
                .questionSetId(questionSet == null ? null : questionSet.getQuestionSetId())
                .mode(questionSet == null
                        ? InterviewSessionMode.COMPANY_FIT.name()
                        : questionSet.getInterviewMode().name())
                .readinessStatus(readiness.getStatus().name())
                .canGenerateQuestions(readiness.isCanGenerateQuestions())
                .guideId(guideContext.getGuide() == null
                        ? null
                        : guideContext.getGuide().getGuideId())
                .guideVersion(guideContext.getGuideVersion())
                .guideMatchType(guideContext.getMatchType().name())
                .questions(questions.stream()
                        .map(this::toQuestionResponse)
                        .toList())
                .build();
    }

    private void validateQuestionSetPresence(
            ReadinessResult readiness,
            AnalysisInputSnapshot snapshot,
            QuestionSet questionSet
    ) {
        boolean questionSetExists = questionSet != null;
        // v2 분석은 질문 세트가 없다. v1 질문 세트가 있을 때만 분석 결과와 맞는지 본다.
        if (questionSetExists && !readiness.isCanGenerateQuestions()) {
            throw integrityConflict();
        }
        if (questionSetExists
                && !Objects.equals(
                        snapshot.getSnapshotId(),
                        questionSet.getSnapshot().getSnapshotId()
                )) {
            throw integrityConflict();
        }
    }

    private void validateQuestions(
            ReadinessResult readiness,
            AnalysisInputSnapshot snapshot,
            QuestionSet questionSet,
            List<InterviewQuestion> questions
    ) {
        if (questionSet != null
                && (questions.isEmpty() || questions.size() > 10)) {
            throw integrityConflict();
        }
        boolean invalid = questions.stream().anyMatch(question ->
                question.getReviewStatus() != InterviewQuestionReviewStatus.PASS
                        || !Objects.equals(
                                questionSet.getQuestionSetId(),
                                question.getQuestionSet().getQuestionSetId()
                        )
                        || (question.getRelatedMatch() != null
                        && !Objects.equals(
                                snapshot.getSnapshotId(),
                                question.getRelatedMatch().getSnapshot().getSnapshotId()
                        ))
        );
        if (invalid) {
            throw integrityConflict();
        }
    }

    private CompanyFitQuestionSetResponse.Question toQuestionResponse(InterviewQuestion question) {
        return CompanyFitQuestionSetResponse.Question.builder()
                .questionId(question.getQuestionId())
                .displayOrder(question.getDisplayOrder())
                .questionType(question.getQuestionType().name())
                .question(question.getQuestion())
                .intent(question.getIntent())
                .evaluationFocus(question.getEvaluationFocus())
                .relatedMatchId(question.getRelatedMatch() == null
                        ? null
                        : question.getRelatedMatch().getMatchId())
                .relatedRequirementId(question.getRelatedRequirementId())
                .sourceRefs(question.getSourceRefs())
                .build();
    }

    private CustomException integrityConflict() {
        return new CustomException(ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT);
    }
}
