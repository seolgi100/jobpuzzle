package com.example.jobpuzzle.guide.service;

import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.entity.JobPostingAnalysis;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.analysis.repository.JobPostingAnalysisRepository;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.guide.dto.GuideContextResultDto;
import com.example.jobpuzzle.guide.entity.*;
import com.example.jobpuzzle.guide.repository.GuideContextChunkRepository;
import com.example.jobpuzzle.guide.repository.GuideContextResultRepository;
import com.example.jobpuzzle.guide.repository.JobGuideChunkRepository;
import com.example.jobpuzzle.guide.repository.JobGuideDocumentRepository;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import com.example.jobpuzzle.guide.config.GuideVectorProperties;
import com.example.jobpuzzle.guide.vector.GuideVectorHit;
import com.example.jobpuzzle.guide.vector.GuideVectorStorePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// JSON-04 가이드 검색 결과를 스냅샷 단위로 고정·재사용하는 운영 서비스다.
@Service
@RequiredArgsConstructor
public class GuideContextService {

    private final AnalysisInputSnapshotRepository analysisInputSnapshotRepository;
    private final JobGuideDocumentRepository jobGuideDocumentRepository;
    private final JobGuideChunkRepository jobGuideChunkRepository;
    private final GuideContextResultRepository guideContextResultRepository;
    private final GuideContextChunkRepository guideContextChunkRepository;
    private final JobPostingAnalysisRepository jobPostingAnalysisRepository;
    private final GuideVectorStorePort guideVectorStore;
    private final GuideVectorProperties guideVectorProperties;

    // 사용자 소유 스냅샷에 대해 JSON-04 결과를 조회하거나 한 번만 생성한다.
    @Transactional
    public GuideContextResultDto getOrCreateCustomizedSynthesisGuideContext(Long userId, Long snapshotId) {
        // 같은 스냅샷의 동시 생성은 짧은 스냅샷 잠금으로 직렬화한다.
        AnalysisInputSnapshot snapshot = analysisInputSnapshotRepository.findWithLockBySnapshotId(snapshotId)
                .filter(value -> value.getUser().getUserId().equals(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.SNAPSHOT_NOT_FOUND));

        String inputReferenceId = String.valueOf(snapshotId);
        // 기존 JSON-04 결과는 이후 가이드 변경과 무관하게 그대로 재사용한다.
        GuideContextResult existing = findExisting(inputReferenceId);
        if (existing != null) {
            // 과거 정책이 남긴 NONE context는 정상 결과로 재사용하지 않고 분석을 중단한다.
            if (existing.getMatchType() == GuideMatchType.NONE) {
                throw new CustomException(ErrorCode.GUIDE_ACTIVE_NOT_FOUND);
            }
            return toDto(existing);
        }

        // 스냅샷에 고정된 직무·경력만으로 fallback 검색 기준을 만든다.
        JobCategory jobCategory = snapshot.getJobCategory();
        GuideSelection selection = findGuide(jobCategory);
        // 활성 가이드가 없으면 JSON-04 결과를 저장하지 않고 상위 파이프라인 실패 처리로 넘긴다.
        if (selection == null) {
            throw new CustomException(ErrorCode.GUIDE_ACTIVE_NOT_FOUND);
        }
        // flush 실패 뒤에는 같은 영속성 컨텍스트를 재조회하지 않고 트랜잭션을 종료시킨다.
        GuideContextResult result = guideContextResultRepository.saveAndFlush(
                GuideContextResult.create(snapshot.getUser(), inputReferenceId, jobCategory,
                        selection.guide(), selection.matchType()));
        List<GuideContextChunk> contextChunks =
                saveChunks(result, selection.guide(), snapshot, jobCategory);
        return GuideContextResultDto.from(result, contextChunks);
    }

    // 분석을 백그라운드로 넘기기 전에, 쓸 수 있는 가이드가 있는지만 빠르게 확인한다.
    // (AI를 부르기 전에 실패를 알려주고, 이 오류를 /run 응답으로 바로 전달하기 위함)
    @Transactional(readOnly = true)
    public void requireAvailableGuide(Long userId, Long analysisCaseId) {
        AnalysisInputSnapshot snapshot = analysisInputSnapshotRepository
                .findByAnalysisCase_AnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SNAPSHOT_NOT_FOUND));
        GuideContextResult existing = findExisting(String.valueOf(snapshot.getSnapshotId()));
        if (existing != null) {
            if (existing.getMatchType() == GuideMatchType.NONE) {
                throw new CustomException(ErrorCode.GUIDE_ACTIVE_NOT_FOUND);
            }
            return;
        }
        if (findGuide(snapshot.getJobCategory()) == null) {
            throw new CustomException(ErrorCode.GUIDE_ACTIVE_NOT_FOUND);
        }
    }

    private GuideContextResult findExisting(String inputReferenceId) {
        return guideContextResultRepository.findByPurposeAndInputReferenceTypeAndInputReferenceId(
                GuideContextPurpose.CUSTOMIZED_SYNTHESIS,
                GuideContextInputReferenceType.ANALYSIS_SNAPSHOT,
                inputReferenceId).orElse(null);
    }

    private GuideContextResultDto toDto(GuideContextResult result) {
        return GuideContextResultDto.from(result,
                guideContextChunkRepository.findByGuideContextResult_GuideContextResultIdOrderByDisplayOrderAsc(
                        result.getGuideContextResultId()));
    }

    private GuideSelection findGuide(JobCategory jobCategory) {
        // 대분류·중분류·경력이 모두 일치하는 CATEGORY 가이드를 먼저 찾는다.
        JobGuideDocument exact = oneCandidate(jobGuideDocumentRepository
                .findByScopeTypeAndJobCategory_MainCategoryAndJobCategory_SubCategoryAndJobCategory_CareerLevelAndStatus(
                        GuideScopeType.CATEGORY, jobCategory.getMainCategory(), jobCategory.getSubCategory(),
                        jobCategory.getCareerLevel(), JobGuideDocumentStatus.ACTIVE));
        if (exact != null) {
            return new GuideSelection(exact, GuideMatchType.EXACT);
        }

        // 동일 중분류의 ANY 경력 CATEGORY 가이드를 두 번째 fallback으로 찾는다.
        JobGuideDocument subcategoryFallback = oneCandidate(jobGuideDocumentRepository
                .findByScopeTypeAndJobCategory_MainCategoryAndJobCategory_SubCategoryAndJobCategory_CareerLevelAndStatus(
                        GuideScopeType.CATEGORY, jobCategory.getMainCategory(), jobCategory.getSubCategory(),
                        JobCategoryCareerLevel.ANY, JobGuideDocumentStatus.ACTIVE));
        if (subcategoryFallback != null) {
            return new GuideSelection(subcategoryFallback, GuideMatchType.FALLBACK_SAME_SUBCATEGORY);
        }

        // 대분류 전체에 적용되는 PARENT_CATEGORY 가이드를 세 번째로 찾는다.
        JobGuideDocument parentFallback = oneCandidate(jobGuideDocumentRepository
                .findByScopeTypeAndScopeMainCategoryAndStatus(GuideScopeType.PARENT_CATEGORY,
                        jobCategory.getMainCategory(), JobGuideDocumentStatus.ACTIVE));
        if (parentFallback != null) {
            return new GuideSelection(parentFallback, GuideMatchType.FALLBACK_PARENT_CATEGORY);
        }

        // 모든 직무에 적용되는 GLOBAL_COMMON 가이드를 마지막 fallback으로 찾는다.
        JobGuideDocument commonFallback = oneCandidate(jobGuideDocumentRepository
                .findByScopeTypeAndStatus(GuideScopeType.GLOBAL_COMMON, JobGuideDocumentStatus.ACTIVE));
        if (commonFallback != null) {
            return new GuideSelection(commonFallback, GuideMatchType.FALLBACK_COMMON);
        }
        // 가이드가 없으면 NONE fallback을 만들지 않고 호출자에게 명시적 오류를 전달한다.
        return null;
    }

    // 같은 검색 범위의 ACTIVE 가이드가 여러 건이면 임의 선택하지 않고 데이터 정합 오류로 처리한다.
    private JobGuideDocument oneCandidate(List<JobGuideDocument> candidates) {
        if (candidates.size() > 1) {
            throw new CustomException(ErrorCode.GUIDE_ACTIVE_DUPLICATED);
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 새로 인덱싱된 가이드는 채용공고 분석과 직무를 검색어로 사용해 관련 청크만 고정한다.
     * 과거 ACTIVE 가이드와 fake 재시작 환경은 기존 전체 청크 정책으로 안전하게 호환한다.
     */
    private List<GuideContextChunk> saveChunks(
            GuideContextResult result,
            JobGuideDocument guide,
            AnalysisInputSnapshot snapshot,
            JobCategory category
    ) {
        if (guide == null) {
            return List.of();
        }
        List<JobGuideChunk> chunks = jobGuideChunkRepository.findByGuide_GuideIdOrderByChunkIndexAsc(guide.getGuideId());
        List<ScoredGuideChunk> selected = selectChunks(guide, snapshot, category, chunks);
        List<GuideContextChunk> contextChunks = java.util.stream.IntStream.range(0, selected.size())
                .mapToObj(index -> GuideContextChunk.create(
                        result, selected.get(index).chunk(), selected.get(index).score(), index))
                .toList();
        return guideContextChunkRepository.saveAll(contextChunks);
    }

    private List<ScoredGuideChunk> selectChunks(
            JobGuideDocument guide,
            AnalysisInputSnapshot snapshot,
            JobCategory category,
            List<JobGuideChunk> chunks
    ) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        // 기존 ACTIVE 데이터는 embedding metadata가 없으므로 이전과 동일하게 전 청크를 사용한다.
        if (!guide.isIndexed() || guide.getEmbeddingProvider() == null) {
            return chunks.stream().map(chunk -> new ScoredGuideChunk(chunk, null)).toList();
        }

        List<GuideVectorHit> hits = guideVectorStore.search(
                guide.getGuideId(),
                buildSearchQuery(snapshot.getSnapshotId(), category),
                Math.max(1, Math.min(guideVectorProperties.getTopK(), chunks.size())));
        if (hits.isEmpty() && "fake".equalsIgnoreCase(guideVectorStore.provider())) {
            return chunks.stream().map(chunk -> new ScoredGuideChunk(chunk, null)).toList();
        }

        Map<Long, JobGuideChunk> byId = chunks.stream().collect(Collectors.toMap(
                JobGuideChunk::getChunkId, Function.identity()));
        List<ScoredGuideChunk> selected = hits.stream()
                .map(hit -> new ScoredGuideChunk(byId.remove(hit.chunkId()), hit.score()))
                .toList();
        if (selected.isEmpty() || selected.stream().anyMatch(value -> value.chunk() == null)) {
            throw new CustomException(ErrorCode.GUIDE_VECTOR_RESULT_INVALID);
        }
        return selected;
    }

    /** JSON-01에서 이미 검증·저장된 채용 요구사항만 검색어로 사용한다. */
    private String buildSearchQuery(Long snapshotId, JobCategory category) {
        JobPostingAnalysis posting = jobPostingAnalysisRepository.findBySnapshot_SnapshotId(snapshotId)
                .orElseThrow(() -> new CustomException(ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT));
        StringBuilder query = new StringBuilder()
                .append(category.getMainCategory()).append(' ')
                .append(category.getSubCategory()).append(' ')
                .append(category.getCareerLevel()).append('\n');
        appendTexts(query, posting.getMainTasks(), JobPostingAnalysis.Item::getText);
        appendTexts(query, posting.getRequirements(), JobPostingAnalysis.Requirement::getText);
        appendTexts(query, posting.getPreferred(), JobPostingAnalysis.Requirement::getText);
        appendTexts(query, posting.getCoreCompetencies(), JobPostingAnalysis.Item::getText);
        return query.toString();
    }

    private <T> void appendTexts(
            StringBuilder query, List<T> values, Function<T, String> textExtractor
    ) {
        if (values == null) return;
        values.stream().map(textExtractor).filter(value -> value != null && !value.isBlank())
                .forEach(value -> query.append(value).append('\n'));
    }

    private record GuideSelection(JobGuideDocument guide, GuideMatchType matchType) {
    }

    private record ScoredGuideChunk(JobGuideChunk chunk, Double score) {
    }
}
