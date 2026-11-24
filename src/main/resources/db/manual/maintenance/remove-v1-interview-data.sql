-- v1 면접(기본·회사 맞춤·약점 보완)·v1 최종 리포트를 코드에서 지운 뒤, 예전 로컬/개발 DB를 맞추는 정리 스크립트.
-- 새로 만든 DB(배포 서버)에는 실행할 필요가 없다. 실행 전에 반드시 DB를 백업한다.
-- 순서: 1) v1 데이터 행 삭제  2) 엔티티가 없는 테이블 삭제  3) 엔티티 필드가 없는 컬럼 삭제

-- 1) v1 데이터 행 ------------------------------------------------------------
DROP TABLE IF EXISTS tmp_v1_sets, tmp_v1_sessions, tmp_v1_sq, tmp_v1_msg, tmp_v1_eval, tmp_v1_logs;

CREATE TABLE tmp_v1_sets AS
SELECT question_set_id FROM question_set WHERE mode IN ('BASIC', 'COMPANY_FIT', 'WEAKNESS_REVIEW');

CREATE TABLE tmp_v1_sessions AS
SELECT session_id FROM interview_session
WHERE mode IN ('BASIC', 'COMPANY_FIT', 'WEAKNESS_REVIEW')
   OR question_set_id IN (SELECT question_set_id FROM tmp_v1_sets);

CREATE TABLE tmp_v1_sq AS
SELECT session_question_id FROM interview_session_question
WHERE session_id IN (SELECT session_id FROM tmp_v1_sessions);

CREATE TABLE tmp_v1_msg AS
SELECT message_id FROM interview_message
WHERE session_question_id IN (SELECT session_question_id FROM tmp_v1_sq);

CREATE TABLE tmp_v1_eval AS
SELECT evaluation_id FROM answer_evaluation
WHERE session_question_id IN (SELECT session_question_id FROM tmp_v1_sq)
   OR evaluation_mode IN ('BASIC', 'COMPANY_FIT', 'WEAKNESS_REVIEW');

CREATE TABLE tmp_v1_logs AS
SELECT ai_call_log_id FROM ai_call_log
WHERE execution_stage IN ('BASIC_QUESTION_GENERATION', 'WEAKNESS_QUESTION_GENERATION', 'WEAKNESS_REEVALUATION', 'FINAL_REPORT')
   OR input_reference_type IN ('JOB_CAREER_CRITERIA', 'INTERVIEW_SESSION', 'WEAKNESS_TAG');

-- v1 테이블이 v1 데이터를 참조하므로 먼저 지운다 (아래 2번에서 테이블 자체도 삭제)
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS improvement_suggestion, final_report,
    weakness_remediation_attempt, weakness_tag_log, weakness_tag_status;
SET FOREIGN_KEY_CHECKS = 1;

-- 약점 보완 질문의 출처 평가 컬럼 (FK 포함)
ALTER TABLE interview_question DROP FOREIGN KEY FKd114qifsc90q46lvn92ljivq2;
ALTER TABLE interview_question DROP COLUMN origin_evaluation_id;

DELETE FROM follow_up_question
WHERE evaluation_id IN (SELECT evaluation_id FROM tmp_v1_eval)
   OR question_message_id IN (SELECT message_id FROM tmp_v1_msg);
DELETE FROM answer_evaluation WHERE evaluation_id IN (SELECT evaluation_id FROM tmp_v1_eval);
UPDATE interview_message SET parent_message_id = NULL WHERE message_id IN (SELECT message_id FROM tmp_v1_msg);
DELETE FROM interview_message WHERE message_id IN (SELECT message_id FROM tmp_v1_msg);
DELETE FROM interview_session_question WHERE session_question_id IN (SELECT session_question_id FROM tmp_v1_sq);
UPDATE interview_session SET previous_session_id = NULL WHERE previous_session_id IN (SELECT session_id FROM tmp_v1_sessions);
DELETE FROM interview_session WHERE session_id IN (SELECT session_id FROM tmp_v1_sessions);
DELETE FROM interview_question WHERE question_set_id IN (SELECT question_set_id FROM tmp_v1_sets);
DELETE FROM question_set WHERE question_set_id IN (SELECT question_set_id FROM tmp_v1_sets);

DELETE FROM ticket_transaction WHERE ticket_usage IN ('BASIC', 'COMPANY_FIT', 'WEAKNESS_REVIEW');

UPDATE ai_call_log SET parent_ai_call_log_id = NULL WHERE parent_ai_call_log_id IN (SELECT ai_call_log_id FROM tmp_v1_logs);
UPDATE ai_call_log SET reused_from_call_id = NULL WHERE reused_from_call_id IN (SELECT ai_call_log_id FROM tmp_v1_logs);
DELETE FROM ai_call_log WHERE ai_call_log_id IN (SELECT ai_call_log_id FROM tmp_v1_logs);

DROP TABLE tmp_v1_sets, tmp_v1_sessions, tmp_v1_sq, tmp_v1_msg, tmp_v1_eval, tmp_v1_logs;

-- 2) 엔티티가 없는 테이블 ------------------------------------------------------
DROP TABLE IF EXISTS interview_view_preference, question_template, job_analysis_report, recommendation_log;

-- 3) 엔티티 필드가 없는 컬럼 ----------------------------------------------------
ALTER TABLE answer_evaluation
    DROP COLUMN pass_threshold,
    DROP COLUMN score_label,
    DROP COLUMN target_weakness_tag,
    DROP COLUMN target_dimension,
    DROP COLUMN weakness_tags,
    DROP COLUMN weakness_diagnostics;
ALTER TABLE interview_session
    DROP COLUMN basis_evaluation_ids,
    DROP COLUMN target_weakness_tag;
ALTER TABLE question_set
    DROP COLUMN basis_evaluation_ids,
    DROP COLUMN target_dimension,
    DROP COLUMN target_weakness_tag;
-- 예전 가이드 전처리·원본 파일 경로 컬럼 (코드에서 이미 쓰지 않음)
ALTER TABLE job_guide_document
    DROP COLUMN preprocessed_at,
    DROP COLUMN preprocessing_error,
    DROP COLUMN preprocessing_model,
    DROP COLUMN preprocessing_status;
ALTER TABLE user_document
    DROP COLUMN file_name,
    DROP COLUMN file_path;

-- 확인: 아래가 모두 0이어야 한다
SELECT COUNT(*) AS v1_sessions FROM interview_session WHERE mode IN ('BASIC', 'COMPANY_FIT', 'WEAKNESS_REVIEW');
SELECT COUNT(*) AS v1_sets FROM question_set WHERE mode IN ('BASIC', 'COMPANY_FIT', 'WEAKNESS_REVIEW');
SELECT COUNT(*) AS v1_tickets FROM ticket_transaction WHERE ticket_usage IN ('BASIC', 'COMPANY_FIT', 'WEAKNESS_REVIEW');
