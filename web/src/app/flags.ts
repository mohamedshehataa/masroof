// Login stays behind a flag until the backend ships auth (VITE_AUTH_ENABLED=true).
export const AUTH_ENABLED = String(import.meta.env.VITE_AUTH_ENABLED) === 'true';
