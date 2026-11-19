package com.example.jobpuzzle.analysis.synthesis.service;

import com.example.jobpuzzle.ai.dto.SourceReference;
import com.example.jobpuzzle.analysis.entity.RequirementType;
import com.example.jobpuzzle.analysis.synthesis.dto.*;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.guide.entity.GuideMatchType;
import com.example.jobpuzzle.interview.entity.InterviewQuestionEvaluationFocus;
import com.example.jobpuzzle.interview.entity.InterviewQuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CustomizedSynthesisV18ResultAssemblerTest {
    private final CustomizedSynthesisV18ResultAssembler assembler =
            new CustomizedSynthesisV18ResultAssembler(new CustomizedSynthesisResultAssembler());

    @Test
    void normalizesHighNarrativeInsteadOfRejectingNonBlankMissingPoint() {
        CustomizedSynthesisV18ProviderResult provider = new CustomizedSynthesisV18ProviderResult(
                new CustomizedSynthesisV18ProviderResult.Readiness("준비됨", List.of()),
                Map.of("req-1", "HIGH::cand-1"),
                Map.of("req-1", new CustomizedSynthesisV18ProviderResult.Narrative(
                        "충족", "모델이 잘못 넣은 부족점", "후보 근거", "불필요한 과제")),
                List.of(new CustomizedSynthesisV18ProviderResult.QuestionSlot(
                        "req-1", InterviewQuestionType.COMPANY_FIT, "설명해 주세요", "검증",
                        InterviewQuestionEvaluationFocus.requirementConnection, "cand-1")));

        var result = assembler.assemble(provider, input(), authority(), GuideMatchType.EXACT);

        assertThat(result.getRequirementMatches()).singleElement().satisfies(match -> {
            assertThat(match.getMissingPoint()).isNull();
            assertThat(match.getCandidateEvidence()).isEqualTo("후보 근거");
        });
        assertThat(result.getTasks()).isEmpty();
    }

    private CustomizedSynthesisProviderInput input() {
        return new CustomizedSynthesisProviderInput(null,
                List.of(new CustomizedSynthesisProviderInput.RequirementItem(
                        "req-1", RequirementType.REQUIRED, "Java",
                        List.of("post-1"), List.of("cand-1"))),
                List.of(), null,
                new CustomizedSynthesisProviderEvidenceCatalog(List.of(
                        new CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem(
                                "post-1", CustomizedSynthesisEvidenceCatalog.EvidenceRole.POSTING, "공고 근거"),
                        new CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem(
                                "cand-1", CustomizedSynthesisEvidenceCatalog.EvidenceRole.CANDIDATE, "후보 근거"))),
                new CustomizedSynthesisProviderInput.GenerationPolicy(true, 1));
    }

    private CustomizedSynthesisEvidenceCatalog authority() {
        return new CustomizedSynthesisEvidenceCatalog(
                List.of(new CustomizedSynthesisEvidenceCatalog.RequirementItem(
                        "req-1", RequirementType.REQUIRED, "Java",
                        List.of("post-1"), List.of("cand-1"))),
                List.of(
                        new CustomizedSynthesisEvidenceCatalog.EvidenceItem(
                                "post-1", CustomizedSynthesisEvidenceCatalog.EvidenceRole.POSTING,
                                source(1L, UserDocumentType.JOB_POSTING, "공고 근거")),
                        new CustomizedSynthesisEvidenceCatalog.EvidenceItem(
                                "cand-1", CustomizedSynthesisEvidenceCatalog.EvidenceRole.CANDIDATE,
                                source(2L, UserDocumentType.RESUME, "후보 근거"))));
    }

    private SourceReference source(Long id, UserDocumentType type, String text) {
        return SourceReference.builder().extractionId(id).documentId(id).documentType(type)
                .pageNumber(1).segmentId("segment-" + id).evidenceText(text).build();
    }
}
