// STUB (test-designer scaffold): implementer adds fetch + runtime schema validation (REQ-OPS-021).
export interface Health {
  status: 'UP' | 'DOWN';
  version: string;
  gitSha: string;
  buildTime: string;
  time: string;
  checks?: Record<string, 'UP' | 'DOWN'>;
}

export function getHealth(): Promise<Health> {
  return Promise.resolve({} as Health);
}
