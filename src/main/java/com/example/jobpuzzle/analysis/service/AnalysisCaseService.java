package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.analysis.dto.AnalysisCaseCreateRequest;
import com.example.jobpuzzle.analysis.dto.AnalysisCaseJobCategoryUpdateRequest;
import com.example.jobpuzzle.analysis.dto.AnalysisCaseResponse;
import com.example.jobpuzzle.analysis.dto.AnalysisCaseSourceAddRequest;
import com.example.jobpuzzle.analysis.dto.AnalysisCaseSourceResponse;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContext;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotResponse;
import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseSource;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshotSource;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseSourceRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotSourceRepository;
import com.example.jobpuzzle.document.entity.DocumentExtraction;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.document.service.DocumentExtractionService;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.repository.JobCategoryRepository;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisCaseService {

    private static final Set<UserDocumentType> USER_MATERIAL_TYPES = Set.of(
            UserDocumentType.RESUME, UserDocumentType.COVER_LETTER,
            UserDocumentType.PORTFOLIO, UserDocumentType.EXPERIENCE_NOTE
    );

    // DocumentExtractionAsyncRunner가 content에 심어두는 "[N페이지]\n" 마커. document-flow.js의 splitPages와 동일 규칙
    private static final Pattern PAGE_MARKER_PATTERN = Pattern.compile("\\[(\\d+)페이지]\\n");

    private final AnalysisCaseRepository analysisCaseRepository;
    private final AnalysisCaseSourceRepository analysisCaseSourceRepository;
    private final AnalysisInputSnapshotRepository analysisInputSnapshotRepository;
    private final AnalysisInputSnapshotSourceRepository analysisInputSnapshotSourceRepository;
    private final UserRepository userRepository;
    private final JobCategoryRepository jobCategoryRepository;
    private final DocumentExtractionService documentExtractionService;
    private final AnalysisMaterialChunkService analysisMaterialChunkService;

    // jobCategoryId를 안 보내면 회원 기본 관심 직무를 초기값으로 사용
    @Transactional
    public AnalysisCaseResponse createCase(Long userId, AnalysisCaseCreateRequest request) {
        JobCategory jobCategory = resolveInitialJobCategory(userId, request.getJobCategoryId());

        AnalysisCase analysisCase = AnalysisCase.builder()
                .user(userRepository.getReferenceById(userId))
                .jobCategory(jobCategory)
                .build();
        analysisCaseRepository.save(analysisCase);

        return AnalysisCaseResponse.of(analysisCase, List.of());
    }

    public AnalysisCaseResponse getCase(Long userId, Long analysisCaseId) {
        AnalysisCase analysisCase = findOwnedCase(userId, analysisCaseId);
        return AnalysisCaseResponse.of(analysisCase, findSources(analysisCaseId));
    }

    // v2: 분석 결과 하나로 면접을 여러 번 만들 수 있어 완료된 분석은 모두 보여 준다.
    public List<AnalysisCaseResponse> getCompletedCases(Long userId) {
        return analysisCaseRepository
                .findByUser_UserIdAndStatusOrderByCreatedAtDesc(
                        userId,
                        AnalysisCaseStatus.COMPLETED
                )
                .stream()
                .map(analysisCase -> AnalysisCaseResponse.of(
                        analysisCase,
                        findSources(analysisCase.getAnalysisCaseId())
                ))
                .toList();
    }

    // 면접 준비 화면용: 분석을 시작한 모든 케이스(작성 중 DRAFT 제외)를 최신순으로
    public List<AnalysisCaseResponse> getCases(Long userId) {
        return analysisCaseRepository
                .findByUser_UserIdAndStatusInOrderByCreatedAtDesc(userId, List.of(
                        AnalysisCaseStatus.INPUT_CONFIRMED,
                        AnalysisCaseStatus.ANALYZING,
                        AnalysisCaseStatus.COMPLETED,
                        AnalysisCaseStatus.FAILED
                ))
                .stream()
                .map(analysisCase -> AnalysisCaseResponse.of(
                        analysisCase,
                        findSources(analysisCase.getAnalysisCaseId())
                ))
                .toList();
    }

    @Transactional
    public AnalysisCaseResponse changeJobCategory(Long userId, Long analysisCaseId, AnalysisCaseJobCategoryUpdateRequest request) {
        AnalysisCase analysisCase = findOwnedCase(userId, analysisCaseId);
        requireDraft(analysisCase);

        analysisCase.changeJobCategory(findJobCategory(request.getJobCategoryId()));

        return AnalysisCaseResponse.of(analysisCase, findSources(analysisCaseId));
    }

    // 본인 소유 CONFIRMED 추출본만 연결 가능. 채용공고는 1건만, 동일 추출본 중복 연결은 금지
    @Transactional
    public AnalysisCaseSourceResponse addSource(Long userId, Long analysisCaseId, AnalysisCaseSourceAddRequest request) {
        AnalysisCase analysisCase = findOwnedCase(userId, analysisCaseId);
        requireDraft(analysisCase);

        DocumentExtraction extraction =
                documentExtractionService.getConfirmedExtractions(userId, List.of(request.getExtractionId())).get(0);
        UserDocumentType documentType = extraction.getDocument().getDocumentType();

        if (analysisCaseSourceRepository.existsByAnalysisCase_AnalysisCaseIdAndExtraction_ExtractionId(
                analysisCaseId, extraction.getExtractionId())) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_SOURCE_DUPLICATE);
        }
        if (documentType == UserDocumentType.JOB_POSTING && analysisCaseSourceRepository
                .existsByAnalysisCase_AnalysisCaseIdAndDocumentType(analysisCaseId, UserDocumentType.JOB_POSTING)) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_JOB_POSTING_ALREADY_SELECTED);
        }

        AnalysisCaseSource source = AnalysisCaseSource.builder()
                .analysisCase(analysisCase)
                .extraction(extraction)
                .documentType(documentType)
                .build();
        analysisCaseSourceRepository.save(source);

        return AnalysisCaseSourceResponse.from(source);
    }

    @Transactional
    public void removeSource(Long userId, Long analysisCaseId, Long analysisCaseSourceId) {
        AnalysisCase analysisCase = findOwnedCase(userId, analysisCaseId);
        requireDraft(analysisCase);

        AnalysisCaseSource source = analysisCaseSourceRepository
                .findByAnalysisCaseSourceIdAndAnalysisCase_AnalysisCaseId(analysisCaseSourceId, analysisCaseId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_CASE_SOURCE_NOT_FOUND));
        analysisCaseSourceRepository.delete(source);
    }

    // 선택 자료 구성을 검증하고 AnalysisCaseSource를 AnalysisInputSnapshotSource로 고정 복사한 뒤
    // 스냅샷을 생성하고 상태를 INPUT_CONFIRMED로 전환
    @Transactional
    public AnalysisInputSnapshotResponse confirmInput(Long userId, Long analysisCaseId) {
        AnalysisCase analysisCase = findOwnedCase(userId, analysisCaseId);
        requireDraft(analysisCase);

        List<AnalysisCaseSource> sources = findSources(analysisCaseId);
        validateSourceComposition(sources);

        List<Long> extractionIds = sources.stream().map(s -> s.getExtraction().getExtractionId()).toList();
        documentExtractionService.getConfirmedExtractions(userId, extractionIds);

        AnalysisInputSnapshot snapshot = AnalysisInputSnapshot.builder()
                .analysisCase(analysisCase)
                .user(analysisCase.getUser())
                .jobCategory(analysisCase.getJobCategory())
                .build();
        analysisInputSnapshotRepository.save(snapshot);

        List<AnalysisInputSnapshotSource> snapshotSources = sources.stream()
                .map(source -> AnalysisInputSnapshotSource.builder()
                        .snapshot(snapshot)
                        .extraction(source.getExtraction())
                        .documentType(source.getDocumentType())
                        .build())
                .toList();
        List<AnalysisInputSnapshotSource> savedSnapshotSources = analysisInputSnapshotSourceRepository.saveAll(snapshotSources);

        // snapshot과 청크를 한 트랜잭션으로 묶어 불완전한 RAG 입력 상태가 남지 않게 한다.
        savedSnapshotSources.forEach(source -> analysisMaterialChunkService.getOrCreateChunks(
                source, AnalysisMaterialChunkService.CURRENT_CHUNKING_VERSION));

        analysisCase.confirmInput();

        return AnalysisInputSnapshotResponse.of(snapshot, savedSnapshotSources);
    }

    public AnalysisInputSnapshotResponse getSnapshot(Long userId, Long analysisCaseId) {
        AnalysisInputSnapshot snapshot = analysisInputSnapshotRepository
                .findByAnalysisCase_AnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SNAPSHOT_NOT_FOUND));
        List<AnalysisInputSnapshotSource> sources = analysisInputSnapshotSourceRepository
                .findBySnapshot_SnapshotIdOrderBySnapshotSourceIdAsc(snapshot.getSnapshotId());
        return AnalysisInputSnapshotResponse.of(snapshot, sources);
    }

    // JSON-03: AI 실행 직전에만 조립하는 서버 내부 컨텍스트. 공개 스냅샷 응답과 분리된 별도 DTO이며
    // 아직 마스킹은 적용하지 않고 원문을 그대로 담는다(1단계, 마스킹은 후속 작업)
    public AnalysisInputSnapshotContext getAnalysisContext(Long userId, Long analysisCaseId) {
        AnalysisInputSnapshot snapshot = analysisInputSnapshotRepository
                .findByAnalysisCase_AnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SNAPSHOT_NOT_FOUND));
        List<AnalysisInputSnapshotSource> sources = analysisInputSnapshotSourceRepository
                .findBySnapshot_SnapshotIdOrderBySnapshotSourceIdAsc(snapshot.getSnapshotId());

        AtomicInteger segmentCounter = new AtomicInteger(1);
        List<AnalysisInputSnapshotContextSource> contextSources = sources.stream()
                .map(source -> buildContextSource(source, segmentCounter))
                .toList();

        return AnalysisInputSnapshotContext.of(snapshot, contextSources);
    }

    private AnalysisInputSnapshotContextSource buildContextSource(AnalysisInputSnapshotSource source, AtomicInteger segmentCounter) {
        DocumentExtraction extraction = source.getExtraction();
        String analysisText = splitPages(extraction.getContent()).stream()
                .map(page -> buildMarkerBlock(extraction, page, segmentCounter.getAndIncrement()))
                .collect(Collectors.joining("\n\n"));
        return AnalysisInputSnapshotContextSource.from(source, analysisText);
    }

    // 세그먼트는 페이지 단위로 나눈다. 마스킹 없이 원문 그대로 담고(1단계), 세그먼트 번호는
    // 스냅샷 전체 조립 1회 안에서 소스 구분 없이 이어서 증가한다
    private String buildMarkerBlock(DocumentExtraction extraction, PageText page, int segmentNumber) {
        String marker = "[SOURCE extractionId=%d documentId=%d][PAGE=%d][SEGMENT=seg-%03d]".formatted(
                extraction.getExtractionId(), extraction.getDocument().getDocumentId(),
                page.pageNumber(), segmentNumber
        );
        return marker + "\n" + page.text();
    }

    // DocumentExtractionAsyncRunner가 심어둔 "[N페이지]\n" 마커 기준으로 페이지 단위로 쪼갠다.
    // 마커가 없으면(예: 직접 입력한 TEXT 자료) 전체를 1페이지로 취급한다
    private List<PageText> splitPages(String content) {
        if (content == null || content.isBlank()) {
            return List.of(new PageText(1, content == null ? "" : content));
        }
        List<MatchResult> matches = PAGE_MARKER_PATTERN.matcher(content).results().toList();
        if (matches.isEmpty()) {
            return List.of(new PageText(1, content.trim()));
        }
        List<PageText> pages = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            MatchResult match = matches.get(i);
            int start = match.end();
            int end = (i + 1 < matches.size()) ? matches.get(i + 1).start() : content.length();
            pages.add(new PageText(Integer.parseInt(match.group(1)), content.substring(start, end).trim()));
        }
        return pages;
    }

    private record PageText(int pageNumber, String text) {
    }

    private void validateSourceComposition(List<AnalysisCaseSource> sources) {
        long jobPostingCount = sources.stream()
                .filter(s -> s.getDocumentType() == UserDocumentType.JOB_POSTING)
                .count();
        if (jobPostingCount != 1) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_JOB_POSTING_REQUIRED);
        }
        boolean hasUserMaterial = sources.stream().anyMatch(s -> USER_MATERIAL_TYPES.contains(s.getDocumentType()));
        if (!hasUserMaterial) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_USER_MATERIAL_REQUIRED);
        }
    }

    private JobCategory resolveInitialJobCategory(Long userId, Long jobCategoryId) {
        if (jobCategoryId != null) {
            return findJobCategory(jobCategoryId);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        JobCategory defaultJobCategory = user.getDefaultJobCategory();
        if (defaultJobCategory == null) {
            throw new CustomException(ErrorCode.JOB_CATEGORY_NOT_FOUND);
        }
        return defaultJobCategory;
    }

    private JobCategory findJobCategory(Long jobCategoryId) {
        return jobCategoryRepository.findById(jobCategoryId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_CATEGORY_NOT_FOUND));
    }

    private List<AnalysisCaseSource> findSources(Long analysisCaseId) {
        return analysisCaseSourceRepository.findByAnalysisCase_AnalysisCaseIdOrderByAnalysisCaseSourceIdAsc(analysisCaseId);
    }

    private AnalysisCase findOwnedCase(Long userId, Long analysisCaseId) {
        return analysisCaseRepository.findByAnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_CASE_NOT_FOUND));
    }

    private void requireDraft(AnalysisCase analysisCase) {
        if (!analysisCase.isDraft()) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_NOT_DRAFT);
        }
    }
}
