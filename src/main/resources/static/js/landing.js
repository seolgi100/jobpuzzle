// 메인(랜딩): 상단 메뉴 바탕 전환, 맞물리는 조각 조립, 모바일 메뉴, 로그인 상태에 따른 버튼
(function () {
  // 상단 메뉴: 첫 화면(어두움)을 벗어나면 밝은 바탕으로
  var nav = document.getElementById('nav');
  new IntersectionObserver(function (entries) { nav.classList.toggle('is-light', !entries[0].isIntersecting); },
    { rootMargin: '-72px 0px 0px 0px', threshold: 0 }).observe(document.getElementById('hero'));
  function atTop() { nav.classList.toggle('is-top', window.scrollY < 8); }
  window.addEventListener('scroll', atTop, { passive: true }); atTop();

  // 맞물리는 조각: 화면에 들어오면 한 번 조립
  var board = document.getElementById('board');
  var io = new IntersectionObserver(function (entries) {
    if (entries[0].isIntersecting) { board.classList.add('is-assembled'); io.disconnect(); }
  }, { threshold: 0.25 });
  io.observe(board);

  // 모바일 메뉴: 항목을 누르면 닫힘
  document.querySelectorAll('.sheet a').forEach(function (a) {
    a.addEventListener('click', function () { a.closest('details').removeAttribute('open'); });
  });

  // 로그인한 사용자: 로그인 링크를 숨기고 시작 버튼을 대시보드로
  fetch(window.JobPuzzleRoutes.path('/user/me'), { credentials: 'same-origin' })
    .then(function (response) { return response.ok ? response.json() : null; })
    .then(function (body) {
      if (!body || !body.success) return;
      var dash = window.JobPuzzleRoutes.path('/dashboard');
      document.querySelectorAll('[data-guest-only]').forEach(function (el) { el.hidden = true; });
      document.querySelectorAll('[data-start]').forEach(function (a) {
        a.href = dash;
        a.firstChild.textContent = a.getAttribute('data-start');
      });
    })
    .catch(function () { /* 비회원 화면 그대로 */ });
})();
