// Fills the privacy page's "On this page" list from the policy's section headings (PRIVACY.md, rendered by Jekyll with
// an id on each heading). Without this script the list stays hidden and the policy reads top to bottom as before.
(function () {
  var policy = document.querySelector('.policy');
  var toc = policy && policy.querySelector('.toc');
  if (!toc) return;
  var list = toc.querySelector('ol');
  var headings = policy.querySelectorAll(':scope > h2[id]');
  if (!headings.length) return;
  headings.forEach(function (h) {
    var item = document.createElement('li');
    var link = document.createElement('a');
    link.href = '#' + h.id;
    link.textContent = h.textContent;
    item.appendChild(link);
    list.appendChild(item);
  });
  // Put the list where it is shown (after the "In short" box), so reading and Tab order follow the page.
  var lead = policy.querySelector(':scope > h1 + p + p + p');
  if (lead) lead.after(toc);
  toc.hidden = false;
})();
