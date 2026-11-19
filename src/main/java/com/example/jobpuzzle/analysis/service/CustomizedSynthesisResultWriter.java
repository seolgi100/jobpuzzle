package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.dto.CustomizedAnalysisGenerationResult;
import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.ai.log.AiCallLogStatus;
import com.example.jobpuzzle.ai.log.AiCallLogRepository;
import com.example.jobpuzzle.analysis.entity.*;
import com.example.jobpuzzle.analysis.repository.*;
import com.example.jobpuzzle.guide.entity.GuideContextResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

// 검증된 JSON-05 전체 결과와 성공 로그·AnalysisCase 완료를 하나의 트랜잭션으로 확정한다.
@Service
@RequiredArgsConstructor
public class CustomizedSynthesisResultWriter {
    private final AnalysisInputSnapshotRepository snapshotRepository;
    private final AiCallLogRepository aiCallLogRepository;
    private final MatchAnalysisResultRepository matchRepository;
    private final ReadinessResultRepository readinessRepository;
    private final ActionPlanRepository actionPlanRepository;

    // 모든 자식 결과가 저장된 뒤에만 SUCCEEDED와 COMPLETED를 확정한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Long snapshotId, Long aiCallLogId, GuideContextResult guideContext,
                      CustomizedAnalysisGenerationResult result) {
        AnalysisInputSnapshot snapshot = snapshotRepository.findWithLockBySnapshotId(snapshotId)
                .orElseThrow(() -> new IllegalStateException("snapshot not found"));
        AiCallLog log = aiCallLogRepository.findById(aiCallLogId)
                .orElseThrow(() -> new IllegalStateException("AI call log not found"));
        if (log.getStatus() != AiCallLogStatus.RUNNING) throw new IllegalStateException("JSON-05 log is not RUNNING");
        if (hasAnyResult(snapshotId)) throw new IllegalStateException("JSON-05 result integrity conflict");

        List<MatchAnalysisResult> matches = matchRepository.saveAll(result.getRequirementMatches().stream()
                .map(value -> MatchAnalysisResult.from(snapshot, log, value)).toList());
        Map<String, MatchAnalysisResult> matchByKey = matches.stream()
                .collect(java.util.stream.Collectors.toMap(MatchAnalysisResult::getMatchKey, Function.identity()));
        readinessRepository.save(ReadinessResult.from(snapshot, log, result.getReadiness()));

        actionPlanRepository.saveAll(result.getTasks().stream()
                .map(value -> ActionPlan.from(snapshot, matchByKey.get(value.getRelatedMatchId()), log, value)).toList());
        log.succeed();
        snapshot.getAnalysisCase().complete();
    }

    private boolean hasAnyResult(Long snapshotId) {
        return readinessRepository.existsBySnapshot_SnapshotId(snapshotId)
                || matchRepository.existsBySnapshot_SnapshotId(snapshotId)
                || actionPlanRepository.existsBySnapshot_SnapshotId(snapshotId);
    }
}
