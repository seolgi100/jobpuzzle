// admin-guide-usage.js — 관리자 가이드 사용 이력 조회 (/jobpuzzle/admin-api/guide-usage)
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

  var MATCH_TYPE_BADGE = {
    EXACT: { bg: '#EEF1F5', color: '#12151B', label: '정확매칭' },
    FALLBACK_SAME_SUBCATEGORY: { bg: '#F6EFE8', color: '#8A4B14', label: '동일중분류 폴백' },
    FALLBACK_PARENT_CATEGORY: { bg: '#F6EFE8', color: '#8A4B14', label: '상위분류 폴백' },
    FALLBACK_COMMON: { bg: '#F6EFE8', color: '#8A4B14', label: '공통가이드 폴백' },
    NONE: { bg: '#F6EFE8', color: '#8A4B14', label: '매칭없음' }
  };

  function pill(label, bg, color) {
    return '<span class="badge-pill" style="background:' + bg + '; color:' + color + ';">' + esc(label) + '</span>';
  }

  var state = { page: 0, pageData: null };

  function load() {
    return api('/admin/guide-usage?page=' + state.page + '&size=10').then(function (page) {
      state.pageData = page;
      renderRows();
      renderPagination();
    }).catch(function (e) { alert(e.message); });
  }

  function renderRows() {
    var rows = (state.pageData && state.pageData.content || []).map(function (r) {
      var match = MATCH_TYPE_BADGE[r.matchType] || { bg: '#F5F7FA', color: '#4F5764', label: r.matchType };
      return '<div class="table-row" style="grid-template-columns:0.9fr 1.4fr 0.9fr 0.7fr 0.9fr 0.9fr 1.1fr;">' +
        '<span style="font-size:13px; font-weight:600;">' + esc(r.loginId) + '</span>' +
        '<span style="font-size:12.5px; color:#4F5764;">' + esc(r.mainCategory) + ' · ' + esc(r.subCategory) + '</span>' +
        '<span>' + pill(match.label, match.bg, match.color) + '</span>' +
        '<span style="font-size:12.5px; color:' + (r.fallbackApplied ? '#8A4B14' : '#9BA3B0') + ';">' + (r.fallbackApplied ? '적용' : '-') + '</span>' +
        '<span style="font-size:12.5px; color:' + (r.insufficient ? '#8A4B14' : '#9BA3B0') + ';">' + (r.insufficient ? '불충분' : '-') + '</span>' +
        '<span style="font-size:12.5px; color:#4F5764;">' + (r.guideId ? ('#' + r.guideId + (r.guideVersion ? ' v' + esc(r.guideVersion) : '')) : '-') + '</span>' +
        '<span style="font-size:12.5px; color:#9BA3B0;">' + (r.createdAt || '').replace('T', ' ').slice(0, 16) + '</span>' +
      '</div>';
    }).join('');
    document.getElementById('guide-usage-rows').innerHTML = rows || '<div class="table-empty">가이드 사용 이력이 없습니다</div>';
  }

  function renderPagination() {
    var p = state.pageData;
    var container = document.getElementById('guide-usage-pagination');
    if (!p || p.totalPages <= 1) { container.innerHTML = ''; return; }

    var current = p.page;
    var total = p.totalPages;
    var windowSize = 5;
    var windowStart = Math.max(0, Math.min(current - Math.floor(windowSize / 2), total - windowSize));
    var windowEnd = Math.min(total, windowStart + windowSize);

    function pageBtn(label, target, disabled) {
      return '<button class="btn-sm" data-page="' + target + '"' + (disabled ? ' disabled' : '') + '>' + label + '</button>';
    }

    var html = pageBtn('처음', 0, current === 0) + pageBtn('이전', current - 1, current === 0);
    for (var i = windowStart; i < windowEnd; i++) {
      html += '<button class="btn-sm' + (i === current ? ' btn-sm--primary-tint' : '') + '" data-page="' + i + '">' + (i + 1) + '</button>';
    }
    html += pageBtn('다음', current + 1, current === total - 1) + pageBtn('마지막', total - 1, current === total - 1);

    container.innerHTML = html;
    document.querySelectorAll('[data-page]').forEach(function (b) {
      b.addEventListener('click', function () {
        var target = parseInt(b.dataset.page, 10);
        if (target < 0 || target > total - 1) return;
        state.page = target;
        load();
      });
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    if (!document.getElementById('guide-usage-rows')) return;
    load();
  });
})();
