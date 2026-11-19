package com.example.jobpuzzle.ai.prompt;

import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContext;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.analysis.rag.dto.RetrievedEvidenceContextDto;
import com.example.jobpuzzle.analysis.rag.dto.CustomizedSynthesisEvidenceProjection;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderInput;
import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.guide.dto.GuideContextResultDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// PromptTemplate의 명시적 변수를 JSON-03 자료와 직무 기준으로 안전하게 렌더링한다.
@Component
public class PromptTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");
    private final ObjectMapper objectMapper;

    public PromptTemplateRenderer() {
        this(new ObjectMapper());
    }

    @Autowired
    public PromptTemplateRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // v1.3은 단일 provider input DTO만 렌더링하며 정본 밖의 후행 계약 지시를 추가하지 않는다.
    public String renderCustomizedAnalysisV13(PromptTemplate template, CustomizedSynthesisProviderInput input) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("synthesisInputJson", json("synthesisInputJson", input));
        return render(template, values);
    }

    // 템플릿 필수 변수를 치환하고 남은 placeholder가 있으면 Provider 호출 전에 실패시킨다.
    public String render(
            AiExecutionStage stage,
            PromptTemplate template,
            AnalysisInputSnapshotContext context,
            List<AnalysisInputSnapshotContextSource> primarySources,
            List<AnalysisInputSnapshotContextSource> supplementarySources
    ) {
        Map<String, String> values = variables(stage, context, primarySources, supplementarySources);
        return render(template, values);
    }

    private String render(PromptTemplate template, Map<String, String> values) {
        for (String required : values.keySet()) {
            if (!template.getTemplateText().contains("{{" + required + "}}")) {
                throw new AiProcessingException(AiCallLogErrorType.PROMPT_RENDER_FAILED,
                        "missing template variable: " + required);
            }
        }
        Matcher matcher = PLACEHOLDER.matcher(template.getTemplateText());
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new AiProcessingException(AiCallLogErrorType.PROMPT_RENDER_FAILED,
                        "unknown template variable: " + matcher.group(1));
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        if (PLACEHOLDER.matcher(rendered).find()) {
            throw new AiProcessingException(AiCallLogErrorType.PROMPT_RENDER_FAILED, "unresolved template placeholder");
        }
        if (template.getForbiddenRules() != null && !template.getForbiddenRules().isBlank()) {
            rendered.append("\n\n[FORBIDDEN_RULES]\n").append(template.getForbiddenRules());
        }
        return rendered.toString().trim();
    }

    private String json(String name, Object value) {
        if (value == null) {
            throw new AiProcessingException(AiCallLogErrorType.PROMPT_RENDER_FAILED, "missing render value: " + name);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AiProcessingException(AiCallLogErrorType.PROMPT_RENDER_FAILED, "JSON serialization failed: " + name);
        }
    }

    // fingerprint는 렌더링 전에도 동일 입력을 식별할 수 있도록 변수 값까지 포함한다.
    public String fingerprintMaterial(
            AiExecutionStage stage,
            PromptTemplate template,
            AnalysisInputSnapshotContext context,
            List<AnalysisInputSnapshotContextSource> primarySources,
            List<AnalysisInputSnapshotContextSource> supplementarySources
    ) {
        StringBuilder material = new StringBuilder(template.getTemplateText());
        variables(stage, context, primarySources, supplementarySources)
                .forEach((key, value) -> material.append("\n").append(key).append("=").append(value));
        return material.toString();
    }

    private Map<String, String> variables(
            AiExecutionStage stage,
            AnalysisInputSnapshotContext context,
            List<AnalysisInputSnapshotContextSource> primarySources,
            List<AnalysisInputSnapshotContextSource> supplementarySources
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("mainCategory", required("mainCategory", context.getMainCategory()));
        values.put("subCategory", required("subCategory", context.getSubCategory()));
        values.put("careerLevel", required("careerLevel", String.valueOf(context.getCareerLevel())));
        if (stage == AiExecutionStage.JOB_POSTING_ANALYSIS) {
            values.put("jobPostingAnalysisText", sources(UserDocumentType.JOB_POSTING, primarySources));
            values.put("companyInfoAnalysisTexts", sources(UserDocumentType.COMPANY_INFO, supplementarySources));
        } else {
            values.put("resumeAnalysisTexts", sources(UserDocumentType.RESUME, primarySources));
            values.put("coverLetterAnalysisTexts", sources(UserDocumentType.COVER_LETTER, primarySources));
            values.put("portfolioAnalysisTexts", sources(UserDocumentType.PORTFOLIO, primarySources));
            values.put("experienceNoteAnalysisTexts", sources(UserDocumentType.EXPERIENCE_NOTE, primarySources));
        }
        return values;
    }

    private String sources(UserDocumentType type, List<AnalysisInputSnapshotContextSource> sources) {
        return sources.stream()
                .filter(source -> source.getDocumentType() == type)
                .map(source -> "[" + type + "]\n"
                        + "displayName=" + source.getDisplayName() + "\n" + source.getAnalysisText())
                .reduce("", (left, right) -> left.isBlank() ? right : left + "\n\n" + right);
    }

    private String required(String name, String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            throw new AiProcessingException(AiCallLogErrorType.PROMPT_RENDER_FAILED,
                    "missing render value: " + name);
        }
        return value;
    }
}
