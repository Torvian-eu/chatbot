(function () {
  var revealNodes = document.querySelectorAll('.reveal');

  if ('IntersectionObserver' in window) {
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.14 });

    revealNodes.forEach(function (node) {
      observer.observe(node);
    });
  } else {
    revealNodes.forEach(function (node) {
      node.classList.add('is-visible');
    });
  }

  // Small utility for demo page copy buttons.
  document.querySelectorAll('[data-copy-target]').forEach(function (button) {
    button.addEventListener('click', function () {
      var targetId = button.getAttribute('data-copy-target');
      var source = targetId ? document.getElementById(targetId) : null;
      if (!source) {
        return;
      }

      var value = source.textContent ? source.textContent.trim() : '';
      if (!value) {
        return;
      }

      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(value).then(function () {
          var oldLabel = button.textContent;
          button.textContent = 'Copied';
          setTimeout(function () {
            button.textContent = oldLabel;
          }, 1200);
        }).catch(function () {
          // Ignore clipboard errors silently to keep the page stable.
        });
      }
    });
  });
})();

