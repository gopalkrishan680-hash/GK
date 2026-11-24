// Extracts product cards from a store's search-results page.
// Called as: __phSearch({store: 'amazon', linkRe: '...'}) -> JSON string
window.__phSearch = function (cfg) {
  var linkRe = new RegExp(cfg.linkRe, 'i');

  function txt(e) { return ((e && (e.innerText || e.textContent)) || '').replace(/\s+/g, ' ').trim(); }
  function rawTxt(e) { return ((e && e.textContent) || '').replace(/\s+/g, ' ').trim(); }
  function num(s) {
    if (s == null) return null;
    var m = String(s).replace(/,/g, '').match(/(?:₹|Rs\.?|INR)\s*([0-9]+(?:\.[0-9]{1,2})?)/i);
    return m ? parseFloat(m[1]) : null;
  }
  function plainNum(s) {
    if (s == null) return null;
    var m = String(s).replace(/,/g, '').match(/([0-9]+(?:\.[0-9]{1,2})?)/);
    return m ? parseFloat(m[1]) : null;
  }
  function abs(h) { try { return new URL(h, location.href).href; } catch (e) { return null; } }
  function imgOf(el) {
    if (!el) return null;
    var imgs = el.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var im = imgs[i];
      var s = im.currentSrc || im.getAttribute('src') || im.getAttribute('data-src') || '';
      if ((!s || s.indexOf('data:') === 0) && im.getAttribute('srcset')) {
        s = im.getAttribute('srcset').split(',')[0].trim().split(' ')[0];
      }
      if (s && s.indexOf('data:') !== 0 && !/sprite|icon|logo|badge|assured/i.test(s)) return abs(s);
    }
    return null;
  }
  function keyOf(url) {
    var m = url.match(linkRe);
    if (m && m[1]) return m[1];
    try { return new URL(url).pathname; } catch (e) { return url; }
  }
  function isStruck(e) {
    for (var n = e, d = 0; n && d < 3; n = n.parentElement, d++) {
      var tag = n.tagName;
      if (tag === 'DEL' || tag === 'S' || tag === 'STRIKE') return true;
      try {
        var cs = getComputedStyle(n);
        if ((cs.textDecorationLine || cs.textDecoration || '').indexOf('line-through') >= 0) return true;
      } catch (x) {}
    }
    return false;
  }
  function ratingOf(card) {
    var t = txt(card);
    var m = t.match(/([1-5](?:\.[0-9])?)\s*(?:out of 5|★|⭐)/);
    if (m) return parseFloat(m[1]);
    var els = card.querySelectorAll('div,span');
    for (var i = 0; i < els.length; i++) {
      if (els[i].childElementCount <= 1) {
        var s = rawTxt(els[i]);
        if (/^[1-5]\.[0-9]$/.test(s)) return parseFloat(s);
      }
    }
    return null;
  }
  // deepest elements whose text is a short "₹1,299" style price
  function prices(card) {
    var res = [];
    var els = card.querySelectorAll('*');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      var t = rawTxt(e);
      if (t.length > 28 || !/(₹|Rs\.?)\s*[0-9]/.test(t)) continue;
      if (/off|save|emi|coupon|cashback|bank|extra|delivery|per\s/i.test(t)) continue;
      var childHas = false;
      for (var c = 0; c < e.children.length; c++) {
        var ct = rawTxt(e.children[c]);
        if (/(₹|Rs\.?)\s*[0-9]/.test(ct) && ct.length <= 28) { childHas = true; break; }
      }
      if (childHas) continue;
      var v = num(t);
      if (v != null && v > 0) res.push({ v: v, struck: isStruck(e) });
    }
    return res;
  }
  function titleOf(card, a) {
    var cands = [];
    var titled = card.querySelectorAll('[title]');
    for (var i = 0; i < titled.length; i++) cands.push(titled[i].getAttribute('title'));
    var im = card.querySelector('img[alt]');
    if (im) cands.push(im.getAttribute('alt'));
    (a.innerText || '').split('\n').forEach(function (l) { cands.push(l); });
    var best = '';
    cands.forEach(function (c) {
      c = (c || '').replace(/\s+/g, ' ').trim();
      if (c.length < 6 || /₹|% off|^add to|^sponsored|^[0-9.,\s]+$|ratings?|reviews?/i.test(c)) return;
      if (c.length > best.length) best = c;
    });
    return best;
  }

  var items = [], seen = {};
  function push(o) {
    if (!o.url || !o.title || !o.price || o.price <= 0) return;
    var k = keyOf(o.url);
    if (seen[k]) return;
    seen[k] = 1;
    o.key = k;
    if (o.mrp && o.mrp <= o.price) o.mrp = null;
    items.push(o);
  }

  // ---------- store specific fast paths ----------
  try {
    if (cfg.store === 'myntra' && window.__myx && window.__myx.searchData) {
      var ps = window.__myx.searchData.results.products || [];
      ps.forEach(function (p) {
        push({
          title: ((p.brand || '') + ' ' + (p.additionalInfo || p.productName || '')).trim(),
          url: abs('/' + p.landingPageUrl),
          price: p.price, mrp: p.mrp,
          image: p.searchImage || null,
          rating: p.rating ? Math.round(p.rating * 10) / 10 : null
        });
      });
    }
  } catch (e) {}

  try {
    if (cfg.store === 'amazon') {
      document.querySelectorAll('[data-component-type="s-search-result"], [data-asin][data-component-type]').forEach(function (c) {
        var asin = c.getAttribute('data-asin');
        if (!asin) return;
        var h2s = c.querySelectorAll('h2');
        var title = '';
        h2s.forEach(function (h) {
          var t = rawTxt(h);
          if (!title) title = t;
          else if (t.indexOf(title) !== 0) title = title + ' ' + t;
          else title = t;
        });
        var pe = c.querySelector('.a-price:not(.a-text-price) .a-offscreen');
        var me = c.querySelector('.a-price.a-text-price .a-offscreen');
        var re = c.querySelector('[aria-label*="out of 5"]');
        var im = c.querySelector('img.s-image');
        push({
          title: title,
          url: 'https://www.amazon.in/dp/' + asin,
          price: pe ? num(rawTxt(pe)) : null,
          mrp: me ? num(rawTxt(me)) : null,
          image: im ? (im.getAttribute('src')) : null,
          rating: re ? plainNum(re.getAttribute('aria-label')) : null,
          sponsored: /sponsored/i.test(rawTxt(c).slice(0, 200))
        });
      });
    }
  } catch (e) {}

  // ---------- generic heuristic (works for most stores) ----------
  if (items.length === 0) {
    var anchors = Array.prototype.slice.call(document.querySelectorAll('a[href]')).filter(function (a) {
      return linkRe.test(a.href);
    });
    var firstByKey = {}, order = [];
    anchors.forEach(function (a) {
      var k = keyOf(a.href);
      if (!firstByKey[k]) { firstByKey[k] = a; order.push(k); }
    });
    order.slice(0, 60).forEach(function (k) {
      var a = firstByKey[k];
      var card = a, el = a;
      for (var d = 0; d < 10 && el.parentElement && el.parentElement !== document.body; d++) {
        var p = el.parentElement;
        var links = p.querySelectorAll('a[href]');
        var other = false;
        for (var i = 0; i < links.length; i++) {
          if (linkRe.test(links[i].href) && keyOf(links[i].href) !== k) { other = true; break; }
        }
        if (other) break;
        el = p;
        card = p;
      }
      var ps = prices(card);
      var sale = null, mrp = null;
      ps.forEach(function (x) {
        if (x.struck) { if (mrp == null || x.v > mrp) mrp = x.v; }
        else if (sale == null) sale = x.v;
      });
      if (sale == null && ps.length) sale = ps[0].v;
      push({
        title: titleOf(card, a),
        url: a.href,
        price: sale, mrp: mrp,
        image: imgOf(card),
        rating: ratingOf(card)
      });
    });
  }

  var body = (document.body ? document.body.innerText : '').slice(0, 3000);
  var blocked = /captcha|robot check|are you a human|access denied|unusual traffic|verify you are human/i.test(document.title + ' ' + body)
    || !!document.querySelector('form[action*="validateCaptcha"]');

  return JSON.stringify({ items: items, blocked: blocked && items.length === 0, ready: document.readyState, url: location.href });
};
