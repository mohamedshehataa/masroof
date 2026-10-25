// REQ-OPS-053: compiled by src/api/generated.test.ts against the generated barrel (src/api/generated/index.ts).
import type { Money } from '../../../src/api/generated';

declare const m: Money;

export const asNumber: number = m.amountMinor;
// @ts-expect-error amountMinor must be exactly number, never string
export const notString: string = m.amountMinor;
// @ts-expect-error amountMinor must be exactly number, never bigint
export const notBigint: bigint = m.amountMinor;
export const literal: Money = { amountMinor: 1250, currency: 'EGP' };
