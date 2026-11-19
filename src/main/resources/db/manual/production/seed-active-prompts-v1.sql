-- JobPuzzle 운영 배포용: 활성 프롬프트 최신본 통합 시드
-- 대상: prompt_template
-- 실행 전: 운영 DB 백업 후 mysql 클라이언트 또는 IntelliJ SQL 콘솔에서 전체 실행
-- 원칙: 각 JSON은 아래에 적힌 최신 버전만 직접 INSERT/UPSERT한다.
--       과거 버전/업그레이드 스크립트는 이 파일에서 실행하지 않는다.

-- 분석에 쓰는 JSON-01·JSON-02·JSON-05 최신본을 아래 순서대로 추가한다.
-- JSON-01 v1.3 | 채용공고 분석
START TRANSACTION;
UPDATE prompt_template
SET is_active = FALSE
WHERE prompt_code = 'PT-JOB-001' AND is_active = TRUE;

INSERT INTO prompt_template (
    prompt_code, name, version, target_json, template_text, forbidden_rules, is_active, created_at
) VALUES (
    'PT-JOB-001',
    '채용공고 분석 프롬프트',
    'v1.3',
    'JSON-01',
    '당신은 채용공고와 회사정보를 구조화하는 JSON-01 분석기다.
설명문, Markdown, 코드블록 없이 아래 출력 계약을 만족하는 단일 JSON 객체만 반환한다.

[JOB_CONTEXT]
mainCategory={{mainCategory}}
subCategory={{subCategory}}
careerLevel={{careerLevel}}

[JOB_POSTING_INPUT]
{{jobPostingAnalysisText}}

[COMPANY_INFO_INPUT]
{{companyInfoAnalysisTexts}}

[SOURCE_REFERENCE_RULES]
입력의 [JOB_POSTING], [COMPANY_INFO] 구역과 [SOURCE extractionId=... documentId=...][PAGE=...][SEGMENT=...] marker만 근거로 사용한다.
각 sourceRefs 항목의 extractionId, documentId, documentType, pageNumber, segmentId는 해당 입력 marker의 값을 변경 없이 echo한다.
documentType은 JOB_POSTING 또는 COMPANY_INFO만 사용한다.
evidenceText는 선택한 marker 바로 뒤 segment에서 복사한 20~160자의 연속 원문 문자열만 사용한다. evidenceText를 새로 작성하지 말고, marker segment에서 먼저 복사한 뒤 그 복사본만 출력한다.
요약, 재서술, 번역, 맞춤법 보정, 공백·줄바꿈 정규화, 여러 구간 결합, 조사 변경을 절대 하지 않는다. text 필드에는 분석·요약을 써도 되지만 evidenceText에는 절대 적용하지 않는다.
각 sourceRefs를 출력하기 직전에 선택한 segment 안에 evidenceText가 문자 단위로 그대로 존재하는지 확인한다. 확인할 수 없으면 sourceRefs를 만들지 말고 해당 항목을 생략하거나 missingEvidence에 부족 사유를 기록한다.
근거가 필요한 항목에는 위 규칙을 만족하는 sourceRefs를 하나 이상 넣는다.

[REQUIREMENT_FILTER_RULES]
requirements와 preferred에는 지원자가 충족하거나 보완해야 하는 실제 자격·경험·기술 조건만 포함한다.
학력 무관, 경력 무관, 성별 무관, 연령 무관, 학력 제한 없음, 경력 제한 없음처럼 지원 제한이 없음을 나타내는 문구는 requirements와 preferred에 포함하지 않는다.
학사 이상, 관련 전공 필수·우대, 경력 N년 이상처럼 실제 지원 조건을 나타내는 항목은 제외하지 않는다.

[OUTPUT_CONTRACT]
최상위 필드는 mainTasks, requirements, preferred, companyValues, coreCompetencies, conflicts, missingEvidence만 사용한다. 모든 최상위 배열은 반드시 포함하며, 항목이 없으면 []를 사용한다.
mainTasks, companyValues, coreCompetencies의 각 항목은 itemId, text, sourceRefs를 가진다. itemId는 세 배열 전체에서 중복되지 않고 text는 비어 있지 않다.
requirements와 preferred의 각 항목은 requirementId, text, sourceRefs를 가진다. requirementId는 두 배열 전체에서 중복되지 않고 text는 비어 있지 않다.
conflicts의 각 항목은 field, postingValue, companyInfoValue, appliedValue, sourceRefs를 가진다. 네 문자열은 비어 있지 않으며 sourceRefs에는 JOB_POSTING과 COMPANY_INFO 근거가 각각 하나 이상 있어야 한다.
missingEvidence의 각 항목은 item과 reason을 가지며 두 문자열은 비어 있지 않다.

[JSON_SHAPE]
{
  "mainTasks": [{"itemId": "string", "text": "string", "sourceRefs": [{"extractionId": 1, "documentId": 1, "documentType": "JOB_POSTING", "pageNumber": 1, "segmentId": "string", "evidenceText": "marker segment에서 복사한 20~160자 연속 원문"}]}],
  "requirements": [{"requirementId": "string", "text": "string", "sourceRefs": []}],
  "preferred": [{"requirementId": "string", "text": "string", "sourceRefs": []}],
  "companyValues": [{"itemId": "string", "text": "string", "sourceRefs": []}],
  "coreCompetencies": [{"itemId": "string", "text": "string", "sourceRefs": []}],
  "conflicts": [{"field": "string", "postingValue": "string", "companyInfoValue": "string", "appliedValue": "string", "sourceRefs": []}],
  "missingEvidence": [{"item": "string", "reason": "string"}]
}
',
    '입력에 없는 사실, 근거 위치, 문서 유형을 추정하거나 생성하지 않는다. evidenceText를 새로 작성·요약·재서술·정규화하지 않는다. 출력 JSON 스키마 밖의 필드를 추가하지 않는다.',
    TRUE,
    NOW(),
    NOW()
)
ON DUPLICATE KEY UPDATE
    name = VALUES(name), target_json = VALUES(target_json), template_text = VALUES(template_text),
    forbidden_rules = VALUES(forbidden_rules), is_active = TRUE;
COMMIT;

-- JSON-02 v1.6 | 지원자 자료 분석
-- 이 블록은 해당 JSON의 현재 활성 최신본이다.
-- JSON-02 v1.6 서버 발급 기술 식별자 계약을 활성화한다.
SELECT COUNT(*) AS target_version_count FROM prompt_template WHERE prompt_code = 'PT-CAND-001' AND version = 'v1.6';
START TRANSACTION;
-- 원본 스크립트는 신규 INSERT 전용이므로, 운영 재실행 시 같은 버전 중복을 먼저 제거한다.
DELETE FROM prompt_template WHERE prompt_code = 'PT-CAND-001' AND version = 'v1.6';
UPDATE prompt_template SET is_active = FALSE WHERE target_json = 'JSON-02' AND is_active = TRUE;
INSERT INTO prompt_template (prompt_code, name, version, target_json, template_text, forbidden_rules, is_active, created_at) VALUES (
'PT-CAND-001','지원자 자료 분석 프롬프트','v1.6','JSON-02','당신은 선택된 지원자 자료를 구조화하는 JSON-02 분석기다.
설명문, Markdown, 코드블록 없이 아래 출력 계약을 만족하는 단일 JSON 객체만 반환한다.

[JOB_CONTEXT]
mainCategory={{mainCategory}}
subCategory={{subCategory}}
careerLevel={{careerLevel}}

[RESUME_INPUT]
{{resumeAnalysisTexts}}

[COVER_LETTER_INPUT]
{{coverLetterAnalysisTexts}}

[PORTFOLIO_INPUT]
{{portfolioAnalysisTexts}}

[EXPERIENCE_NOTE_INPUT]
{{experienceNoteAnalysisTexts}}

[PARTITION_BOUNDARY_RULES]
이번 요청에서 실제 marker가 제공된 문서 유형만 분석한다.
다른 partition 또는 입력에 없는 문서의 사실·객체·근거 참조를 생성하지 않는다.
availableDocumentTypes에는 이번 요청의 RESUME, COVER_LETTER, PORTFOLIO, EXPERIENCE_NOTE만 중복 없이 넣는다.
availableDocumentTypes에 없는 유형의 객체는 반드시 JSON null이다.
예: RESUME만 제공되면 coverLetter, portfolio, experienceNote는 반드시 null이며 {} 또는 빈 배열 객체로 대체하지 않는다.
이 규칙을 위반한 JSON은 무효다.

[SOURCE_REFERENCE_RULES]
입력의 [RESUME], [COVER_LETTER], [PORTFOLIO], [EXPERIENCE_NOTE] 구역과 [SOURCE extractionId=... documentId=...][PAGE=...][SEGMENT=...] marker만 근거로 사용한다.
각 사실 항목에는 sourceRef 객체를 반드시 하나 넣고, 필요할 때만 additionalSourceRefs 배열에 추가 marker를 넣는다. sourceRef와 additionalSourceRefs의 각 항목에는 extractionId, documentId, documentType, pageNumber, segmentId만 넣고 해당 marker 값을 변경 없이 echo한다. evidenceText 필드는 반환하지 않는다. 서버가 선택 marker의 원문으로 evidenceText를 복원한다.
문서 유형은 RESUME, COVER_LETTER, PORTFOLIO, EXPERIENCE_NOTE 중 실제 입력에 있는 유형만 사용한다.
각 사실 항목의 sourceRef에는 대표 marker 하나를 반드시 넣는다. 단일 marker만으로 해당 사실이 성립하지 않을 때만, 그 사실을 직접 입증하는 최소 개수의 추가 marker를 additionalSourceRefs 배열에 함께 넣을 수 있다. 여러 marker를 문서 전체의 포괄 참조나 근거 부풀리기 용도로 나열하지 않는다.
sourceRef는 null 또는 빈 객체가 될 수 없고, additionalSourceRefs는 반드시 배열이다. 이번 partition에 실제로 제공된 marker가 하나도 없으면 그 사실 객체를 생성하지 않는다. missingEvidence를 근거 없는 사실 객체의 대체물로 사용하지 않는다.

[FACT_PRESERVATION_RULES]
원문에서 확인되는 서로 다른 경험, 기술과 사용 맥락, 역할과 책임, 결과와 성과를 임의로 누락하지 않는다. 배열의 개수 고정 상한을 적용하지 않는다.
experiences는 경험 또는 프로젝트 단위, skills는 기술과 사용 맥락, roles는 담당 역할과 책임, results는 결과 또는 성과만 표현한다. 같은 marker를 사용해도 의미가 다른 경험·기술·역할·성과는 모두 유지한다.
같은 사실을 같은 배열 또는 여러 배열에 문장만 바꾸어 반복하지 않는다. 각 항목은 가능한 한 하나의 독립 사실만 표현한다.
사실을 요약하는 문자열은 220자 이내의 간결한 문장으로 작성한다. 단순 절단하지 말고 의미를 보존해 다시 쓰며, 서로 다른 사실이 함께 있으면 각각 별도 항목으로 분리한다.

[OUTPUT_CONTRACT]
최상위 필드는 availableDocumentTypes, resume, coverLetter, portfolio, experienceNote, missingEvidence만 사용한다. missingEvidence와 선택된 객체 안의 모든 배열은 반드시 포함하며, 항목이 없으면 []를 사용한다.
availableDocumentTypes는 실제 입력에 존재하는 RESUME, COVER_LETTER, PORTFOLIO, EXPERIENCE_NOTE를 중복 없이 정확히 한 번씩만 반환한다.
실제 입력에 없는 문서 유형의 객체는 반드시 JSON null로 반환한다. 실제 입력에 있는 문서 유형의 객체는 반드시 반환한다.
resume은 experiences, skills, roles, results 배열을 가진다. experiences 항목은 title, summary, sourceRef, additionalSourceRefs가 필수이고 period는 null 가능하다. experienceId는 서버가 부여하므로 반환하지 않는다. skills 항목은 skill, usageContext, sourceRef, additionalSourceRefs가 필수이고 roles 항목은 role, context, sourceRef, additionalSourceRefs가 필수이며 results 항목은 result, sourceRef, additionalSourceRefs가 필수다.
coverLetter의 motivation, values, jobConnection은 null 가능하다. null이 아닌 경우 summary, sourceRef, additionalSourceRefs가 필수다. experienceNarratives는 반드시 배열이며 각 항목은 summary, sourceRef, additionalSourceRefs가 필수다.
portfolio는 projects 배열을 가진다. 각 project는 projectName, structure, role, contributions, techUsageReasons, problemSolving, outputs, sourceRef, additionalSourceRefs를 가진다. projectId는 서버가 부여하므로 반환하지 않는다. 문자열 필드는 비어 있지 않으며 배열은 null이 아니다.
experienceNote는 starCandidates 배열을 가진다. 각 항목은 situation, task, action, result, missingParts, sourceRef, additionalSourceRefs를 가진다. candidateId는 서버가 부여하므로 반환하지 않는다. missingParts는 반드시 배열이다. situation, task, action, result는 null 가능하며 missingParts에는 SITUATION, TASK, ACTION, RESULT만 사용한다.
missingEvidence의 각 항목은 item과 reason을 가지며 두 문자열은 비어 있지 않다. 원문에 없는 사실을 추정하지 않는다.

[JSON_SHAPE]
{
  "availableDocumentTypes": ["RESUME"],
  "resume": {"experiences": [{"title": "string", "period": null, "summary": "string", "sourceRef": {"extractionId": 1, "documentId": 1, "documentType": "RESUME", "pageNumber": 1, "segmentId": "string"}, "additionalSourceRefs": []}], "skills": [], "roles": [], "results": []},
  "coverLetter": null,
  "portfolio": null,
  "experienceNote": null,
  "missingEvidence": [{"item": "string", "reason": "string"}]
}','입력에 없는 사실, 근거 위치, 문서 유형을 추정하거나 생성하지 않는다. evidenceText 필드를 출력하지 않는다. 동일 사실을 문장만 바꾸어 여러 배열에 반복하지 않는다. 출력 JSON 스키마 밖의 필드를 추가하지 않는다.',TRUE,CURRENT_TIMESTAMP);
COMMIT;





-- JSON-05 v1.10 | 직무 가이드
-- 이 블록은 해당 JSON의 현재 활성 최신본이다.
-- v1.9 품질 기준에 한국어 문장 자체 점검을 추가한 JSON-05 v1.10을 활성화한다.
START TRANSACTION;
UPDATE prompt_template SET is_active = FALSE WHERE target_json = 'JSON-05' AND is_active = TRUE;
INSERT INTO prompt_template
    (prompt_code, name, version, target_json, template_text, forbidden_rules, is_active, created_at)
SELECT 'PT-JSON05-001', '맞춤 종합 분석 프롬프트', 'v1.10', 'JSON-05',
'당신은 채용공고 requirement와 지원자 근거를 비교하는 JSON-05 분석기다.
설명문 없이 출력 schema의 단일 JSON 객체만 반환한다.

<SYNTHESIS_INPUT>{{synthesisInputJson}}</SYNTHESIS_INPUT>

[분석]
- requirementCatalog의 모든 requirementId를 decisionById와 narrativesById에 정확히 한 번 채운다.
- 근거가 없으면 NONE 또는 INSUFFICIENT를 선택한다.
- 근거가 있으면 schema enum의 HIGH::evidenceId, MEDIUM::evidenceId, LOW::evidenceId 중 가장 타당한 하나를 선택한다.
- HIGH는 요구사항을 직접 입증하는 구체적 역할·행동·성과가 있을 때만 사용한다.
- MEDIUM은 관련 경험은 있으나 범위·깊이·성과 중 일부가 부족할 때 사용한다.
- LOW는 간접 경험이나 제한적인 단서만 있을 때 사용한다.
- reason은 요구사항과 선택한 지원자 근거를 비교한 판단 이유를 구체적으로 작성한다.
- missingPoint는 부족한 역량명이 아니라 현재 근거에서 확인되지 않은 경험·행동·성과를 작성한다.
- candidateEvidence는 근거 원문의 단순 반복이 아니라 요구사항과 연결되는 지원자의 역할·행동·성과를 요약한다.
- taskSuggestion은 missingPoint를 실제로 보완할 수 있는 구체적인 준비 행동을 작성한다.
- HIGH이면 missingPoint와 taskSuggestion은 빈 문자열이다.
- NONE/INSUFFICIENT이면 candidateEvidence는 빈 문자열이다.

[질문]
- generationPolicy.questionGenerationEnabled=true이면 questions 배열을 반환하고 false이면 null이다.
- 질문은 근거와 확인 가치가 있는 만큼 만들되 3~6개를 목표로 하고 절대 10개를 넘기지 않는다.
- relatedRequirementId는 서로 다른 직무 핵심 요구사항을 우선하며 같은 요구사항과 질문 의도를 불필요하게 반복하지 않는다.
- 우선순위는 지원자 근거가 있는 직무 핵심 HIGH, 역할·판단·성과가 모호한 MEDIUM, 실제 확인 가치가 있는 LOW 순이다.
- NONE/INSUFFICIENT와 지원자 근거가 없는 요구사항은 질문이 아니라 taskSuggestion으로 보완한다.
- 학력무관, 경력무관, 성별·연령 조건, 관련 학과·우대전공 같은 행정·조건성 항목은 질문 대상으로 선택하지 않는다.
- 선택한 요구사항과 candidate evidence를 바탕으로 실제 상황·본인 역할·판단·행동·결과 중 부족한 내용을 답하게 묻는다.
- “관련 경험을 설명해 주세요” 같은 일반 질문이나 답을 유도하는 질문은 피한다.
- question은 한 번에 하나의 핵심만 묻고, intent에는 평가자가 확인할 구체적인 판단 기준을 작성한다.
- 질문을 자연스러운 한국어 한 문장으로 작성하고, 출력 전에 오탈자·중복 표현·조사 호응을 확인한다.
- 각 question에는 placeholder, TODO, TBD, N/A 같은 임시 문구를 절대 반환하지 않는다.
- questionDirection과 avoidQuestions가 있으면 반드시 반영한다.
- evidenceId와 evaluationFocus는 schema enum에서 하나씩 선택한다.

[서버 책임]
- readiness는 reason과 limitations만 반환한다.
- status, canGenerateQuestions, source identity, sourceRefs, 각 저장 ID와 저장 형식은 서버가 결정한다.

반환 전에 모든 requirement key, enum 값, 필수 필드를 확인한다.',
'Do not invent decision values or evidence IDs. Do not omit required fields.',
TRUE, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM prompt_template WHERE target_json = 'JSON-05' AND version = 'v1.10'
);
UPDATE prompt_template
SET template_text = REPLACE(
        template_text,
        '- generationPolicy.questionGenerationEnabled=true이면 primaryQuestion을 하나 반환하고 false이면 null이다.
- relatedRequirementId는 requirementCatalog에서 면접 확인 가치가 가장 높은 요구사항 하나를 선택한다.
- 우선순위는 낮은 충족도, 중요한 부족점, 근거의 모호함 순이다.',
        '- generationPolicy.questionGenerationEnabled=true이면 questions 배열을 반환하고 false이면 null이다.
- 질문은 근거와 확인 가치가 있는 만큼 만들되 3~6개를 목표로 하고 절대 10개를 넘기지 않는다.
- relatedRequirementId는 서로 다른 직무 핵심 요구사항을 우선하며 같은 요구사항과 질문 의도를 불필요하게 반복하지 않는다.
- 우선순위는 지원자 근거가 있는 직무 핵심 HIGH, 역할·판단·성과가 모호한 MEDIUM, 실제 확인 가치가 있는 LOW 순이다.
- NONE/INSUFFICIENT와 지원자 근거가 없는 요구사항은 질문이 아니라 taskSuggestion으로 보완한다.
- 학력무관, 경력무관, 성별·연령 조건, 관련 학과·우대전공 같은 행정·조건성 항목은 질문 대상으로 선택하지 않는다.'
    )
WHERE target_json = 'JSON-05'
  AND version = 'v1.10'
  AND template_text LIKE '%primaryQuestion을 하나 반환%';
UPDATE prompt_template
SET template_text = REPLACE(
        template_text,
        '- 질문을 자연스러운 한국어 한 문장으로 작성하고, 출력 전에 오탈자·중복 표현·조사 호응을 확인한다.',
        '- 질문을 자연스러운 한국어 한 문장으로 작성하고, 출력 전에 오탈자·중복 표현·조사 호응을 확인한다.
- 각 question에는 placeholder, TODO, TBD, N/A 같은 임시 문구를 절대 반환하지 않는다.'
    )
WHERE target_json = 'JSON-05'
  AND version = 'v1.10'
  AND template_text NOT LIKE '%placeholder, TODO, TBD, N/A%';
UPDATE prompt_template
SET template_text = REPLACE(
        template_text,
        '- question에는 placeholder, TODO, TBD, N/A 같은 임시 문구를 절대 반환하지 않는다.',
        '- 각 question에는 placeholder, TODO, TBD, N/A 같은 임시 문구를 절대 반환하지 않는다.'
    )
WHERE target_json = 'JSON-05'
  AND version = 'v1.10';
UPDATE prompt_template SET is_active = TRUE WHERE target_json = 'JSON-05' AND version = 'v1.10';
COMMIT;

-- 실행 후 확인: 각 JSON별 active 행은 정확히 1개여야 한다.
-- (면접 질문 생성 JSON-12, 답변 평가 JSON-06-V2는 앱이 처음 실행될 때 PromptTemplateSeeder가 넣는다.)
SELECT target_json, prompt_code, version, is_active
FROM prompt_template
WHERE target_json IN ('JSON-01','JSON-02','JSON-05')
ORDER BY target_json, prompt_template_id;
