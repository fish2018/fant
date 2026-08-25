import '../assets/styles.css';

import { Window } from './window';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

const container = document.getElementById('root');
const root = createRoot(container as HTMLElement);

root.render(
  <StrictMode>
    <Window />
  </StrictMode>
);
