import type { FC, ReactNode } from 'react';

// Decorative stroke icons (the visible/sr-only label carries the meaning). `directional` icons imply a direction
// and are mirrored in RTL by CSS keyed on the data attribute.
const Svg: FC<{ children: ReactNode; directional?: boolean }> = ({ children, directional }) => (
  <svg
    className="icon"
    viewBox="0 0 24 24"
    width="24"
    height="24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
    focusable="false"
    {...(directional ? { 'data-icon-directional': '' } : {})}
  >
    {children}
  </svg>
);

export const HomeIcon: FC = () => (
  <Svg>
    <path d="M3 11l9-8 9 8" />
    <path d="M5 10v10h14V10" />
  </Svg>
);
export const ListIcon: FC = () => (
  <Svg>
    <path d="M8 6h13M8 12h13M8 18h13" />
    <path d="M3 6h.01M3 12h.01M3 18h.01" />
  </Svg>
);
export const PlusIcon: FC = () => (
  <Svg>
    <path d="M12 5v14M5 12h14" />
  </Svg>
);
export const ChartIcon: FC = () => (
  <Svg>
    <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />
  </Svg>
);
export const MoreIcon: FC = () => (
  <Svg>
    <path d="M5 12h.01M12 12h.01M19 12h.01" strokeWidth="3" />
  </Svg>
);
/** Arrow leaving a door: points toward the inline end, so it mirrors in RTL. */
export const SignOutIcon: FC = () => (
  <Svg directional>
    <path d="M9 21H5V3h4" />
    <path d="M16 17l5-5-5-5M21 12H9" />
  </Svg>
);
