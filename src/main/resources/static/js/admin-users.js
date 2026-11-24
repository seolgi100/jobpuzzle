// admin-users.js — 관리자 회원 목록/검색 (/jobpuzzle/admin-api/users)
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

  var ROLE_BADGE = {
    ADMIN: { bg: '#EDF2FA', color: '#3A62A8', label: 'ADMIN' },
    USER: { bg: '#F5F7FA', color: '#4F5764', label: 'USER' }
  };
  var STATUS_BADGE = {
    ACTIVE: { bg: '#EEF1F5', color: '#12151B', label: '활성' },
    WITHDRAWN: { bg: '#F6EFE8', color: '#8A4B14', label: '탈퇴' }
  };
  var SOCIAL_ICON = {
    kakao: { bg: '#FEE500', color: '#3C1E1E', letter: 'K', title: '카카오 로그인' },
    google: { bg: '#4285F4', color: '#FFFFFF', letter: 'G', title: '구글 로그인' }
  };

  function pill(label, bg, color) {
    return '<span class="badge-pill" style="background:' + bg + '; color:' + color + ';">' + esc(label) + '</span>';
  }

  function socialIcon(provider) {
    var icon = SOCIAL_ICON[provider];
    if (!icon) return '';
    return '<span title="' + icon.title + '" style="display:inline-flex; align-items:center; justify-content:center; ' +
      'width:16px; height:16px; border-radius:50%; background:' + icon.bg + '; color:' + icon.color + '; ' +
      'font-size:10px; font-weight:700; line-height:1;">' + icon.letter + '</span>';
  }

  var state = {
    page: 0,
    keyword: '',
    searchField: 'LOGIN_ID',
    status: '',
    pageData: null
  };

  function loadUsers() {
    var qs = 'page=' + state.page + '&size=10';
    if (state.keyword) qs += '&keyword=' + encodeURIComponent(state.keyword) + '&searchField=' + state.searchField;
    if (state.status) qs += '&status=' + state.status;
    return api('/admin/users?' + qs).then(function (page) {
      state.pageData = page;
      renderRows();
      renderPagination();
    }).catch(function (e) { alert(e.message); });
  }

  function renderRows() {
    var users = (state.pageData && state.pageData.content) || [];
    var rows = users.map(function (u) {
      var role = ROLE_BADGE[u.role] || { bg: '#F5F7FA', color: '#4F5764', label: u.role };
      var status = STATUS_BADGE[u.status] || { bg: '#F5F7FA', color: '#4F5764', label: u.status };
      return '<div class="table-row" style="grid-template-columns:1fr 1fr 1.6fr 0.7fr 0.8fr 0.7fr 1fr;">' +
        '<span style="font-size:13px; font-weight:600; display:flex; align-items:center; flex-wrap:wrap; gap:5px; min-width:0;">' +
          socialIcon(u.socialProvider) + '<span style="overflow-wrap:anywhere;">' + esc(u.loginId) + '</span>' +
        '</span>' +
        '<span style="font-size:13px;">' + esc(u.name) + '</span>' +
        '<span style="font-size:12.5px; color:#4F5764;">' + esc(u.email) + '</span>' +
        '<span>' + pill(role.label, role.bg, role.color) + '</span>' +
        '<span>' + pill(status.label, status.bg, status.color) + '</span>' +
        '<span>' + (u.isLocked ? pill('잠김', '#F6EFE8', '#8A4B14') : '<span style="font-size:12.5px; color:#9BA3B0;">-</span>') + '</span>' +
        '<span style="font-size:12.5px; color:#9BA3B0;">' + (u.createdAt || '').slice(0, 10) + '</span>' +
      '</div>';
    }).join('');
    document.getElementById('admin-user-rows').innerHTML = rows || '<div class="table-empty">검색 결과가 없습니다</div>';
  }

  function renderPagination() {
    var p = state.pageData;
    var container = document.getElementById('admin-user-pagination');
    if (!p || p.totalPages <= 1) { container.innerHTML = ''; return; }

    var current = p.page;
    var total = p.totalPages;
    var windowSize = 5;
    var windowStart = Math.max(0, Math.min(current - Math.floor(windowSize / 2), total - windowSize));
    var windowEnd = Math.min(total, windowStart + windowSize);

    function pageBtn(label, target, disabled) {
      return '<button class="btn-sm" data-page="' + target + '"' + (disabled ? ' disabled' : '') + '>' + label + '</button>';
    }

    var html = pageBtn('처음', 0, current === 0);
    html += pageBtn('이전', current - 1, current === 0);
    for (var i = windowStart; i < windowEnd; i++) {
      html += '<button class="btn-sm' + (i === current ? ' btn-sm--primary-tint' : '') + '" data-page="' + i + '">' + (i + 1) + '</button>';
    }
    html += pageBtn('다음', current + 1, current === total - 1);
    html += pageBtn('마지막', total - 1, current === total - 1);

    container.innerHTML = html;
    document.querySelectorAll('[data-page]').forEach(function (b) {
      b.addEventListener('click', function () {
        var target = parseInt(b.dataset.page, 10);
        if (target < 0 || target > total - 1) return;
        state.page = target;
        loadUsers();
      });
    });
  }

  function search() {
    state.keyword = document.getElementById('user-search-input').value.trim();
    state.searchField = document.getElementById('user-search-field').value;
    state.status = document.getElementById('user-status-filter').value;
    state.page = 0;
    loadUsers();
  }

  function resetSearch() {
    document.getElementById('user-search-input').value = '';
    document.getElementById('user-search-field').value = 'LOGIN_ID';
    document.getElementById('user-status-filter').value = '';
    state.keyword = '';
    state.searchField = 'LOGIN_ID';
    state.status = '';
    state.page = 0;
    loadUsers();
  }

  document.addEventListener('DOMContentLoaded', function () {
    if (!document.getElementById('admin-user-rows')) return;
    document.getElementById('user-search-btn').addEventListener('click', search);
    document.getElementById('user-search-reset').addEventListener('click', resetSearch);
    document.getElementById('user-search-input').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') search();
    });
    loadUsers();
  });
})();
