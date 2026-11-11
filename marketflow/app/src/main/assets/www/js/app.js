/* App shell: tabs, overlay page stack, banner, lifecycle hooks called by the Android activity. */
(function (root) {
  'use strict';
  var MF = root.MF, U = MF.ui, doc = root.document;
  var TABS = [
    { id: 'market', label: 'Market', icon: 'market' }, { id: 'flow', label: 'Flow', icon: 'flow' }, { id: 'options', label: 'Options', icon: 'options' },
    { id: 'backtest', label: 'Backtest', icon: 'test' }, { id: 'more', label: 'More', icon: 'more' }
  ];
  var current = null, currentId = null, stack = [], pollers = [];

  /* Repeats fn while the app is in the foreground. Returns a stop function. */
  U.poll = function (fn, ms) {
    var p = { fn: fn, ms: ms, id: setInterval(function () { if (!doc.hidden) fn(); }, ms) };
    pollers.push(p);
    return function () { clearInterval(p.id); var i = pollers.indexOf(p); if (i >= 0) pollers.splice(i, 1); };
  };

  var App = MF.app = {
    show: function (id, args) {
      while (stack.length) App.pop(true);
      if (current && current.destroy) current.destroy();
      currentId = id;
      var view = U.$('#view'); view.innerHTML = ''; view.scrollTop = 0;
      U.$$('#tabs button').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-tab') === id); });
      current = MF.views[id](view, args || {}) || {};
    },
    /* Opens a full-screen page above the tabs. viewFn(pageEl, args, titleEl, actionsEl) */
    push: function (viewFn, args, title) {
      var el = U.el('<div class="overlay"><div class="topbar"><button class="iconbtn" data-back>' + U.icon('back') + '</button><div class="grow"><h1 class="ellipsis" data-title></h1><small class="ellipsis" data-sub style="display:block"></small></div><div class="row" data-actions></div></div><div class="page"></div></div>');
      U.$('[data-title]', el).textContent = title || '';
      U.$('[data-back]', el).onclick = function () { App.pop(); };
      U.$('#app').appendChild(el);
      var inst = viewFn(U.$('.page', el), args || {}, { title: U.$('[data-title]', el), sub: U.$('[data-sub]', el), actions: U.$('[data-actions]', el) }) || {};
      stack.push({ el: el, inst: inst });
    },
    pop: function (silent) {
      var top = stack.pop(); if (!top) return false;
      if (top.inst.destroy) top.inst.destroy();
      top.el.remove();
      if (!silent && !stack.length && current && current.onShow) current.onShow();
      return true;
    },
    back: function () {
      if (U.closeTopSheet()) return true;
      if (App.pop()) return true;
      if (currentId !== 'market') { App.show('market'); return true; }
      return false;
    },
    banner: function () {
      var b = U.$('#banner');
      if (MF.data.isDemo()) { b.innerHTML = '<b>DEMO MODE</b> — prices are simulated, not real. <a data-go-settings>Connect Groww</a>'; b.classList.remove('hidden'); }
      else b.classList.add('hidden');
    },
    openDetail: function (sym) { App.push(MF.views.detail, { sym: sym }, U.symLabel(sym)); },
    currentTab: function () { return currentId; }
  };

  root.MFBack = function () { return App.back(); };
  root.MFResume = function () { if (MF.alerts) MF.alerts.onResume(); pollers.forEach(function (p) { p.fn(); }); };
  root.MFPause = function () { /* timers are skipped while hidden */ };

  function start() {
    var nav = U.$('#tabs');
    nav.innerHTML = TABS.map(function (t) { return '<button data-tab="' + t.id + '">' + U.icon(t.icon) + '<span>' + t.label + '</span></button>'; }).join('');
    U.on(nav, 'click', 'button', function (e, b) { App.show(b.getAttribute('data-tab')); });
    U.on(U.$('#banner'), 'click', '[data-go-settings]', function () { App.show('more', { open: 'settings' }); });
    App.banner();
    if (MF.alerts) MF.alerts.start();
    App.show('market');
    if (!MF.store.get('disclaimerSeen', false)) MF.views.disclaimer(true);
  }
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', start); else start();
})(window);
