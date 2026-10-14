/* =====================================================================
 * YangshiTV —— 央视频电视直播页注入脚本
 * ---------------------------------------------------------------------
 * 三件事：
 *   1) 把页面里的主播放器钉成全屏，页面其余部分清掉
 *   2) 清浮层（登录弹窗 / 下载 App 横幅 / 右侧节目单等）
 *   3) 把「当前正在播什么」回报给原生层
 *
 * 硬性约定：**绝不写死任何 class 名 / id**。
 * 央视频的 class 是构建时哈希出来的，随时会变。这里只用
 * 「位置 + 尺寸 + getComputedStyle 结果」做启发式判断。
 *
 * 页面事实（2026-10-14 用桌面 Chrome 探针实测，别再猜）：
 *   - 地址必须是 `/tv/home?pid=xxx`（query 形态）。
 *     `/#/tv/home?pid=xxx` 匹配不到路由 → 落到首页 pc-index，页面里没有 video。
 *   - 这站是 **history 路由**，不是 hash 路由。
 *   - 播放器是 **video.js**：<video class="video-js vjs-default-skin">，
 *     实测 3~6 秒起来，1920x1080，src 是 blob:（MSE），duration 约 20 秒（直播滑窗）。
 *   - 直播页是列表页：#app > .comPadding > .tv-home > .tv-home-list，
 *     播放器只占一块（762x430），旁边挂着频道列表 —— 所以必须剪枝，不能只清浮层。
 *
 * 语法保持 ES5（老电视盒子内核可能很旧）。
 * ===================================================================== */
(function () {
  'use strict';

  var VERSION = 3;

  /* 已经装过一遍就只跑一次 tick，避免重复注册定时器 */
  if (window.__HTV__ && window.__HTV__.v === VERSION) {
    try { window.__HTV__.tick(); } catch (e) {}
    return;
  }

  /* ------------------------------------------------------------------
   * 基础工具
   * ------------------------------------------------------------------ */
  function vw() {
    return window.innerWidth ||
      document.documentElement.clientWidth ||
      (screen && screen.width) || 1920;
  }

  function vh() {
    return window.innerHeight ||
      document.documentElement.clientHeight ||
      (screen && screen.height) || 1080;
  }

  function cs(el) {
    try { return window.getComputedStyle(el); } catch (e) { return null; }
  }

  /* 只在值真的需要变时才写 style，减少无谓的样式重算 */
  function force(el, prop, val) {
    try {
      if (el.style.getPropertyValue(prop) !== val) {
        el.style.setProperty(prop, val, 'important');
      }
    } catch (e) {}
  }

  function isOurNode(el) {
    return !!(el && el.id && el.id.indexOf('__htv_') === 0);
  }

  function isHiddenByUs(el) {
    return el.getAttribute && el.getAttribute('data-htv-hidden') === '1';
  }

  /* ------------------------------------------------------------------
   * 找主播放器
   * 优先级：最大的 <video> → 最大的 <canvas> → 最大的 <iframe>
   * （央视频若把播放器放进 iframe，主文档里就找不到 video）
   * ------------------------------------------------------------------ */
  function pickLargest(list) {
    var best = null, bestArea = 0;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      var r = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
      if (!r || r.width < 80 || r.height < 60) continue;
      var s = cs(el);
      if (s && (s.display === 'none' || s.visibility === 'hidden')) continue;
      var area = r.width * r.height;
      if (area > bestArea) { bestArea = area; best = el; }
    }
    return best;
  }

  function findPlayer() {
    var v = pickLargest(document.querySelectorAll('video'));
    if (v) return { el: v, kind: 'video' };
    var c = pickLargest(document.querySelectorAll('canvas'));
    if (c) return { el: c, kind: 'canvas' };
    var f = pickLargest(document.querySelectorAll('iframe'));
    if (f) return { el: f, kind: 'iframe' };
    return null;
  }

  /* 从播放器往上收集祖先链（直到 body）
     这条链是「必须保留」的：清浮层时绝不碰它，反而要把它撑满全屏 */
  function buildChain(el) {
    var chain = [];
    var n = el;
    var guard = 0;
    while (n && n !== document.body && guard++ < 40) {
      chain.push(n);
      n = n.parentElement;
    }
    return chain;
  }

  function inChain(chain, el) {
    for (var i = 0; i < chain.length; i++) { if (chain[i] === el) return true; }
    return false;
  }

  /* ------------------------------------------------------------------
   * 把播放器链路钉成全屏，并且**只留播放器这一条枝**
   *
   * 央视频直播页其实是个"列表页"：#app > .comPadding > .tv-home > .tv-home-list，
   * 播放器只是里面的一块（实测 762x430），旁边和下面还挂着频道列表、导航。
   * 所以光把链撑满还不够 —— 那些 static 定位的旁支节点，
   * shouldHide() 是不认的（它只处理 fixed/absolute/sticky 的浮层），会露在播放器边上。
   * 这里对链上每一层做"剪枝"：只保留通往播放器的那一根枝，其余兄弟全部砍掉。
   * ------------------------------------------------------------------ */
  function pinPlayer(player, chain) {
    var i, el;

    /* 1) 整条链撑满全屏，逐层叠 z-index，保证压在一切浮层之上 */
    for (i = 0; i < chain.length; i++) {
      el = chain[i];
      force(el, 'position', 'fixed');
      force(el, 'left', '0');
      force(el, 'top', '0');
      force(el, 'right', '0');
      force(el, 'bottom', '0');
      force(el, 'width', '100%');
      force(el, 'height', '100%');
      force(el, 'max-width', 'none');
      force(el, 'max-height', 'none');
      force(el, 'min-width', '0');
      force(el, 'min-height', '0');
      force(el, 'margin', '0');
      force(el, 'padding', '0');
      force(el, 'border', '0');
      force(el, 'transform', 'none');
      force(el, 'overflow', 'hidden');
      force(el, 'background-color', '#000');
      force(el, 'z-index', String(2147483000 + i));
    }

    /* 2) 播放器本体铺满，保留原始画面比例（左右可能留黑边） */
    force(player, 'position', 'absolute');
    force(player, 'left', '0');
    force(player, 'top', '0');
    force(player, 'width', '100%');
    force(player, 'height', '100%');
    force(player, 'max-width', 'none');
    force(player, 'max-height', 'none');
    force(player, 'margin', '0');
    force(player, 'background-color', '#000');
    force(player, 'object-fit', 'contain');
    force(player, 'z-index', '2147483200');

    /* 3) 剪枝：从播放器的父层一路剪到 body，每层只留通往播放器的那一根枝 */
    for (i = 1; i <= chain.length; i++) {
      var parent = (i === chain.length) ? document.body : chain[i];
      if (!parent || !parent.children) continue;
      var keep = chain[i - 1];
      var kids = parent.children;
      for (var k = 0; k < kids.length; k++) {
        var c = kids[k];
        if (c === keep) continue;
        if (isOurNode(c)) continue;
        hideIt(c);
      }
    }
  }

  /* ------------------------------------------------------------------
   * 浮层判定 —— 全靠位置和尺寸，不看 class
   * ------------------------------------------------------------------ */
  function shouldHide(el) {
    if (!el || el.nodeType !== 1) return false;
    if (isOurNode(el)) return false;
    if (isHiddenByUs(el)) return false;

    var tag = el.tagName;
    if (tag === 'VIDEO' || tag === 'CANVAS' || tag === 'SCRIPT' ||
        tag === 'STYLE' || tag === 'LINK' || tag === 'HEAD' ||
        tag === 'HTML' || tag === 'BODY') return false;

    /* 里面装着播放器的容器，一律不碰 */
    if (el.querySelector && el.querySelector('video,canvas')) return false;

    var s = cs(el);
    if (!s) return false;
    if (s.display === 'none' || s.visibility === 'hidden') return false;
    if (parseFloat(s.opacity) === 0) return false;

    var floating = (s.position === 'fixed' || s.position === 'absolute' ||
                    s.position === 'sticky');
    if (!floating) return false;

    var r = el.getBoundingClientRect();
    var W = vw(), H = vh();
    if (r.width <= 0 || r.height <= 0) return false;

    var wRatio = r.width / W;
    var hRatio = r.height / H;
    var areaRatio = wRatio * hRatio;

    /* A. 几乎铺满整屏的浮层（登录墙 / 播放前广告 / 全屏遮罩） */
    if (wRatio > 0.88 && hRatio > 0.82) return true;

    /* B. 大面积浮层，含各种蒙层 */
    if (areaRatio > 0.34) return true;

    /* C. 右侧竖长条 —— 典型的就是节目单侧栏 */
    if (wRatio > 0.20 && hRatio > 0.52) return true;

    /* D. 横贯屏幕的窄条 —— 下载 App 横幅 / 登录提示条 */
    if (s.position === 'fixed' &&
        wRatio > 0.82 && hRatio >= 0.045 && hRatio < 0.22) return true;

    /* E. 固定定位的弹窗盒子（有背景色、不大不小、浮在中间） */
    if (s.position === 'fixed' &&
        areaRatio > 0.06 && areaRatio <= 0.34 &&
        r.width > W * 0.22 && r.height > H * 0.18) {
      var bg = s.backgroundColor || '';
      var hasBg = bg && bg !== 'transparent' &&
                  bg.indexOf('rgba(0, 0, 0, 0)') === -1;
      if (hasBg && parseFloat(s.zIndex) > 100) return true;
    }

    return false;
  }

  function hideIt(el) {
    el.setAttribute('data-htv-hidden', '1');
    force(el, 'display', 'none');
  }

  /* ------------------------------------------------------------------
   * 清理清扫：递归往下走，遇到「播放器祖先链」就保护并继续深入
   * （这样链内部的兄弟浮层，比如右侧节目单，也能被发现）
   * ------------------------------------------------------------------ */
  var sweepBudget = 0;

  function sweep(node, depth, chain) {
    if (!node || depth > 14 || sweepBudget <= 0) return;
    var kids = node.children;
    if (!kids) return;

    for (var i = 0; i < kids.length; i++) {
      var el = kids[i];
      if (sweepBudget-- <= 0) return;

      if (inChain(chain, el)) {
        /* 保护链，但继续往里钻 */
        sweep(el, depth + 1, chain);
        continue;
      }
      if (shouldHide(el)) {
        hideIt(el);
        continue;
      }
      if (depth < 9) sweep(el, depth + 1, chain);
    }
  }

  /* ------------------------------------------------------------------
   * 兜底：body / html 不允许滚动，背景压黑
   * ------------------------------------------------------------------ */
  function lockPage() {
    var h = document.documentElement, b = document.body;
    if (h) {
      force(h, 'overflow', 'hidden');
      force(h, 'background-color', '#000');
      force(h, 'width', '100%');
      force(h, 'height', '100%');
    }
    if (b) {
      force(b, 'overflow', 'hidden');
      force(b, 'background-color', '#000');
      force(b, 'margin', '0');
      force(b, 'padding', '0');
      force(b, 'width', '100%');
      force(b, 'height', '100%');
    }
  }

  /* ------------------------------------------------------------------
   * 提取「正在播出的栏目」
   * 思路：在播放器周围的若干层容器里找可见文字，排除时间戳、按钮词、
   *       以及频道名本身（频道名原生层已经知道，传进来做过滤）。
   * ------------------------------------------------------------------ */
  var NOISE = {
    '直播': 1, '回看': 1, '预约': 1, '全屏': 1, '退出全屏': 1,
    '高清': 1, '超清': 1, '蓝光': 1, '标清': 1, '流畅': 1,
    '登录': 1, '注册': 1, '下载': 1, '分享': 1, '收藏': 1, '更多': 1,
    '节目单': 1, '切换频道': 1, '画中画': 1, '静音': 1, '音量': 1,
    '央视频': 1, '电视': 1, '首页': 1, '推荐': 1, '赛事': 1,
    '影视': 1, '节目库': 1, '搜索': 1, '刷新': 1, '重试': 1
  };

  var TIME_RE = /^\d{1,2}:\d{2}/;
  var PURE_ASCII_RE = /^[\x00-\x7F]+$/;
  var CJK_RE = /[\u4e00-\u9fa5]/;

  function extractProgram(currentName) {
    var player = currentPlayer;
    if (!player) return '';

    /* 以播放器往上第 4 层作为搜索半径 */
    var root = player;
    for (var i = 0; i < 4 && root.parentElement; i++) root = root.parentElement;
    if (!root || !root.querySelectorAll) return '';

    var nodes = root.querySelectorAll('span,div,p,a,em,i,b,h1,h2,h3,strong');
    var best = '', bestScore = -1;
    var limit = Math.min(nodes.length, 1200);

    for (var n = 0; n < limit; n++) {
      var el = nodes[n];
      if (isHiddenByUs(el)) continue;
      if (el.children && el.children.length > 0) continue;

      var txt = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (!txt) continue;
      if (txt.length < 2 || txt.length > 42) continue;
      if (!CJK_RE.test(txt)) continue;
      if (TIME_RE.test(txt)) continue;
      if (PURE_ASCII_RE.test(txt)) continue;
      if (NOISE[txt]) continue;
      if (currentName && txt.indexOf(currentName) > -1) continue;
      if (/^(正在|已|共)\s*\d/.test(txt)) continue;

      var s = cs(el);
      if (!s || s.display === 'none' || s.visibility === 'hidden') continue;

      var r = el.getBoundingClientRect();
      if (r.width < 36 || r.height < 12) continue;
      if (r.top < -20 || r.top > vh()) continue;

      /* 打分：字号大、靠上、宽度适中 的更像节目标题 */
      var fs = parseFloat(s.fontSize) || 14;
      var score = fs * 10 - (r.top / vh()) * 40;
      if (r.width > vw() * 0.75) score -= 30;
      if (score > bestScore) { bestScore = score; best = txt; }
    }

    return best;
  }

  /* ------------------------------------------------------------------
   * 回报原生层
   * ------------------------------------------------------------------ */
  /* 记录页面里冒出来的 JS 错误 —— 真机排查时这是屏幕上唯一能看到的线索 */
  var lastError = '';
  window.addEventListener('error', function (e) {
    try {
      var m = (e && (e.message || (e.error && e.error.message))) || 'unknown';
      lastError = String(m).slice(0, 46);
    } catch (x) {}
  }, true);

  var lastSentProgram = '';

  function notify(type, payload) {
    try {
      if (window.HTVNative && window.HTVNative.onEvent) {
        window.HTVNative.onEvent(type, payload ? String(payload) : '');
      }
    } catch (e) {}
  }

  /*
   * ⚠️ 只发**非空**的节目名。
   *
   * 踩过的坑：这里原来还有个 else 分支，猜不到就每 15 秒发一次空串，
   * 想让原生层「清掉过期的名字」。结果页面被剪枝成只剩播放器之后，
   * extractProgram() 基本永远返回空 —— 于是变成「每换一个台，
   * 4 秒内必到一个空串」，原生层把已经查好的 EPG 名字全抹了。
   * 表现就是频道列表里「点一个台，消一个节目名」。
   *
   * 节目名的权威来源是 EPG 接口（原生层直接查，48 个台都有），
   * 这里只作为补充：有就报，没有就闭嘴。
   */
  function reportProgram() {
    var name = '';
    try { name = window.__HTV__.channelName || ''; } catch (e) {}

    var p = '';
    try { p = extractProgram(name); } catch (e) { p = ''; }

    if (p && p !== lastSentProgram) {
      lastSentProgram = p;
      notify('program', p);
    }
  }

  /* ------------------------------------------------------------------
   * 播放就绪检测：currentTime 连续两次在推进，就算画面真的出来了
   * ------------------------------------------------------------------ */
  var currentPlayer = null;
  var currentKind = '';
  var lastTime = -1;
  var progressHits = 0;
  var readyNotified = false;

  function checkReady() {
    if (!currentPlayer || currentKind !== 'video') return;
    var t = 0;
    try { t = currentPlayer.currentTime || 0; } catch (e) { t = 0; }

    if (t > 0.25 && t !== lastTime) {
      progressHits++;
      lastTime = t;
      if (progressHits >= 2 && !readyNotified) {
        readyNotified = true;
        notify('ready', String(Math.round(t * 10) / 10));
      }
    } else if (t === lastTime && t > 0) {
      /* 卡住了，不重置计数，避免反复触发 */
    }
  }

  /* ------------------------------------------------------------------
   * 主循环
   * ------------------------------------------------------------------ */
  function tick() {
    var found = findPlayer();
    if (!found) return;

    var el = found.el;

    /* 播放器元素换人了（SPA 换台后重建）→ 状态重置 */
    if (el !== currentPlayer) {
      currentPlayer = el;
      currentKind = found.kind;
      lastTime = -1;
      progressHits = 0;
      readyNotified = false;
      lastSentProgram = '';
    }

    var chain = buildChain(el);
    pinPlayer(el, chain);
    lockPage();

    sweepBudget = 3000;
    sweep(document.body, 0, chain);
  }

  /* ------------------------------------------------------------------
   * 换台：只能整页跳转
   *
   * 实测（桌面 Chrome 探针，两次独立验证）排除掉了两条"不刷新"的路：
   *   ① 改 location.hash           → 这站是 history 路由，hash 变了播放器纹丝不动
   *   ② pushState + 派发 popstate   → URL 变了，但 video 的 blob src 没变、
   *                                   currentTime 照常往前走，还是同一个流
   * 直播地址是带 token 二次请求拿到的，只有整页重走一遍才会真的换台。
   * 原生侧会用黑遮罩盖住整个刷新过程，所以观感上没有闪白。
   *
   * 注意地址必须是 query 形态：`/tv/home?pid=`，
   * 用 `/#/tv/home?pid=` 会路由匹配失败落到首页，页面里一个 video 都没有。
   * ------------------------------------------------------------------ */
  function hardLoad(pid) {
    if (!pid) return false;
    try {
      window.location.href = '/tv/home?pid=' + pid;
      return true;
    } catch (e) {
      return false;
    }
  }

  /* ------------------------------------------------------------------
   * 对外接口
   * ------------------------------------------------------------------ */
  window.__HTV__ = {
    v: VERSION,
    channelName: '',

    tick: tick,

    /* switchTo 这个名字保留做别名 —— 换台本质上就是整页跳转 */
    switchTo: function (pid) { return hardLoad(pid); },
    hardLoad: hardLoad,

    /* 原生换台前调用：重置就绪标记，好让黑遮罩能正确等画面 */
    beginSwitch: function (name) {
      window.__HTV__.channelName = name || '';
      lastTime = -1;
      progressHits = 0;
      readyNotified = false;
      lastSentProgram = '';
    },

    restore: function () {
      var list = document.querySelectorAll('[data-htv-hidden="1"]');
      for (var i = 0; i < list.length; i++) {
        list[i].removeAttribute('data-htv-hidden');
        list[i].style.removeProperty('display');
      }
      return list.length;
    },

    /*
     * 超时兜底用：画面是不是其实早就出来了？
     *
     * 真机上踩过 —— 视频已经在播，ready 信号却丢在路上，
     * 原生层干等 20 秒后还要去切备用源，等于把好好的直播打断。
     * 所以超时前先问一句这个。
     */
    isPlaying: function () {
      try {
        var v = currentPlayer || document.querySelector('video');
        if (!v) return false;
        return (v.readyState || 0) >= 3 && !v.paused;
      } catch (e) { return false; }
    },

    diag: function () {
      return JSON.stringify({
        player: currentKind,
        hasPlayer: !!currentPlayer,
        program: lastSentProgram,
        hidden: document.querySelectorAll('[data-htv-hidden="1"]').length,
        hash: window.location.hash || ''
      });
    },

    /*
     * 给黑遮罩用的诊断串。
     * 主源超时时原生层会把它念出来显示在屏幕上 —— 电视上没有日志可看，
     * 用户截一张图就能知道到底卡在哪一环。
     */
    diagnose: function () {
      var out = [];
      try {
        out.push('页 ' + (window.location.pathname || '?') + (window.location.search || ''));
      } catch (e) { out.push('页 ?'); }

      try {
        var n = document.querySelectorAll('video').length;
        var v = currentPlayer || document.querySelector('video');
        var d = '无';
        if (v) {
          d = '就绪' + (v.readyState || 0) + (v.paused ? '/暂停' : '/播放') +
              ' ' + (v.videoWidth || 0) + 'x' + (v.videoHeight || 0);
        }
        out.push('video ' + n + '个 ' + d);
      } catch (e) { out.push('video 读取失败'); }

      try {
        out.push('脚本' + (window.__HTV__ ? '在' : '丢') +
                 ' 清' + document.querySelectorAll('[data-htv-hidden="1"]').length +
                 ' 错' + (lastError || '无'));
      } catch (e) {}

      return out.join('\n');
    }
  };

  /* ------------------------------------------------------------------
   * 启动
   * ------------------------------------------------------------------ */
  function boot() {
    tick();
    setInterval(tick, 800);          // 浮层是动态弹的，得持续清
    setInterval(checkReady, 500);
    setInterval(reportProgram, 4000);

    /* 页面尺寸变了（电视上少见，但盒子切分辨率时会）→ 立刻重整 */
    window.addEventListener('resize', function () { tick(); }, false);

    /* 万一页面内部自己走了路由（用户误点到列表），立刻重整一次 */
    window.addEventListener('popstate', function () {
      setTimeout(tick, 200);
      setTimeout(tick, 900);
    }, false);
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    boot();
  } else {
    document.addEventListener('DOMContentLoaded', boot, false);
  }
})();
