let latestRevision = 0;
let rendering = Promise.resolve();
let observer;

function renderDiagram(source, theme, revision) {
  latestRevision = revision;
  observer?.disconnect();

  // Mermaid configuration is shared by all renders in this WebView.
  rendering = rendering.then(async () => {
    if (revision !== latestRevision) return;
    try {
      const defaults = mermaid.mermaidAPI.defaultConfig;
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        theme,
        secure: [...defaults.secure, 'theme', 'themeCSS', 'dompurifyConfig'],
        suppressErrorRendering: true,
      });
      const staging = document.getElementById('staging');
      const { svg } = await mermaid.render('orcaDiagram' + revision, source, staging);
      if (revision !== latestRevision) return;

      const container = document.getElementById('diagram');
      container.innerHTML = svg;
      const diagram = container.querySelector('svg');
      const reportHeight = () => DiagramSize.report(revision, Math.ceil(diagram.getBoundingClientRect().height));
      observer = new ResizeObserver(reportHeight);
      observer.observe(diagram);
      reportHeight();
    } catch {
      if (revision === latestRevision) DiagramSize.report(revision, -1);
    }
  });
}
