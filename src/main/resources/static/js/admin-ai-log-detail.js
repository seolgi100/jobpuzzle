// admin-ai-log-detail.js — 관리자 AI 오류 로그 상세 페이지 (/admin/ai-logs/{id})
(function () {
  'use strict';

  function api(path) {
    return fetch(window.JobPuzzleRoutes.path(path.replace(/^\/admin/, '/admin-api')), { credentials: 'same-origin' }).then(function (res) {
      return res.json().then(function (body) {
        if (!res.ok || !body.success) {
          throw new Error((body && body.message) || '요청에 실패했습니다.');
        }
        return body.data;
      });
    });
  }

  var STATUS_BADGE = {
    PENDING: { bg: '#F5F7FA', color: '#4F5764', label: '대기' },
    RUNNING: { bg: '#EDF2FA', color: '#3A62A8', label: '실행중' },
    SUCCEEDED: { bg: '#EEF1F5', color: '#12151B', label: '성공' },
    FAILED: { bg: '#F6EFE8', color: '#8A4B14', label: '실패' }
  };

  function pill(label, bg, color) {
    return '<span class="badge-pill" style="background:' + bg + '; color:' + color + ';">' + esc(label) + '</span>';
  }

  function detailField(label, valueHtml) {
    return '<div><p class="field-label" style="margin-bottom:4px;">' + esc(label) + '</p>' +
      '<div style="font-size:13.5px;">' + (valueHtml || '<span style="color:#9BA3B0;">-</span>') + '</div></div>';
  }

  function section(title, innerHtml) {
    return '<section class="card card--pad-lg" style="display:flex; flex-direction:column; gap:14px;">' +
      '<p class="field-label--section" style="margin:0;">' + esc(title) + '</p>' +
      innerHtml +
      '</section>';
  }

  function grid(fieldsHtml) {
    return '<div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(200px, 1fr)); gap:14px 20px;">' + fieldsHtml + '</div>';
  }

  function text(value) {
    return value === null || value === undefined || value === '' ? null : esc(String(value));
  }

  function logLink(id) {
    if (!id) return null;
    return '<a href="' + window.JobPuzzleRoutes.path('/admin/ai-logs/' + id) + '" style="color:var(--jp-blue-deep); font-weight:600;">#' + id + '</a>';
  }

  function formatMetadata(raw) {
    if (!raw) return null;
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch (e) {
      return raw;
    }
  }

  function idFromUrl() {
    var parts = window.location.pathname.split('/').filter(Boolean);
    return parseInt(parts[parts.length - 1], 10);
  }

  var aiCallLogId = idFromUrl();

  function render(d) {
    var status = STATUS_BADGE[d.status] || { bg: '#F5F7FA', color: '#4F5764', label: d.status };
    document.getElementById('ai-log-detail-title').innerHTML =
      'AI 호출 로그 #' + d.aiCallLogId + ' ' + pill(status.label, status.bg, status.color);

    var prompt = [d.promptCode, d.promptName, d.promptVersion].filter(Boolean).join(' · ');
    var guide = [d.guideCode, d.guideTitle, d.guideVersion].filter(Boolean).join(' · ');
    var metadata = formatMetadata(d.providerCompletionMetadata);
    var started = (d.startedAt || '').replace('T', ' ').slice(0, 19);
    var completed = d.completedAt ? (d.completedAt || '').replace('T', ' ').slice(0, 19) : '진행중';

    var basicSection = section('기본 정보', grid(
      detailField('실행 단계', text(d.executionStage)) +
      detailField('호출 역할', text(d.callRole)) +
      detailField('provider / model', text(d.provider) + ' / ' + esc(d.model || '')) +
      detailField('입력 참조', text(d.inputReferenceType) + ' #' + esc(d.inputReferenceId || '')) +
      detailField('시작 시각', text(started)) +
      detailField('완료 시각', text(completed))
    ));

    var contextSection = section('프롬프트 · 가이드', grid(
      detailField('프롬프트', text(prompt)) +
      detailField('가이드', text(guide)) +
      detailField('입력 지문', d.inputFingerprint ? '<code style="font-size:12px;">' + esc(d.inputFingerprint) + '</code>' : null)
    ));

    var resultSection = section('실행 결과', grid(
      detailField('검증 결과', d.valid === null || d.valid === undefined ? null : (d.valid ? '통과' : '실패')) +
      detailField('에러 타입', d.errorType && d.errorType !== 'NONE' ? text(d.errorType) : null)
    ) + detailField('에러 메시지', text(d.errorMessage)));

    var retrySection = section('재시도 · 재사용 이력', grid(
      detailField('재사용 여부', d.reused ? ('재사용됨 (출처 ' + (logLink(d.reusedFromCallId) || '-') + ')') : '신규 호출') +
      detailField('재시도', d.retryCount + '회' + (d.parentAiCallLogId ? ' (이전 시도 ' + logLink(d.parentAiCallLogId) + ')' : ''))
    ));

    var metadataSection = section('provider 완료 메타데이터',
      metadata
        ? '<textarea class="textarea-input" readonly style="width:100%; height:180px; font-family:monospace; font-size:12px; box-sizing:border-box;">' + esc(metadata) + '</textarea>'
        : '<p style="font-size:13.5px; color:#9BA3B0; margin:0;">저장된 메타데이터가 없습니다</p>'
    );

    document.getElementById('ai-log-detail-body').innerHTML =
      basicSection + contextSection + resultSection + retrySection + metadataSection;
  }

  function load() {
    return api('/admin/ai-call-logs/' + aiCallLogId).then(render).catch(function (e) {
      document.getElementById('ai-log-detail-title').textContent = '로그를 불러올 수 없습니다';
      document.getElementById('ai-log-detail-body').innerHTML = '<p style="color:#8A4B14;">' + esc(e.message) + '</p>';
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    if (!document.getElementById('ai-log-detail-body') || !aiCallLogId) return;
    load();
  });
})();
