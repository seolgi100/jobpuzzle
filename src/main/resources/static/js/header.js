// Shared adaptive Thymeleaf header: visual state, profile menus and real app authentication.
(function () {
    'use strict';

    // 메뉴와 트리거뿐 아니라 헤더의 레이어 상태도 함께 갱신해 아래 페이지 버튼으로 클릭이 통과하지 않게 한다.
    function setMenuState(trigger, menu, open) {
        menu.hidden = !open;
        trigger.setAttribute('aria-expanded', open ? 'true' : 'false');
        var header = trigger.closest('[data-dynamic-header]');
        if (header) header.classList.toggle('has-open-user-menu', open);
    }

    function closeMenus(except) {
        document.querySelectorAll('[data-user-menu-trigger]').forEach(function (trigger) {
            var menu = document.getElementById(trigger.getAttribute('data-user-menu-trigger'));
            if (!menu || menu === except) return;
            setMenuState(trigger, menu, false);
        });
    }

    // 각 사용자 칩에 메뉴 토글을 직접 연결하고 실제 바깥 포인터 입력에서만 메뉴를 닫는다.
    function initMenus() {
        document.querySelectorAll('[data-user-menu-trigger]').forEach(function (trigger) {
            trigger.addEventListener('click', function (event) {
                var menu = document.getElementById(trigger.getAttribute('data-user-menu-trigger'));
                if (!menu) return;
                event.preventDefault();
                event.stopPropagation();
                var opening = menu.hidden;
                closeMenus(menu);
                setMenuState(trigger, menu, opening);
            });
        });
        document.addEventListener('pointerdown', function (event) {
            if (event.target.closest('[data-user-menu-trigger], .user-menu')) return;
            closeMenus(null);
        });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') closeMenus(null);
        });
    }

    function initHeaderTheme() {
        var headers = Array.prototype.slice.call(document.querySelectorAll('[data-dynamic-header]'));
        if (!headers.length) return;
        var sections = Array.prototype.slice.call(document.querySelectorAll('[data-header-theme]'));
        var activeTheme = '';
        var raf = 0;
        headers.forEach(function (header) { header.classList.add('is-header-ready'); });

        function render() {
            var max = Math.max(document.documentElement.scrollHeight - window.innerHeight, 1);
            document.documentElement.style.setProperty('--page-scroll-progress', Math.max(0, Math.min(1, window.scrollY / max)).toFixed(4));
            headers.forEach(function (header) { header.classList.toggle('is-header-compact', window.scrollY > 18); });
            var probe = Math.max(6, headers[0].getBoundingClientRect().bottom + 2);
            var active = sections[0];
            sections.forEach(function (section) {
                var rect = section.getBoundingClientRect();
                if (rect.top <= probe && rect.bottom > probe) active = section;
            });
            var theme = active ? (active.getAttribute('data-header-theme') || 'light') : 'light';
            if (theme !== activeTheme) {
                activeTheme = theme;
                headers.forEach(function (header) {
                    header.classList.toggle('header--dark', theme === 'dark');
                    header.classList.toggle('header--light', theme !== 'dark');
                });
            }
            raf = 0;
        }

        function requestRender() {
            if (!raf) raf = window.requestAnimationFrame(render);
        }
        window.addEventListener('scroll', requestRender, { passive: true });
        window.addEventListener('resize', requestRender);
        requestRender();
    }

    function renderUserDisplay(user) {
        var name = user.name || user.loginId || '';
        var nameEl = document.getElementById('app-user-name');
        var avatarEl = document.getElementById('app-user-avatar');
        if (!nameEl || !avatarEl) return;

        nameEl.textContent = (user.name || user.loginId) + '님';
        avatarEl.textContent = (user.name || user.loginId).charAt(0);

        var adminLink = document.getElementById('app-admin-nav-link');
        if (adminLink) {
            adminLink.hidden = user.role !== 'ADMIN';
        }
    }

    function loadCurrentUser() {
        if (!document.getElementById('app-user-chip')) return;
        fetch(window.JobPuzzleRoutes.path('/user/me'), { credentials: 'same-origin' })
            .then(function (response) { return response.json().then(function (body) { return { ok: response.ok, body: body }; }); })
            .then(function (result) {
                if (result.ok && result.body.success) {
                    document.dispatchEvent(new CustomEvent('app-user-loaded', { detail: result.body.data }));
                    renderUserDisplay(result.body.data);
                    return;
                }
                window.location.href = window.JobPuzzleRoutes.path('/login');
            })
            .catch(function () { window.location.href = window.JobPuzzleRoutes.path('/login'); });
    }

    function bindLogout() {
        document.querySelectorAll('#logout-link, #app-logout-link').forEach(function (logoutLink) {
            logoutLink.addEventListener('click', function (event) {
                event.preventDefault();
                fetch(window.JobPuzzleRoutes.path('/user/logout'), { method: 'POST', credentials: 'same-origin' })
                    .finally(function () { window.location.href = window.JobPuzzleRoutes.path('/'); });
            });
        });
    }

    // landingHeader (guest/user 전환): 랜딩 페이지는 landing.js가 이미 처리하므로
    // 여기서는 auth-guest가 있고 landing.js가 없는 페이지(로그인, 회원가입, 아이디/비번 찾기 등)만 다룬다.
    function applyGuestHeaderState(user) {
        var authGuest = document.getElementById('auth-guest');
        var authUser = document.getElementById('auth-user');
        if (!authGuest && !authUser) return;

        if (user) {
            if (authGuest) authGuest.hidden = true;
            if (authUser) authUser.hidden = false;
            var name = user.name || user.loginId || '';
            var nameEl = document.getElementById('user-name');
            var avatarEl = document.getElementById('user-avatar');
            if (nameEl) nameEl.textContent = name + '님';
            if (avatarEl) avatarEl.textContent = name.charAt(0);
            var adminLink = document.getElementById('admin-nav-link');
            if (adminLink) adminLink.hidden = user.role !== 'ADMIN';
        } else {
            if (authGuest) authGuest.hidden = false;
            if (authUser) authUser.hidden = true;
        }
    }

    function loadGuestHeaderAuthState() {
        if (window.__jobPuzzleLandingHandlesAuthHeader) return;
        if (!document.getElementById('auth-guest')) return;
        fetch(window.JobPuzzleRoutes.path('/user/me'), { credentials: 'same-origin' })
            .then(function (response) { return response.json().then(function (body) { return { ok: response.ok, body: body }; }); })
            .then(function (result) {
                var user = result.ok && result.body.success ? result.body.data : null;
                applyGuestHeaderState(user);
                document.dispatchEvent(new CustomEvent('guest-header-auth', { detail: user }));
            })
            .catch(function () { applyGuestHeaderState(null); });
    }

    document.addEventListener('DOMContentLoaded', function () {
        initMenus();
        initHeaderTheme();
        bindLogout();
        loadCurrentUser();
        loadGuestHeaderAuthState();
        document.addEventListener('app-user-updated', function (event) { renderUserDisplay(event.detail); });
    });
})();
