// Extracts title / price / MRP / image from a single product page.
window.__phProduct = function () {
  function rawTxt(e) { return ((e && e.textContent) || '').replace(/\s+/g, ' ').trim(); }
  function num(s) {
    if (s == null) return null;
    var str = String(s).replace(/,/g, '');
    var m = str.match(/(?:₹|Rs\.?|INR)\s*([0-9]+(?:\.[0-9]{1,2})?)/i) || str.match(/^\s*([0-9]+(?:\.[0-9]{1,2})?)\s*$/);
    return m ? parseFloat(m[1]) : null;
  }
  function meta(sel) { var m = document.querySelector(sel); return m ? m.getAttribute('content') : null; }
  function isStruck(e) {
    for (var n = e, d = 0; n && d < 3; n = n.parentElement, d++) {
      if (/^(DEL|S|STRIKE)$/.test(n.tagName)) return true;
      try {
        var cs = getComputedStyle(n);
        if ((cs.textDecorationLine || cs.textDecoration || '').indexOf('line-through') >= 0) return true;
      } catch (x) {}
    }
    return false;
  }

  var r = { title: null, price: null, mrp: null, image: null, inStock: true, url: location.href };
  var host = location.hostname;

  // 1. Store specific
  try {
    if (/amazon\./.test(host)) {
      r.title = rawTxt(document.getElementById('productTitle')) || null;
      var sels = ['#corePriceDisplay_desktop_feature_div .priceToPay .a-offscreen',
        '#corePrice_feature_div .a-price .a-offscreen', '.apexPriceToPay .a-offscreen',
        '#priceblock_dealprice', '#priceblock_ourprice', '#tp_price_block_total_price_ww .a-offscreen',
        '.priceToPay .a-offscreen'];
      for (var i = 0; i < sels.length && !r.price; i++) r.price = num(rawTxt(document.querySelector(sels[i])));
      r.mrp = num(rawTxt(document.querySelector('.basisPrice .a-offscreen, .a-price.a-text-price[data-a-strike] .a-offscreen')));
      var li = document.getElementById('landingImage') || document.getElementById('imgBlkFront');
      if (li) r.image = li.getAttribute('data-old-hires') || li.getAttribute('src');
      var av = rawTxt(document.getElementById('availability'));
      if (/currently unavailable|out of stock/i.test(av)) r.inStock = false;
    }
    if (/myntra\./.test(host) && window.__myx && window.__myx.pdpData) {
      var p = window.__myx.pdpData;
      r.title = ((p.brand && p.brand.name ? p.brand.name + ' ' : '') + (p.name || '')).trim();
      if (p.price) { r.price = p.price.discounted || p.price.mrp; r.mrp = p.price.mrp; }
      try { r.image = p.media.albums[0].images[0].imageURL; } catch (e) {}
    }
  } catch (e) {}

  // 2. JSON-LD
  try {
    document.querySelectorAll('script[type="application/ld+json"]').forEach(function (s) {
      var data;
      try { data = JSON.parse(s.textContent); } catch (e) { return; }
      var list = Array.isArray(data) ? data : (data['@graph'] || [data]);
      list.forEach(function (d) {
        if (!d || !/Product/i.test(String(d['@type']))) return;
        if (!r.title && d.name) r.title = d.name;
        if (!r.image && d.image) r.image = Array.isArray(d.image) ? d.image[0] : (d.image.url || d.image);
        var o = d.offers;
        if (Array.isArray(o)) o = o[0];
        if (o && !r.price) {
          var pr = o.price || o.lowPrice || (o.priceSpecification && o.priceSpecification.price);
          if (pr) r.price = parseFloat(String(pr).replace(/,/g, ''));
          if (o.availability && /OutOfStock|SoldOut/i.test(o.availability)) r.inStock = false;
        }
      });
    });
  } catch (e) {}

  // 3. Meta tags
  if (!r.price) r.price = num(meta('meta[property="product:price:amount"]') || meta('meta[property="og:price:amount"]') || meta('meta[itemprop="price"]'));
  if (!r.title) r.title = meta('meta[property="og:title"]') || rawTxt(document.querySelector('h1')) || document.title;
  if (!r.image) r.image = meta('meta[property="og:image"]');

  // 4. Visual heuristic: biggest non-struck ₹ price near the top of the page
  if (!r.price || !r.mrp) {
    var best = null, bestSize = 0, struck = [];
    var els = document.querySelectorAll('body *');
    for (var j = 0; j < els.length; j++) {
      var e = els[j];
      var t = rawTxt(e);
      if (t.length > 20 || !/(₹|Rs\.?)\s*[0-9]/.test(t)) continue;
      if (/off|save|emi|coupon|bank/i.test(t)) continue;
      var childHas = false;
      for (var c = 0; c < e.children.length; c++) if (/(₹|Rs\.?)\s*[0-9]/.test(rawTxt(e.children[c]))) { childHas = true; break; }
      if (childHas) continue;
      var rect = e.getBoundingClientRect();
      if (rect.width === 0 || rect.top > 1600) continue;
      var v = num(t);
      if (!v) continue;
      if (isStruck(e)) { struck.push({ v: v, top: rect.top }); continue; }
      var fs = parseFloat(getComputedStyle(e).fontSize) || 0;
      if (fs > bestSize) { bestSize = fs; best = { v: v, top: rect.top }; }
    }
    if (!r.price && best) r.price = best.v;
    if (!r.mrp && best && struck.length) {
      struck.sort(function (a, b) { return Math.abs(a.top - best.top) - Math.abs(b.top - best.top); });
      r.mrp = struck[0].v;
    }
  }
  if (r.mrp && r.price && r.mrp <= r.price) r.mrp = null;
  if (/out of stock|sold out|currently unavailable/i.test((document.body.innerText || '').slice(0, 4000)) && !r.price) r.inStock = false;

  var body = (document.body ? document.body.innerText : '').slice(0, 3000);
  r.blocked = !r.price && /captcha|robot check|are you a human|access denied|verify you are human/i.test(document.title + ' ' + body);
  return JSON.stringify(r);
};
