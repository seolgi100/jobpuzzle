// landing.js — 메인: HERO 3D 퍼즐 배치, 스크롤 등장 효과, 서비스 흐름 단계 강조, 로그인 상태에 따른 시작 버튼
  // HERO 퍼즐: 스카이 블루 단색 톤 + 흰 J 엠블럼 (순서: 좌상·상중·우상·좌하·하중·우하, 상중·하중이 J 조각)
  (function () {
    var look = document.getElementById('jobPuzzleCanvas')._puzzleLook;
    if (!look) return;
    look.setColors(['#7DB4F5','#3B82F6','#93C5FD','#60A5FA','#2F74E8','#A9D0FF'], 1.3);
    var logo = new Image();
    logo.onload = function () { look.setEmblem(logo, .8); };
    // 뒷면: 사이트 이름 (Pretendard 로드 후 그려야 글꼴이 맞음)
    (document.fonts ? document.fonts.load('800 100px Pretendard') : Promise.resolve()).catch(function () {}).then(function () { look.setBackText('JobPuzzle'); });
    // 미리보기 카드 화살표: 단계를 넘기면 퍼즐도 그 단계만큼 맞춰짐
    document.getElementById('holoPrev').onclick = look.prev;
    document.getElementById('holoNext').onclick = look.next;
    look.setAnchor(document.getElementById('heroPuzzleAnchor'));
    // ── HERO 확정안(⑭ 배경 퍼즐 격자 · 가운데 아래 빈칸) ──
    // 배경 전체가 퍼즐 이음선, 빈칸(.hero__puzzle-anchor 자리)에 화면 곳곳에 떠 있는 조각을 끼워 맞춤
    var hero = document.querySelector('.hero');
    look.setGhost('grid');
    look.setOuterEdges([1,0,-1, 1,0, -1,1,0, 0,-1]); // 바깥 테두리: 돌출·홈·직선 섞기
    look.setDragMode('piece');                       // 드래그: 조각 잡아서 끼우기 (빈칸 어디든 놓으면 자기 자리로)
    look.setFloatSpeed(.4);                          // 떠다니는 속도 40%
    look.setAuto(3);                                 // 3초마다 자동으로 한 조각 (넘어갈 때마다·놓을 때마다 다시 3초)

    // 흩어진 조각: 빈칸 주변 퍼짐 반경 안에서 랜덤, 글씨·카드·헤더·빈칸과 서로를 피해서 (방문마다 다른 배치)
    var SPREAD = .4, BLOCK = '.hero__title, .hero__desc, .hero__cta, .hero__card-wrap, .site-header, .hero-puzzle-caption', seed = Date.now();
    function rng(s) { return function () { s |= 0; s = s + 0x6D2B79F5 | 0; var t = Math.imul(s ^ s >>> 15, 1 | s); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
    function placeRandom() {
      var H = hero.getBoundingClientRect(), A = document.getElementById('heroPuzzleAnchor').getBoundingClientRect();
      var size = look.roamPieceSize(), pad = size * .6, rand = rng(seed); // pad: 조각 반지름 정도의 여백
      var cx = A.left + A.width / 2, cy = A.top + A.height / 2, holeR = Math.hypot(A.width, A.height) / 2;
      var blocks = Array.prototype.map.call(document.querySelectorAll(BLOCK), function (e) { return e.getBoundingClientRect(); })
        .filter(function (r) { return r.width; }).concat([A]);
      var maxR = holeR + pad + SPREAD * Math.hypot(H.width, H.height) * .55, placed = [];
      function ok(x, y) {
        if (x < H.left + pad || x > H.right - pad || y < H.top + pad || y > Math.min(H.bottom, innerHeight) - pad) return false;
        for (var i = 0; i < blocks.length; i++) { var b = blocks[i]; if (x > b.left - pad && x < b.right + pad && y > b.top - pad && y < b.bottom + pad) return false; }
        return placed.every(function (q) { return Math.hypot(q[0] - x, q[1] - y) > size * 1.3; });
      }
      for (var n = 0; n < 6; n++) {
        var pt = null;
        for (var tries = 0; tries < 1500 && !pt; tries++) {
          var x, y;
          if (tries < 900) { // 퍼짐 반경 안에서, 자리가 없으면 반경을 점점 넓힘
            var R = maxR * (1 + Math.floor(tries / 300) * .35), a = rand() * Math.PI * 2, d = holeR + pad * .5 + rand() * (R - holeR);
            x = cx + Math.cos(a) * d; y = cy + Math.sin(a) * d;
          } else { // 그래도 없으면 HERO 화면 전체에서
            x = H.left + rand() * H.width; y = H.top + rand() * Math.min(H.height, innerHeight);
          }
          if (ok(x, y)) pt = [x, y];
        }
        placed.push(pt || [cx + (n - 2.5) * size, H.bottom - pad]);
      }
      look.setRoam(placed.map(function (q) { return [(q[0] - H.left) / H.width * 2 - 1, 1 - (q[1] - H.top) / H.height * 2]; }));
    }
    // 첫 프레임(시점 계산)과 레이아웃이 잡힌 뒤 배치, 창 크기가 바뀌면 다시
    requestAnimationFrame(function () { requestAnimationFrame(placeRandom); });
    addEventListener('resize', placeRandom);
    logo.src = window.JP_LOGO_MASK;
  })();
(function () {
  var reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;

  // 1) HERO 진입: 제목 줄 마스크 → 나머지 순차 등장
  requestAnimationFrame(function () {
    document.querySelector('.hero').classList.add('is-entered');
    document.querySelectorAll('[data-hero]').forEach(function (el) { el.classList.add('is-visible'); });
  });

  // 2) 헤더는 공통 남색 헤더 하나 (스크롤해도 그대로)
  var hero = document.querySelector('.hero');

  // 3) 스크롤 등장 (리포트 막대, 단계 연결선 포함)
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (!e.isIntersecting) return;
      e.target.classList.add('is-visible');
      io.unobserve(e.target);
    });
  }, { threshold: 0.2, rootMargin: '0px 0px -40px 0px' });
  document.querySelectorAll('[data-reveal]:not([data-hero]), [data-steps]').forEach(function (el) { io.observe(el); });

  // 4) 서비스 흐름: 화면 가운데 들어온 단계를 현재 단계로
  var stages = [].slice.call(document.querySelectorAll('.stage'));
  var active = document.getElementById('storyActive');
  var stageIo = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (!e.isIntersecting) return;
      var i = stages.indexOf(e.target);
      stages.forEach(function (s) { s.classList.toggle('is-current', s === e.target); });
      active.classList.add('is-changing');
      setTimeout(function () {
        document.getElementById('storyIndex').textContent = '0' + (i + 1) + ' / 04';
        document.getElementById('storyTitle').textContent = e.target.dataset.title;
        active.classList.remove('is-changing');
      }, 160);
      document.getElementById('storyBar').style.width = ((i + 1) / stages.length * 100) + '%';
    });
  }, { rootMargin: '-45% 0px -45% 0px' });
  stages.forEach(function (s) { stageIo.observe(s); });

  // 5) HERO 카드: 마우스 위치에 따라 살짝 기울기 (기존 6~8° → 3°)
  var card = document.getElementById('heroCard');
  if (!reduce) {
    card.addEventListener('pointermove', function (e) {
      var r = card.getBoundingClientRect();
      card.style.setProperty('--tilt-y', ((e.clientX - r.left) / r.width - .5) * 6 + 'deg');
      card.style.setProperty('--tilt-x', -((e.clientY - r.top) / r.height - .5) * 6 + 'deg');
    });
    card.addEventListener('pointerleave', function () {
      card.style.setProperty('--tilt-x', '0deg');
      card.style.setProperty('--tilt-y', '0deg');
    });
  }
})();
(function () {
  // 로그인한 사용자는 시작 버튼을 대시보드로 (header.js가 /user/me 결과를 알려줌)
  document.addEventListener('guest-header-auth', function (e) {
    if (!e.detail) return;
    var dash = window.JobPuzzleRoutes.path('/dashboard');
    var hero = document.getElementById('hero-cta'), bottom = document.getElementById('bottom-cta');
    hero.href = dash; hero.textContent = '대시보드로 가기';
    bottom.href = dash; bottom.textContent = '이어서 준비하기';
  });
})();
