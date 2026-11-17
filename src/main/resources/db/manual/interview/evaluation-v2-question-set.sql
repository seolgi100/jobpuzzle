-- 평가 명세 v2 질문 생성 (수정사항 20번). 데이터는 지우지 않고 제약만 바꾼다.
-- 1) 인성 면접 질문 묶음은 AI를 부르지 않아 AI 호출 기록이 없다.
ALTER TABLE question_set
    MODIFY COLUMN ai_call_log_id BIGINT NULL;

-- 2) 같은 분석 결과로 같은 모드 면접을 여러 번 만들 수 있어야 한다 (기초 지식·자료 기반, 이후 질문 다시 생성).
--    이 유일 인덱스가 snapshot_id 외래 키도 받치고 있어, 일반 인덱스를 먼저 만들고 같은 문장에서 지운다.
ALTER TABLE question_set
    ADD INDEX idx_question_set_snapshot (snapshot_id),
    DROP INDEX uk_question_set_snapshot_mode;
