import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { applyTheme, parseTheme, THEME_KEY } from './shell/settings';
import { readStored } from './shell/storage';

// Apply the stored theme before the first paint so an explicit Dark/Light choice never flashes the other palette.
applyTheme(parseTheme(readStored(THEME_KEY)));

const root = document.getElementById('root');
if (root) {
  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}
