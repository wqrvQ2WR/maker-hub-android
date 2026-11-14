// Raspberry Pi Connect 화면공유 보정. 앱이 connect.raspberrypi.com 페이지마다 넣는다.
//
// 1) 해상도: Connect 는 noVNC 의 resizeSession 으로 파이 데스크톱 해상도를 브라우저 영역 크기(CSS px)에 맞춘다.
//    폰에서는 411x914 같은 좁은 데스크톱이 되어 창이 화면 밖으로 잘린다. 요청 해상도만 기기 실제 픽셀 크기로 키운다.
//    비율은 화면 영역과 같으니 scaleViewport 가 잘림 없이 맞춰 준다.
// 2) 두 손가락 확대: noVNC 는 두 손가락 제스처를 원격 스크롤/Ctrl+휠로 보낸다. 두 손가락은 화면 확대·이동으로 쓰고,
//    한 손가락 탭·드래그는 그대로 원격 마우스로 둔다.
(function () {
  var VERSION = 2;
  if (window.__mhConnectFix === VERSION) return;
  window.__mhConnectFix = VERSION;

  var MAX_ZOOM = 6;

  function desired(s) {
    if (!s.w || !s.h) return s;
    var k = Math.max(window.devicePixelRatio || 1, 1024 / Math.min(s.w, s.h));
    k = Math.min(k, 3840 / Math.max(s.w, s.h));
    return { w: Math.floor((s.w * k) / 2) * 2, h: Math.floor((s.h * k) / 2) * 2 };
  }

  function wrapResize(rfb) {
    var request = rfb._requestRemoteResize;
    rfb._requestRemoteResize = function () {
      var self = this;
      var screenSize = this._screenSize;
      this._screenSize = function () { return desired(screenSize.call(self)); };
      try { return request.apply(this, arguments); } finally { delete this._screenSize; }
    };
  }

  // 파이가 막 다른 크기로 바뀌는 중이면 "Resize is administratively prohibited" 로 거절한다.
  // 원래 크기로 남지 않게 5초 간격으로 같은 크기를 6번까지 다시 청한다.
  function retryResize(rfb) {
    if (rfb._rfbConnectionState !== 'connected' || rfb._pendingRemoteResize || !rfb._resizeSession) return;
    var want = desired(rfb._screenSize());
    if (want.w === rfb._fbWidth && want.h === rfb._fbHeight) return;
    var key = want.w + 'x' + want.h;
    var now = Date.now();
    var t = rfb.__mhTries[key] || (rfb.__mhTries[key] = { n: 0, next: 0 });
    if (t.n >= 6 || now < t.next) return;
    t.n++;
    t.next = now + 5000;
    rfb._requestRemoteResize();
  }

  // noVNC 가 화면 크기에 맞춰 배율을 다시 잡을 때(키보드, 전체화면, 해상도 변경) 확대 배율을 곱해 유지한다.
  function wrapScale(rfb) {
    var update = rfb._updateScale;
    rfb.__mhZoom = 1;
    rfb.__mhFit = rfb._display.scale;
    rfb._updateScale = function () {
      update.apply(this, arguments);
      this.__mhFit = this._display.scale;
      if (this.__mhZoom > 1) this._display.scale = this.__mhFit * this.__mhZoom;
    };
  }

  function attachPinch(rfb) {
    var screen = rfb._screen;
    var canvas = rfb._canvas;
    var gestures = rfb._gestures;
    if (!screen || !canvas || screen.__mhPinch) return;
    screen.__mhPinch = true;

    var active = false;
    var start = null;

    function block(e) {
      e.stopPropagation();
      if (e.cancelable) e.preventDefault();
    }

    function measure(e) {
      var a = e.touches[0];
      var b = e.touches[1];
      return {
        x: (a.clientX + b.clientX) / 2,
        y: (a.clientY + b.clientY) / 2,
        d: Math.max(1, Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY)),
      };
    }

    // 손가락 사이 중점 아래의 원격 화면 좌표를 기억해 두고, 확대·이동 뒤에도 그 점이 중점 아래 오게 스크롤한다.
    function begin(e) {
      var p = measure(e);
      var r = canvas.getBoundingClientRect();
      var s = rfb._display.scale || 1;
      start = { d: p.d, z: rfb.__mhZoom, fx: (p.x - r.left) / s, fy: (p.y - r.top) / s };
    }

    function resetNoVncGesture() {
      if (!gestures) return;
      gestures._stopLongpressTimeout();
      gestures._stopTwoTouchTimeout();
      gestures._tracked = [];
      gestures._ignored = [];
      gestures._waitingRelease = false;
      gestures._state = 127;
    }

    function onStart(e) {
      if (active) {
        block(e);
        if (e.touches.length >= 2) begin(e);
        return;
      }
      if (e.touches.length < 2) return;
      // 한 손가락으로 이미 원격 드래그/길게 누르기가 시작됐으면 원격 마우스를 끊지 않는다.
      if (gestures && gestures._hasDetectedGesture()) return;
      // 첫 손가락은 noVNC 가 이미 받았다. 탭으로 처리되지 않게 추적 상태를 비운다.
      resetNoVncGesture();
      active = true;
      block(e);
      begin(e);
    }

    function onMove(e) {
      if (!active) return;
      block(e);
      if (e.touches.length < 2 || !start) return;
      var p = measure(e);
      var zoom = Math.min(MAX_ZOOM, Math.max(1, (start.z * p.d) / start.d));
      if (zoom < 1.03) zoom = 1;
      rfb.__mhZoom = zoom;
      var scale = (rfb.__mhFit || rfb._display.scale) * zoom;
      rfb._display.scale = scale;
      var r = canvas.getBoundingClientRect();
      screen.scrollLeft += r.left - (p.x - start.fx * scale);
      screen.scrollTop += r.top - (p.y - start.fy * scale);
    }

    function onEnd(e) {
      if (!active) return;
      block(e);
      if (e.touches.length >= 2) {
        begin(e);
      } else if (e.touches.length === 0) {
        active = false;
        start = null;
      }
    }

    var opts = { capture: true, passive: false };
    screen.addEventListener('touchstart', onStart, opts);
    screen.addEventListener('touchmove', onMove, opts);
    screen.addEventListener('touchend', onEnd, opts);
    screen.addEventListener('touchcancel', onEnd, opts);
  }

  function patch(rfb) {
    if (!rfb || typeof rfb._requestRemoteResize !== 'function' || typeof rfb._screenSize !== 'function' || !rfb._display) return;
    if (rfb.__mhPatched !== VERSION) {
      // 예전 버전 스크립트가 감싼 함수가 있으면 벗기고 다시 감싼다.
      delete rfb._requestRemoteResize;
      delete rfb._updateScale;
      rfb.__mhPatched = VERSION;
      rfb.__mhTries = {};
      wrapResize(rfb);
      wrapScale(rfb);
      attachPinch(rfb);
    }
    retryResize(rfb);
  }

  function scan() {
    if (!window.Stimulus || !window.Stimulus.getControllerForElementAndIdentifier) return;
    var els = document.querySelectorAll('[data-controller~="vnc"]');
    for (var i = 0; i < els.length; i++) {
      var c = window.Stimulus.getControllerForElementAndIdentifier(els[i], 'vnc');
      if (c) patch(c.rfb);
    }
  }

  scan();
  setInterval(scan, 1000);
})();
