package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.analysis.dto.AnalysisStageStatus;
import com.example.jobpuzzle.analysis.dto.AnalysisStatusResponse;
import org.junit.jupiter.api.Test;

import static com.example.jobpuzzle.analysis.dto.AnalysisStageStatus.NOT_STARTED;
import static com.example.jobpuzzle.analysis.dto.AnalysisStageStatus.RUNNING;
import static com.example.jobpuzzle.analysis.dto.AnalysisStageStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;

// 시뮬레이션 대기 중에는 지난 시간만큼 4단계가 차례로 넘어가고, 끝나기 전까지 마지막 단계는 진행 중으로 남는다
class AnalysisSimulatedStatusTest {

    @Test
    void stagesAdvanceWithProgress() {
        assertThat(stages(0.0)).containsExactly(RUNNING, NOT_STARTED, NOT_STARTED, NOT_STARTED);
        assertThat(stages(0.3)).containsExactly(SUCCEEDED, RUNNING, NOT_STARTED, NOT_STARTED);
        assertThat(stages(0.6)).containsExactly(SUCCEEDED, SUCCEEDED, RUNNING, NOT_STARTED);
        assertThat(stages(1.0)).containsExactly(SUCCEEDED, SUCCEEDED, SUCCEEDED, RUNNING);
        AnalysisStatusResponse status = AnalysisStatusQueryService.simulatedStatus(7L, 0.5);
        assertThat(status.getAnalysisCaseStatus()).isEqualTo("ANALYZING");
        assertThat(status.isSimulated()).isTrue();
    }

    private static AnalysisStageStatus[] stages(double progress) {
        AnalysisStatusResponse s = AnalysisStatusQueryService.simulatedStatus(7L, progress);
        return new AnalysisStageStatus[]{s.getJobPostingAnalysisStatus(), s.getCandidateMaterialAnalysisStatus(),
                s.getGuideContextStatus(), s.getCustomizedAnalysisStatus()};
    }
}
