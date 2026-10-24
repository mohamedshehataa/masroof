import type { FC } from 'react';

// STUB (test-designer scaffold): presentational version line, props already validated (REQ-OPS-021).
export interface VersionLineProps {
  version: string;
  gitSha: string;
}

export const VersionLine: FC<VersionLineProps> = () => null;
