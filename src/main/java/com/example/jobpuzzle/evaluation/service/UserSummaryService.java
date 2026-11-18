package com.example.jobpuzzle.evaluation.service;

import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.evaluation.dto.SessionReportV2Response;
import com.example.jobpuzzle.evaluation.dto.UserSummaryResponse;
import com.example.jobpuzzle.evaluation.v2.UserSummaryCalculator;
import com.example.jobpuzzle.interview.entity.InterviewSession;
import com.example.jobpuzzle.interview.entity.InterviewSessionStatus;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 평가 명세 v2 4-3 사용자 종합 평가. 끝난 v2 면접의 최종 보고서(SessionReportV2Service)에서 평가된 질문을 모아
 * UserSummaryCalculator로 태그별 강점·아쉬운 점과 선호 직무를 계산한다. AI 호출 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserSummaryService {

    private final InterviewSessionRepository sessionRepository;
    private final AnalysisCaseRepository analysisCaseRepository;
    private final SessionReportV2Service reportService;
    private final SummaryProperties properties;

    /** 평가된 질문 하나 (어느 면접의 어떤 질문인지와 잘한 점·보완할 점). */
    private record Answered(Long sessionId, SessionReportV2Response.Question question) {
    }

    // ponytail: 부를 때마다 끝난 면접의 보고서를 다시 계산함. 면접이 수십 개를 넘어 느려지면 보고서 점수를 저장해 둘 것
    public UserSummaryResponse summary(Long userId) {
        List<InterviewSession> sessions = sessionRepository
                .findByUser_UserIdAndStatusAndDeletedAtIsNullOrderByCompletedAtDesc(userId, InterviewSessionStatus.COMPLETED)
                .stream().filter(s -> s.getMode().isV2()).toList();

        List<Answered> answered = new ArrayList<>();
        Map<String, String> tagNames = new HashMap<>();
        for (InterviewSession session : sessions) {
            for (SessionReportV2Response.Question q : reportService.build(session).questions()) {
                if (!"EVALUATED".equals(q.status()) || q.competencyTagCodes().isEmpty()) continue;
                answered.add(new Answered(session.getSessionId(), q));
                for (int i = 0; i < q.competencyTagCodes().size(); i++) {
                    tagNames.putIfAbsent(q.competencyTagCodes().get(i), q.competencyTags().get(i));
                }
            }
        }

        // 선호 직무: 분석·면접을 가장 많이 진행한 직무 (같으면 최근에 진행한 쪽이 먼저 들어가 이긴다, 수정사항 5번)
        Map<String, Integer> activity = new LinkedHashMap<>();
        sessions.forEach(s -> activity.merge(jobName(s.getJobCategory()), 1, Integer::sum));
        analysisCaseRepository.findByUser_UserIdAndStatusAndHiddenAtIsNullOrderByCreatedAtDesc(userId, AnalysisCaseStatus.COMPLETED)
                .stream().map(AnalysisCase::getJobCategory)
                .forEach(job -> activity.merge(jobName(job), 1, Integer::sum));

        UserSummaryCalculator.Settings settings = new UserSummaryCalculator.Settings(
                properties.getMinReports(), properties.getTopN(), properties.getMinQuestionsPerTag());
        var summary = UserSummaryCalculator.calculate(settings, sessions.size(),
                answered.stream().map(a -> new UserSummaryCalculator.TaggedQuestion(
                        new LinkedHashSet<>(a.question().competencyTagCodes()), a.question().score())).toList(),
                activity);

        return new UserSummaryResponse(
                summary.isPresent(), sessions.size(), settings.minReports(), settings.minQuestionsPerTag(),
                summary.map(UserSummaryCalculator.Summary::preferredJob).orElse(null),
                summary.map(s -> tags(s.strengths(), answered, tagNames)).orElse(List.of()),
                summary.map(s -> tags(s.weaknesses(), answered, tagNames)).orElse(List.of()));
    }

    private List<UserSummaryResponse.Tag> tags(List<UserSummaryCalculator.TagScore> scores, List<Answered> answered,
                                               Map<String, String> tagNames) {
        return scores.stream().map(score -> {
            List<Answered> withTag = answered.stream()
                    .filter(a -> a.question().competencyTagCodes().contains(score.tag())).toList();
            Comparator<Answered> byScore = Comparator.comparingInt(a -> a.question().score());
            Answered best = withTag.stream().max(byScore).orElseThrow();
            Answered worst = withTag.stream().min(byScore).orElseThrow();
            return new UserSummaryResponse.Tag(score.tag(), tagNames.getOrDefault(score.tag(), score.tag()),
                    score.average(), score.questionCount(),
                    example(best, best.question().strengths()), example(worst, worst.question().improvements()));
        }).toList();
    }

    private static UserSummaryResponse.Example example(Answered answered, String text) {
        return new UserSummaryResponse.Example(answered.sessionId(), answered.question().question(),
                answered.question().score(), text);
    }

    private static String jobName(JobCategory job) {
        return job.getMainCategory() + " · " + job.getSubCategory();
    }
}
