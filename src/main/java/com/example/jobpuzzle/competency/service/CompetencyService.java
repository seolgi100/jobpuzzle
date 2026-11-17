package com.example.jobpuzzle.competency.service;

import com.example.jobpuzzle.competency.dto.CompetencyDtos.CommonTagResponse;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.JobCompetencyResponse;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.JobRef;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.QuestionRequest;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.QuestionResponse;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.TagRequest;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.TagResponse;
import com.example.jobpuzzle.competency.entity.CommonCompetencyTag;
import com.example.jobpuzzle.competency.entity.JobCompetencyTag;
import com.example.jobpuzzle.competency.entity.PersonalityQuestion;
import com.example.jobpuzzle.competency.repository.JobCompetencyTagRepository;
import com.example.jobpuzzle.competency.repository.PersonalityQuestionRepository;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.jobcategory.repository.JobCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** 직무별 역량 태그·인성 질문 관리 (평가 명세 4장·8장). */
@Service
@RequiredArgsConstructor
@Transactional
public class CompetencyService {

    private final JobCompetencyTagRepository tagRepository;
    private final PersonalityQuestionRepository questionRepository;
    private final JobCategoryRepository jobCategoryRepository;

    // 직무 분류는 경력별로 행이 나뉘어 있어 대분류+중분류만 중복 없이 모은다.
    @Transactional(readOnly = true)
    public List<JobRef> getJobs() {
        return jobCategoryRepository.findAll().stream()
                .map(c -> new JobRef(c.getMainCategory(), c.getSubCategory()))
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    public JobCompetencyResponse get(String mainCategory, String subCategory) {
        requireJob(mainCategory, subCategory);
        return new JobCompetencyResponse(mainCategory, subCategory,
                Arrays.stream(CommonCompetencyTag.values()).map(CommonTagResponse::from).toList(),
                tags(mainCategory, subCategory).stream().map(TagResponse::from).toList(),
                questions(mainCategory, subCategory).stream().map(QuestionResponse::from).toList());
    }

    public TagResponse createTag(TagRequest request) {
        requireJob(request.mainCategory(), request.subCategory());
        requireNewCode(request.mainCategory(), request.subCategory(), request.code(), null);
        return TagResponse.from(tagRepository.save(new JobCompetencyTag(request.mainCategory(), request.subCategory(),
                request.code(), request.name(), request.description())));
    }

    // 태그가 속한 직무는 바꾸지 않는다. 요청의 대분류·중분류는 무시한다.
    public TagResponse updateTag(Long tagId, TagRequest request) {
        JobCompetencyTag tag = findTag(tagId);
        if (!tag.getCode().equals(request.code())) {
            requireNewCode(tag.getMainCategory(), tag.getSubCategory(), request.code(), tagId);
            requireUnused(tag);
        }
        tag.update(request.code(), request.name(), request.description());
        return TagResponse.from(tag);
    }

    public void deleteTag(Long tagId) {
        JobCompetencyTag tag = findTag(tagId);
        requireUnused(tag);
        tagRepository.delete(tag);
    }

    public QuestionResponse createQuestion(QuestionRequest request) {
        requireJob(request.mainCategory(), request.subCategory());
        validateTags(request.mainCategory(), request.subCategory(), request.competencyTags());
        return QuestionResponse.from(questionRepository.save(new PersonalityQuestion(request.mainCategory(),
                request.subCategory(), request.question(), request.intent(), request.competencyTags(), request.careerLevel())));
    }

    public QuestionResponse updateQuestion(Long questionId, QuestionRequest request) {
        PersonalityQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new CustomException(ErrorCode.PERSONALITY_QUESTION_NOT_FOUND));
        validateTags(question.getMainCategory(), question.getSubCategory(), request.competencyTags());
        question.update(request.question(), request.intent(), request.competencyTags(), request.careerLevel());
        return QuestionResponse.from(question);
    }

    public void deleteQuestion(Long questionId) {
        PersonalityQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new CustomException(ErrorCode.PERSONALITY_QUESTION_NOT_FOUND));
        questionRepository.delete(question);
    }

    /** 질문 태그 규칙 (명세 4-2): 1~2개, 중복 없이, 공통 역량 또는 그 직무의 역량 태그만. */
    @Transactional(readOnly = true)
    public void validateTags(String mainCategory, String subCategory, List<String> codes) {
        if (codes == null || codes.isEmpty() || codes.size() > 2 || new HashSet<>(codes).size() != codes.size()) {
            throw new CustomException(ErrorCode.COMPETENCY_TAG_INVALID);
        }
        List<String> jobCodes = tags(mainCategory, subCategory).stream().map(JobCompetencyTag::getCode).toList();
        for (String code : codes) {
            if (!CommonCompetencyTag.contains(code) && !jobCodes.contains(code)) {
                throw new CustomException(ErrorCode.COMPETENCY_TAG_INVALID,
                        "공통 역량이나 이 직무의 역량 태그가 아닙니다: " + code);
            }
        }
    }

    private void requireJob(String mainCategory, String subCategory) {
        if (!jobCategoryRepository.existsByMainCategoryAndSubCategory(mainCategory, subCategory)) {
            throw new CustomException(ErrorCode.COMPETENCY_JOB_NOT_FOUND);
        }
    }

    private void requireNewCode(String mainCategory, String subCategory, String code, Long selfId) {
        if (CommonCompetencyTag.contains(code)) {
            throw new CustomException(ErrorCode.COMPETENCY_TAG_DUPLICATE, "공통 역량과 같은 코드는 쓸 수 없습니다.");
        }
        boolean taken = tags(mainCategory, subCategory).stream()
                .anyMatch(t -> t.getCode().equals(code) && (selfId == null || !selfId.equals(t.getJobCompetencyTagId())));
        if (taken) throw new CustomException(ErrorCode.COMPETENCY_TAG_DUPLICATE);
    }

    private void requireUnused(JobCompetencyTag tag) {
        boolean used = questions(tag.getMainCategory(), tag.getSubCategory()).stream()
                .anyMatch(q -> q.usesTag(tag.getCode()));
        if (used) throw new CustomException(ErrorCode.COMPETENCY_TAG_IN_USE);
    }

    private JobCompetencyTag findTag(Long tagId) {
        return tagRepository.findById(tagId).orElseThrow(() -> new CustomException(ErrorCode.COMPETENCY_TAG_NOT_FOUND));
    }

    private List<JobCompetencyTag> tags(String mainCategory, String subCategory) {
        return tagRepository.findByMainCategoryAndSubCategoryOrderByJobCompetencyTagIdAsc(mainCategory, subCategory);
    }

    private List<PersonalityQuestion> questions(String mainCategory, String subCategory) {
        return questionRepository.findByMainCategoryAndSubCategoryOrderByPersonalityQuestionIdAsc(mainCategory, subCategory);
    }
}
