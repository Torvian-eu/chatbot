# chatbot.torvian.eu static site

This directory contains a lightweight static website for `chatbot.torvian.eu`.

## Files
- `index.html` - landing page
- `demo.html` - demo onboarding page with login and setup instructions
- `styles.css` - shared visual design and responsive layout
- `script.js` - small reveal and copy-to-clipboard interactions

## Local preview (PowerShell)
```bash
Push-Location "C:\Users\Rogier\Documents\MyData\MyProjects\Chatbot\chatbot\website"
python -m http.server 5500
Pop-Location
```

Open:
- `http://localhost:5500/index.html`
- `http://localhost:5500/demo.html`

