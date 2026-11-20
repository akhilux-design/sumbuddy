// Read-only Figma serializer used to export the design spec.
// Paste this whole file at the top of a `use_figma` script, then end the script with e.g.:
//   const node = await figma.getNodeByIdAsync('42:1084');
//   return await dump(node);
// It never changes the document.
for (const p of figma.root.children) await p.loadAsync();
const __vcache = {};
const vname = async (id) => {
  if (!(id in __vcache)) { const v = await figma.variables.getVariableByIdAsync(id); __vcache[id] = v ? v.name : id; }
  return __vcache[id];
};
const hex = (c) => '#' + [c.r, c.g, c.b].map((v) => Math.round(v * 255).toString(16).padStart(2, '0')).join('').toUpperCase();
const paint = async (p) => {
  if (!p || p.visible === false) return null;
  if (p.type === 'SOLID') {
    const bv = p.boundVariables && p.boundVariables.color;
    const base = bv ? await vname(bv.id) : hex(p.color);
    return base + (p.opacity != null && p.opacity < 1 ? '@' + Math.round(p.opacity * 100) : '');
  }
  if (p.type.indexOf('GRADIENT') === 0) return p.type + '(' + p.gradientStops.map((s) => hex(s.color) + (s.color.a < 1 ? '@' + Math.round(s.color.a * 100) : '')).join(',') + ')';
  return p.type;
};
const paints = async (ps) => {
  if (!ps || ps === figma.mixed) return undefined;
  const out = [];
  for (const p of ps) { const s = await paint(p); if (s) out.push(s); }
  return out.length ? out : undefined;
};
const bvar = async (n, f) => { const b = n.boundVariables && n.boundVariables[f]; return b && b.id ? await vname(b.id) : undefined; };
const instInfo = async (n) => {
  const o = {};
  const mc = await n.getMainComponentAsync();
  if (mc) {
    if (mc.parent && mc.parent.type === 'COMPONENT_SET') { o.comp = mc.parent.name; o.variant = mc.name; } else o.comp = mc.name;
  }
  const props = {};
  for (const k of Object.keys(n.componentProperties || {})) {
    const v = n.componentProperties[k];
    if (v.type === 'VARIANT') continue;
    let val = v.value;
    if (v.type === 'INSTANCE_SWAP') {
      const c = await figma.getNodeByIdAsync(val);
      if (c) val = (c.parent && c.parent.type === 'COMPONENT_SET' ? c.parent.name + ' / ' : '') + c.name;
    }
    props[k.split('#')[0]] = val;
  }
  if (Object.keys(props).length) o.props = props;
  return o;
};
const textInfo = async (n, o) => {
  o.text = n.characters;
  if (n.textStyleId && n.textStyleId !== figma.mixed) { const st = await figma.getStyleByIdAsync(n.textStyleId); if (st) o.style = st.name; }
  else if (n.fontSize !== figma.mixed && n.fontName !== figma.mixed) o.font = n.fontName.family + ' ' + n.fontName.style + ' ' + n.fontSize;
  if (n.textAlignHorizontal !== 'LEFT') o.align = n.textAlignHorizontal;
  if (n.textCase && n.textCase !== figma.mixed && n.textCase !== 'ORIGINAL') o.textCase = n.textCase;
  if (n.fills === figma.mixed) {
    const segs = n.getStyledTextSegments(['fills']);
    o.segs = [];
    for (const s of segs) o.segs.push({ text: s.characters, fill: await paints(s.fills) });
  }
};
const dump = async (n) => {
  if (n.visible === false) return null;
  const o = { t: n.type, n: n.name, x: Math.round(n.x), y: Math.round(n.y), w: Math.round(n.width * 10) / 10, h: Math.round(n.height * 10) / 10 };
  if ('opacity' in n && n.opacity < 1) o.op = Math.round(n.opacity * 100) / 100;
  if ('rotation' in n && Math.abs(n.rotation) > 0.01) o.rot = Math.round(n.rotation * 10) / 10;
  if ('layoutMode' in n && n.layoutMode !== 'NONE') {
    o.lay = n.layoutMode === 'HORIZONTAL' ? 'row' : 'col';
    o.gap = n.itemSpacing; o.pad = [n.paddingTop, n.paddingRight, n.paddingBottom, n.paddingLeft];
    o.main = n.primaryAxisAlignItems; o.cross = n.counterAxisAlignItems;
    if (n.layoutWrap === 'WRAP') { o.wrap = true; o.crossGap = n.counterAxisSpacing; }
    const g = await bvar(n, 'itemSpacing'); if (g) o.gapVar = g;
  }
  if ('layoutSizingHorizontal' in n) o.size = [n.layoutSizingHorizontal, n.layoutSizingVertical];
  if ('layoutPositioning' in n && n.layoutPositioning === 'ABSOLUTE') o.abs = true;
  if ('cornerRadius' in n) {
    if (n.cornerRadius !== figma.mixed) { if (n.cornerRadius > 0) o.r = n.cornerRadius; }
    else o.r = [n.topLeftRadius, n.topRightRadius, n.bottomRightRadius, n.bottomLeftRadius];
  }
  if ('fills' in n) { const f = await paints(n.fills); if (f) o.fill = f; }
  if ('strokes' in n && n.strokes && n.strokes.length) {
    const s = await paints(n.strokes);
    if (s) {
      o.stroke = s;
      o.sw = n.strokeWeight === figma.mixed ? [n.strokeTopWeight, n.strokeRightWeight, n.strokeBottomWeight, n.strokeLeftWeight] : n.strokeWeight;
      if (n.dashPattern && n.dashPattern.length) o.dash = n.dashPattern;
    }
  }
  if ('effects' in n && n.effects && n.effects.length) {
    // e.g. DROP_SHADOW(x,y,blur,colour): a 0-blur shadow straight down is the "chunky edge" under buttons and cards.
    const fx = [];
    for (const e of n.effects) {
      if (e.visible === false) continue;
      let col = '';
      if (e.color) { const bv = e.boundVariables && e.boundVariables.color; col = ',' + (bv && bv.id ? await vname(bv.id) : hex(e.color) + '@' + Math.round(e.color.a * 100)); }
      fx.push(e.type + (e.offset ? '(' + e.offset.x + ',' + e.offset.y + ',' + e.radius + col + ')' : '(' + e.radius + ')'));
    }
    if (fx.length) o.fx = fx;
  }
  if ('clipsContent' in n && n.clipsContent) o.clip = true;
  if (n.type === 'TEXT') await textInfo(n, o);
  if (n.type === 'INSTANCE') {
    Object.assign(o, await instInfo(n));
    // Do not repeat the component's internals: record only what this instance changes or contains.
    const texts = [], nested = [], hidden = [];
    for (const d of n.findAll(() => true)) {
      let anc = d.parent, inIcon = false, invisible = d.visible === false;
      while (anc && anc.id !== n.id) { if (anc.visible === false) invisible = true; if (anc.type === 'INSTANCE') { const m = await anc.getMainComponentAsync(); if (m && m.parent && m.parent.name && m.parent.name.indexOf('Icon/') === 0) inIcon = true; } anc = anc.parent; }
      if (inIcon) continue;
      if (invisible) { if (d.visible === false) hidden.push(d.name); continue; }
      if (d.type === 'TEXT') { const t = { n: d.name }; await textInfo(d, t); const f = await paints(d.fills); if (f) t.fill = f; texts.push(t); }
      else if (d.type === 'INSTANCE') nested.push(Object.assign({ n: d.name, w: Math.round(d.width), h: Math.round(d.height) }, await instInfo(d)));
    }
    if (texts.length) o.texts = texts;
    if (nested.length) o.nested = nested;
    if (hidden.length) o.hidden = hidden;
    return o;
  }
  if ('children' in n && n.type !== 'BOOLEAN_OPERATION') {
    const kids = [];
    for (const c of n.children) { const d = await dump(c); if (d) kids.push(d); }
    if (kids.length) o.c = kids;
  }
  return o;
};
// Full structural dump that also descends into instances (use for component definitions).
const dumpDeep = async (n) => {
  if (n.visible === false) return null;
  const o = await dump(n);
  if (!o) return null;
  if ('children' in n && n.type !== 'BOOLEAN_OPERATION') {
    const kids = [];
    for (const c of n.children) { const d = await dumpDeep(c); if (d) kids.push(d); }
    if (kids.length) o.c = kids; else delete o.c;
  }
  return o;
};
