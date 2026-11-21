async function renderDiagram(source, theme) {
  try {
    const defaults = mermaid.mermaidAPI.defaultConfig;
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme,
      secure: [...defaults.secure, 'theme', 'themeCSS', 'dompurifyConfig'],
      suppressErrorRendering: true,
    });
    const container = document.getElementById('diagram');
    const { svg } = await mermaid.render('orcaDiagram', source);
    container.innerHTML = svg;
    const diagram = container.querySelector('svg');
    const reportHeight = () => DiagramSize.report(Math.ceil(diagram.getBoundingClientRect().height));
    new ResizeObserver(reportHeight).observe(diagram);
    reportHeight();
  } catch {
    DiagramSize.report(-1);
  }
}
