package com.example.jobpuzzle.ai.validation;

import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.ai.dto.SourceReference;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Provider 원시 JSON을 엄격 파싱하고 v13 구조·근거 계약을 검증한다.
@Component
public class AiResponseProcessor {

    private static final Pattern JSON_CODE_BLOCK = Pattern.compile("^```json\\s*\\n([\\s\\S]*)\\n?```$");
    private static final int CANONICAL_EVIDENCE_MAX_LENGTH = 160;
    private static final Set<UserDocumentType> JOB_POSTING_TYPES = Set.of(
            UserDocumentType.JOB_POSTING, UserDocumentType.COMPANY_INFO
    );
    private static final Set<UserDocumentType> CANDIDATE_TYPES = Set.of(
            UserDocumentType.RESUME, UserDocumentType.COVER_LETTER,
            UserDocumentType.PORTFOLIO, UserDocumentType.EXPERIENCE_NOTE
    );

    private final ObjectMapper strictObjectMapper;
    private final AnalysisSourceMarkerParser markerParser;

    public AiResponseProcessor(ObjectMapper objectMapper, AnalysisSourceMarkerParser markerParser) {
        this.strictObjectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.markerParser = markerParser;
    }

    // JSON-01 응답은 공고·회사정보 marker에 대해서만 구조와 근거를 검증한다.
    public JobPostingAnalysisResult parseJobPosting(
            String rawJson,
            List<AnalysisInputSnapshotContextSource> sources
    ) {
        JobPostingAnalysisResult result = parse(rawJson, JobPostingAnalysisResult.class, "JSON-01");
        List<AnalysisSourceMarkerParser.SourceMarker> markers = markerParser.parseSources(sources);
        requireList("mainTasks", result.getMainTasks());
        requireList("requirements", result.getRequirements());
        requireList("preferred", result.getPreferred());
        requireList("companyValues", result.getCompanyValues());
        requireList("coreCompetencies", result.getCoreCompetencies());
        requireList("conflicts", result.getConflicts());
        requireList("missingEvidence", result.getMissingEvidence());
        unique("itemId", concat(result.getMainTasks(), result.getCompanyValues(), result.getCoreCompetencies()), JobPostingAnalysisResult.Item::getItemId);
        unique("requirementId", concat(result.getRequirements(), result.getPreferred()), JobPostingAnalysisResult.Requirement::getRequirementId);
        validateItems(result.getMainTasks(), markers, JOB_POSTING_TYPES, JobPostingAnalysisResult.Item::getItemId, JobPostingAnalysisResult.Item::getText, JobPostingAnalysisResult.Item::getSourceRefs);
        validateItems(result.getRequirements(), markers, JOB_POSTING_TYPES, JobPostingAnalysisResult.Requirement::getRequirementId, JobPostingAnalysisResult.Requirement::getText, JobPostingAnalysisResult.Requirement::getSourceRefs);
        validateItems(result.getPreferred(), markers, JOB_POSTING_TYPES, JobPostingAnalysisResult.Requirement::getRequirementId, JobPostingAnalysisResult.Requirement::getText, JobPostingAnalysisResult.Requirement::getSourceRefs);
        validateItems(result.getCompanyValues(), markers, JOB_POSTING_TYPES, JobPostingAnalysisResult.Item::getItemId, JobPostingAnalysisResult.Item::getText, JobPostingAnalysisResult.Item::getSourceRefs);
        validateItems(result.getCoreCompetencies(), markers, JOB_POSTING_TYPES, JobPostingAnalysisResult.Item::getItemId, JobPostingAnalysisResult.Item::getText, JobPostingAnalysisResult.Item::getSourceRefs);
        // conflict는 양쪽 문서 근거가 있어야만 의미가 있는 보조 판단이다. 한쪽 근거만 있거나
        // marker를 잘못 고른 conflict 하나 때문에 핵심 공고 분석 전체를 폐기하지 않는다.
        // 서버가 근거를 만들어 붙이지 않고 검증 불가능한 conflict 항목만 제외한다.
        result.setConflicts(result.getConflicts().stream()
                .filter(conflict -> isVerifiableConflict(conflict, markers))
                .toList());
        validateMissingEvidence(result.getMissingEvidence(), "missingEvidence");
        // 지원 제한이 없다는 안내 문구는 충족도·질문·액션플랜의 평가 대상에서 제외한다.
        result.setRequirements(filterNonEvaluativeRequirements(result.getRequirements()));
        result.setPreferred(filterNonEvaluativeRequirements(result.getPreferred()));
        return result;
    }

    // 실제 학력·경력 조건과 혼동하지 않도록 명시적인 무관 문구만 제한적으로 제거한다.
    private List<JobPostingAnalysisResult.Requirement> filterNonEvaluativeRequirements(
            List<JobPostingAnalysisResult.Requirement> requirements
    ) {
        return requirements.stream()
                .filter(requirement -> !isNonEvaluativeRequirement(requirement.getText()))
                .toList();
    }

    private boolean isNonEvaluativeRequirement(String text) {
        if (text == null) return false;
        String normalized = text.replaceAll("[·•]", " ").replaceAll("\\s+", " ").trim();
        return normalized.matches("^(학력|경력|성별|연령)\\s*[:：-]?\\s*(무관|제한 없음)$");
    }

    // JSON-02 응답은 실제 선택된 지원자 자료 유형과 marker를 모두 echo-only로 검증한다.
    public CandidateMaterialAnalysisResult parseCandidateMaterial(
            String rawJson,
            List<AnalysisInputSnapshotContextSource> sources
    ) {
        CandidateMaterialAnalysisResult result = parse(
                normalizeCandidateSourceReferences(rawJson), CandidateMaterialAnalysisResult.class, "JSON-02");
        assignServerIssuedTechnicalIds(result);
        List<AnalysisSourceMarkerParser.SourceMarker> markers = markerParser.parseSources(sources);
        Set<UserDocumentType> actualTypes = sources.stream().map(AnalysisInputSnapshotContextSource::getDocumentType)
                .collect(java.util.stream.Collectors.toSet());
        if (!CANDIDATE_TYPES.containsAll(actualTypes)) {
            throw validation("invalid candidate document type");
        }
        normalizeCandidateSections(result, actualTypes);
        validateResume(result.getResume(), actualTypes.contains(UserDocumentType.RESUME), markers);
        validateCoverLetter(result.getCoverLetter(), actualTypes.contains(UserDocumentType.COVER_LETTER), markers);
        validatePortfolio(result.getPortfolio(), actualTypes.contains(UserDocumentType.PORTFOLIO), markers);
        validateExperienceNote(result.getExperienceNote(), actualTypes.contains(UserDocumentType.EXPERIENCE_NOTE), markers);
        validateMissingEvidence(result.getMissingEvidence(), "missingEvidence");
        return result;
    }

    private void normalizeCandidateSections(CandidateMaterialAnalysisResult result, Set<UserDocumentType> actualTypes) {
        result.setAvailableDocumentTypes(actualTypes.stream().sorted().toList());
        if (result.getMissingEvidence() == null) result.setMissingEvidence(new ArrayList<>());
        if (actualTypes.contains(UserDocumentType.RESUME)) {
            if (result.getResume() == null) result.setResume(new CandidateMaterialAnalysisResult.Resume());
            if (result.getResume().getExperiences() == null) result.getResume().setExperiences(new ArrayList<>());
            if (result.getResume().getSkills() == null) result.getResume().setSkills(new ArrayList<>());
            if (result.getResume().getRoles() == null) result.getResume().setRoles(new ArrayList<>());
            if (result.getResume().getResults() == null) result.getResume().setResults(new ArrayList<>());
        } else result.setResume(null);
        if (actualTypes.contains(UserDocumentType.COVER_LETTER)) {
            if (result.getCoverLetter() == null) result.setCoverLetter(new CandidateMaterialAnalysisResult.CoverLetter());
            if (result.getCoverLetter().getExperienceNarratives() == null)
                result.getCoverLetter().setExperienceNarratives(new ArrayList<>());
        } else result.setCoverLetter(null);
        if (actualTypes.contains(UserDocumentType.PORTFOLIO)) {
            if (result.getPortfolio() == null) result.setPortfolio(new CandidateMaterialAnalysisResult.Portfolio());
            if (result.getPortfolio().getProjects() == null) result.getPortfolio().setProjects(new ArrayList<>());
        } else result.setPortfolio(null);
        if (actualTypes.contains(UserDocumentType.EXPERIENCE_NOTE)) {
            if (result.getExperienceNote() == null)
                result.setExperienceNote(new CandidateMaterialAnalysisResult.ExperienceNote());
            if (result.getExperienceNote().getStarCandidates() == null)
                result.getExperienceNote().setStarCandidates(new ArrayList<>());
        } else result.setExperienceNote(null);
    }

    private boolean isVerifiableConflict(
            JobPostingAnalysisResult.Conflict conflict,
            List<AnalysisSourceMarkerParser.SourceMarker> markers
    ) {
        if (conflict == null
                || blank(conflict.getField())
                || blank(conflict.getPostingValue())
                || blank(conflict.getCompanyInfoValue())
                || blank(conflict.getAppliedValue())) {
            return false;
        }
        try {
            validateRefs(conflict.getSourceRefs(), markers, JOB_POSTING_TYPES, "conflicts.sourceRefs");
        } catch (AiProcessingException ignored) {
            return false;
        }
        Set<UserDocumentType> types = conflict.getSourceRefs().stream()
                .map(SourceReference::getDocumentType)
                .collect(java.util.stream.Collectors.toSet());
        return types.contains(UserDocumentType.JOB_POSTING)
                && types.contains(UserDocumentType.COMPANY_INFO);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // Structured Output 전용 sourceRef(필수) + additionalSourceRefs(추가) 형식을 기존 DTO의
    // sourceRefs 배열로 복원한다. Mock/fake와 기존 저장 payload의 sourceRefs 배열은 그대로 허용한다.
    private String normalizeCandidateSourceReferences(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            throw parseFailure("JSON-02 empty response");
        }
        try {
            JsonNode root = strictObjectMapper.readTree(extractSingleObject(rawJson, "JSON-02"));
            if (!(root instanceof ObjectNode object)) {
                throw parseFailure("JSON-02 must be a JSON object");
            }
            normalizeCandidateSourceReferences(object);
            return strictObjectMapper.writeValueAsString(object);
        } catch (JsonProcessingException exception) {
            throw parseFailure("JSON-02 source reference normalization failed: " + exception.getClass().getSimpleName());
        }
    }

    private void normalizeCandidateSourceReferences(JsonNode node) {
        if (node instanceof ObjectNode object) {
            boolean hasPrimary = object.has("sourceRef");
            boolean hasAdditional = object.has("additionalSourceRefs");
            if (hasPrimary || hasAdditional) {
                if (!hasPrimary || !hasAdditional || object.has("sourceRefs")) {
                    throw validation("JSON-02 source reference fields must use sourceRef and additionalSourceRefs together");
                }
                JsonNode primary = object.get("sourceRef");
                JsonNode additional = object.get("additionalSourceRefs");
                if (!primary.isObject() || !additional.isArray()) {
                    throw validation("JSON-02 sourceRef must be an object and additionalSourceRefs must be an array");
                }
                ArrayNode refs = strictObjectMapper.createArrayNode();
                refs.add(primary);
                additional.forEach(refs::add);
                object.remove("sourceRef");
                object.remove("additionalSourceRefs");
                object.set("sourceRefs", refs);
            }
            object.elements().forEachRemaining(this::normalizeCandidateSourceReferences);
        } else if (node instanceof ArrayNode array) {
            array.elements().forEachRemaining(this::normalizeCandidateSourceReferences);
        }
    }

    // JSON-02 partition schema는 모델 생성 ID를 받지 않는다. 최종 merger도 원문 순서·사실 key로
    // 다시 번호를 부여하므로, 이 값은 partition 내 DTO 검증을 위한 안정적인 기술 식별자다.
    private void assignServerIssuedTechnicalIds(CandidateMaterialAnalysisResult result) {
        if (result.getResume() != null && result.getResume().getExperiences() != null) {
            for (int index = 0; index < result.getResume().getExperiences().size(); index++) {
                result.getResume().getExperiences().get(index).setExperienceId("exp-" + (index + 1));
            }
        }
        if (result.getPortfolio() != null && result.getPortfolio().getProjects() != null) {
            for (int index = 0; index < result.getPortfolio().getProjects().size(); index++) {
                result.getPortfolio().getProjects().get(index).setProjectId("project-" + (index + 1));
            }
        }
        if (result.getExperienceNote() != null && result.getExperienceNote().getStarCandidates() != null) {
            for (int index = 0; index < result.getExperienceNote().getStarCandidates().size(); index++) {
                result.getExperienceNote().getStarCandidates().get(index).setCandidateId("star-" + (index + 1));
            }
        }
    }

    public com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisV18ProviderResult
    parseCustomizedAnalysisV18(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) throw parseFailure("JSON-05 v1.8 empty response");
        try {
            return strictObjectMapper.readValue(extractSingleObject(rawJson, "JSON-05 v1.8"),
                    com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisV18ProviderResult.class);
        } catch (JsonProcessingException exception) {
            throw parseFailure("JSON-05 v1.8 parse failed: " + exception.getClass().getSimpleName());
        }
    }

    // 평가 명세 v2 5장. 형식만 확인하고, 항목 구성·점수 범위는 AnswerEvaluationV2Validator가 본다.
    public com.example.jobpuzzle.ai.dto.AnswerEvaluationV2Result parseAnswerEvaluationV2(String rawJson) {
        return parse(rawJson, com.example.jobpuzzle.ai.dto.AnswerEvaluationV2Result.class, "JSON-06-V2");
    }

    public com.example.jobpuzzle.ai.dto.QuestionGenerationV2Result parseQuestionGenerationV2(String rawJson) {
        return parse(rawJson, com.example.jobpuzzle.ai.dto.QuestionGenerationV2Result.class, "JSON-12");
    }

    // 응답 전체가 json 코드 블록인 경우만 제거하고 그 밖의 복구는 하지 않는다.
    private <T> T parse(String rawJson, Class<T> type, String contractName) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            throw parseFailure(contractName + " empty response");
        }
        String normalized = rawJson.trim();
        Matcher block = JSON_CODE_BLOCK.matcher(normalized);
        if (block.matches()) {
            normalized = block.group(1).trim();
        }
        try {
            // JSON-01·02도 JSON-05와 같이 단일 객체만 추출한다. 설명문은 허용하지만 복수 객체·잘린 객체는 복구하지 않는다.
            return strictObjectMapper.readValue(extractSingleObject(normalized, contractName), type);
        } catch (JsonProcessingException exception) {
            // 원문 응답은 저장하지 않는다. 잘림·형식 오류를 구분할 수 있는 응답 형태와 parser 위치만 남긴다.
            throw parseFailure(contractName + " parse failed: " + parseDiagnostic(normalized, exception));
        }
    }

    private String parseDiagnostic(String normalized, JsonProcessingException exception) {
        long offset = exception.getLocation() == null ? -1 : exception.getLocation().getCharOffset();
        return exception.getClass().getSimpleName()
                + "; chars=" + normalized.length()
                + "; first=" + characterKind(normalized.charAt(0))
                + "; last=" + characterKind(normalized.charAt(normalized.length() - 1))
                + "; offset=" + offset;
    }

    private String characterKind(char value) {
        if (value == '{') return "OBJECT_OPEN";
        if (value == '}') return "OBJECT_CLOSE";
        if (value == '[') return "ARRAY_OPEN";
        if (value == ']') return "ARRAY_CLOSE";
        if (value == '"') return "QUOTE";
        if (Character.isWhitespace(value)) return "WHITESPACE";
        return "OTHER";
    }

    private String extractSingleObject(String rawJson, String contractName) {
        String normalized = rawJson.trim();
        Matcher block = JSON_CODE_BLOCK.matcher(normalized);
        if (block.matches()) normalized = block.group(1).trim();
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
                continue;
            }
            if (character == '"') {
                quoted = true;
                continue;
            }
            if (character == '{') {
                if (depth++ == 0) start = index;
            } else if (character == '}' && depth > 0 && --depth == 0) {
                objects.add(normalized.substring(start, index + 1));
            }
        }
        if (depth != 0 || objects.size() != 1) {
            // 원문은 남기지 않고, 완결 객체 부재가 잘림인지 복수 응답인지 구분할 수 있는 형태만 기록한다.
            throw parseFailure(contractName + " must contain exactly one JSON object; chars=" + normalized.length()
                    + "; completedObjects=" + objects.size() + "; openDepth=" + depth
                    + "; first=" + characterKind(normalized.charAt(0))
                    + "; last=" + characterKind(normalized.charAt(normalized.length() - 1)));
        }
        return objects.get(0);
    }

    private void validateResume(CandidateMaterialAnalysisResult.Resume resume, boolean selected, List<AnalysisSourceMarkerParser.SourceMarker> markers) {
        if (!selected) {
            requireAbsent("resume", resume);
            return;
        }
        if (resume == null) throw validation("resume is required");
        requireList("resume.experiences", resume.getExperiences());
        requireList("resume.skills", resume.getSkills());
        requireList("resume.roles", resume.getRoles());
        requireList("resume.results", resume.getResults());
        // 한 개의 불완전한 요약 항목이 partition 전체를 폐기하지 않도록 의미 없는 항목만 제외한다.
        resume.getExperiences().removeIf(item -> item == null || blank(item.getTitle()) || blank(item.getSummary()));
        resume.getSkills().removeIf(item -> item == null || blank(item.getSkill()) || blank(item.getUsageContext()));
        resume.getRoles().removeIf(item -> item == null || blank(item.getRole()) || blank(item.getContext()));
        resume.getResults().removeIf(item -> item == null || blank(item.getResult()));
        unique("experienceId", resume.getExperiences(), CandidateMaterialAnalysisResult.Experience::getExperienceId);
        validateItems(resume.getExperiences(), markers, CANDIDATE_TYPES, CandidateMaterialAnalysisResult.Experience::getExperienceId, CandidateMaterialAnalysisResult.Experience::getTitle, CandidateMaterialAnalysisResult.Experience::getSourceRefs);
        for (CandidateMaterialAnalysisResult.Experience item : resume.getExperiences()) {
            requireText("resume.experiences.summary", item.getSummary());
        }
        validateItems(resume.getSkills(), markers, CANDIDATE_TYPES, CandidateMaterialAnalysisResult.Skill::getSkill, CandidateMaterialAnalysisResult.Skill::getUsageContext, CandidateMaterialAnalysisResult.Skill::getSourceRefs);
        validateItems(resume.getRoles(), markers, CANDIDATE_TYPES, CandidateMaterialAnalysisResult.Role::getRole, CandidateMaterialAnalysisResult.Role::getContext, CandidateMaterialAnalysisResult.Role::getSourceRefs);
        for (CandidateMaterialAnalysisResult.Result item : resume.getResults()) {
            requireText("resume.results.result", item.getResult());
            validateRefs(item.getSourceRefs(), markers, CANDIDATE_TYPES, "resume.results.sourceRefs");
        }
    }

    private void validateCoverLetter(CandidateMaterialAnalysisResult.CoverLetter coverLetter, boolean selected, List<AnalysisSourceMarkerParser.SourceMarker> markers) {
        if (!selected) {
            requireAbsent("coverLetter", coverLetter);
            return;
        }
        if (coverLetter == null) throw validation("coverLetter is required");
        if (coverLetter.getMotivation() != null && blank(coverLetter.getMotivation().getSummary()))
            coverLetter.setMotivation(null);
        if (coverLetter.getValues() != null && blank(coverLetter.getValues().getSummary()))
            coverLetter.setValues(null);
        if (coverLetter.getJobConnection() != null && blank(coverLetter.getJobConnection().getSummary()))
            coverLetter.setJobConnection(null);
        coverLetter.getExperienceNarratives().removeIf(item -> item == null || blank(item.getSummary()));
        validateSummary(coverLetter.getMotivation(), markers, "coverLetter.motivation");
        validateSummary(coverLetter.getValues(), markers, "coverLetter.values");
        requireList("coverLetter.experienceNarratives", coverLetter.getExperienceNarratives());
        coverLetter.getExperienceNarratives().forEach(item -> validateSummary(item, markers, "coverLetter.experienceNarratives"));
        validateSummary(coverLetter.getJobConnection(), markers, "coverLetter.jobConnection");
    }

    private void validatePortfolio(CandidateMaterialAnalysisResult.Portfolio portfolio, boolean selected, List<AnalysisSourceMarkerParser.SourceMarker> markers) {
        if (!selected) {
            requireAbsent("portfolio", portfolio);
            return;
        }
        if (portfolio == null) throw validation("portfolio is required");
        requireList("portfolio.projects", portfolio.getProjects());
        portfolio.getProjects().removeIf(project -> project == null
                || blank(project.getProjectName()) || blank(project.getStructure()) || blank(project.getRole()));
        unique("projectId", portfolio.getProjects(), CandidateMaterialAnalysisResult.Project::getProjectId);
        for (CandidateMaterialAnalysisResult.Project project : portfolio.getProjects()) {
            requireText("portfolio.projects.projectId", project.getProjectId());
            requireText("portfolio.projects.projectName", project.getProjectName());
            requireText("portfolio.projects.structure", project.getStructure());
            requireText("portfolio.projects.role", project.getRole());
            requireList("portfolio.projects.contributions", project.getContributions());
            requireList("portfolio.projects.techUsageReasons", project.getTechUsageReasons());
            requireList("portfolio.projects.problemSolving", project.getProblemSolving());
            requireList("portfolio.projects.outputs", project.getOutputs());
            validateRefs(project.getSourceRefs(), markers, CANDIDATE_TYPES, "portfolio.projects.sourceRefs");
        }
    }

    private void validateExperienceNote(CandidateMaterialAnalysisResult.ExperienceNote note, boolean selected, List<AnalysisSourceMarkerParser.SourceMarker> markers) {
        if (!selected) {
            requireAbsent("experienceNote", note);
            return;
        }
        if (note == null) throw validation("experienceNote is required");
        requireList("experienceNote.starCandidates", note.getStarCandidates());
        unique("candidateId", note.getStarCandidates(), CandidateMaterialAnalysisResult.StarCandidate::getCandidateId);
        for (CandidateMaterialAnalysisResult.StarCandidate candidate : note.getStarCandidates()) {
            requireText("experienceNote.starCandidates.candidateId", candidate.getCandidateId());
            requireList("experienceNote.starCandidates.missingParts", candidate.getMissingParts());
            validateRefs(candidate.getSourceRefs(), markers, CANDIDATE_TYPES, "experienceNote.starCandidates.sourceRefs");
        }
    }

    private void validateSummary(CandidateMaterialAnalysisResult.SummaryEvidence value, List<AnalysisSourceMarkerParser.SourceMarker> markers, String field) {
        if (value == null) return;
        requireText(field + ".summary", value.getSummary());
        validateRefs(value.getSourceRefs(), markers, CANDIDATE_TYPES, field + ".sourceRefs");
    }

    private <T> void validateItems(List<T> items, List<AnalysisSourceMarkerParser.SourceMarker> markers, Set<UserDocumentType> allowedTypes,
                                   Function<T, String> id, Function<T, String> text, Function<T, List<SourceReference>> refs) {
        for (T item : items) {
            requireText("item id", id.apply(item));
            requireText("item text", text.apply(item));
            validateRefs(refs.apply(item), markers, allowedTypes, "sourceRefs");
        }
    }

    // sourceRefs는 JSON-03 marker에 있던 동일한 ID·문서유형·페이지·세그먼트만 echo할 수 있다.
    private void validateRefs(List<SourceReference> refs, List<AnalysisSourceMarkerParser.SourceMarker> markers,
                              Set<UserDocumentType> allowedTypes, String field) {
        if (refs == null || refs.isEmpty()) invalidSource(field, "must contain at least one reference");
        for (SourceReference ref : refs) {
            if (ref == null || ref.getExtractionId() == null || ref.getDocumentId() == null || ref.getDocumentType() == null
                    || ref.getSegmentId() == null || ref.getSegmentId().isBlank()) {
                invalidSource(field, "missing required reference field");
            }
            if (!allowedTypes.contains(ref.getDocumentType()))
                invalidSource(field, "documentType is not allowed: " + ref.getDocumentType());
            AnalysisSourceMarkerParser.SourceMarker marker = markers.stream()
                    .filter(value -> value.extractionId().equals(ref.getExtractionId()))
                    .filter(value -> value.documentId().equals(ref.getDocumentId()))
                    .filter(value -> value.documentType() == ref.getDocumentType())
                    .filter(value -> java.util.Objects.equals(value.pageNumber(), ref.getPageNumber()))
                    .filter(value -> java.util.Objects.equals(value.segmentId(), ref.getSegmentId()))
                    .findFirst().orElse(null);
            // Provider가 extraction/document identity를 잘못 echo해도 segmentId가 현재 partition에서
            // 유일하면 서버 marker로 결정적으로 복원한다. 중복 segment면 추측하지 않고 실패한다.
            if (marker == null && (ref.getExtractionId() <= 0 || ref.getDocumentId() <= 0)) {
                List<AnalysisSourceMarkerParser.SourceMarker> sameSegment = markers.stream()
                        .filter(value -> allowedTypes.contains(value.documentType()))
                        .filter(value -> java.util.Objects.equals(value.segmentId(), ref.getSegmentId()))
                        .toList();
                if (sameSegment.size() == 1) {
                    marker = sameSegment.getFirst();
                    ref.setExtractionId(marker.extractionId());
                    ref.setDocumentId(marker.documentId());
                    ref.setDocumentType(marker.documentType());
                    ref.setPageNumber(marker.pageNumber());
                    ref.setSegmentId(marker.segmentId());
                }
            }
            if (marker == null)
                invalidSource(field, "unknown marker extractionId=" + ref.getExtractionId() + ", segmentId=" + ref.getSegmentId());
            // 모델은 marker 식별자만 선택한다. 표시·저장할 원문 발췌는 서버가 marker 원문에서
            // 결정적으로 채워, 모델의 재서술·문자 정규화 때문에 유효한 근거가 거절되지 않게 한다.
            ref.setEvidenceText(canonicalEvidenceText(marker));
            if (!marker.segmentText().contains(ref.getEvidenceText()))
                invalidSource(field, "evidenceText is not in segmentId=" + ref.getSegmentId());
        }
    }

    private String canonicalEvidenceText(AnalysisSourceMarkerParser.SourceMarker marker) {
        String text = marker.segmentText();
        if (text == null || text.isBlank()) {
            throw validation("marker segmentText must not be blank");
        }
        return text.length() <= CANONICAL_EVIDENCE_MAX_LENGTH
                ? text
                : text.substring(0, CANONICAL_EVIDENCE_MAX_LENGTH);
    }

    private void validateMissingEvidence(List<?> missingEvidence, String field) {
        for (Object item : missingEvidence) {
            if (item instanceof JobPostingAnalysisResult.MissingEvidence value) {
                requireText(field + ".item", value.getItem());
                requireText(field + ".reason", value.getReason());
            } else if (item instanceof CandidateMaterialAnalysisResult.MissingEvidence value) {
                requireText(field + ".item", value.getItem());
                requireText(field + ".reason", value.getReason());
            }
        }
    }

    private <T> void unique(String field, List<T> values, Function<T, String> id) {
        Set<String> ids = new HashSet<>();
        for (T value : values) {
            String identifier = id.apply(value);
            requireText(field, identifier);
            if (!ids.add(identifier)) throw validation("duplicate " + field + ": " + identifier);
        }
    }

    @SafeVarargs
    private final <T> List<T> concat(List<T>... values) {
        List<T> result = new ArrayList<>();
        for (List<T> value : values) result.addAll(value);
        return result;
    }

    private void requireList(String field, List<?> values) {
        if (values == null) throw validation(field + " must not be null");
    }

    private void requireText(String field, String value) {
        if (value == null || value.isBlank()) throw validation(field + " must not be blank");
    }

    private void requireAbsent(String field, Object value) {
        if (value != null) throw validation(field + " exists without selected document type");
    }

    private AiProcessingException parseFailure(String message) {
        return new AiProcessingException(AiCallLogErrorType.RESPONSE_PARSE_FAILED, message);
    }

    private AiProcessingException validation(String message) {
        return new AiProcessingException(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED, message);
    }

    private void invalidSource(String field, String detail) {
        throw new AiProcessingException(AiCallLogErrorType.SOURCE_REFERENCE_INVALID, field + ": " + detail);
    }
}
