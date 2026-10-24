declare const m: { amountMinor: number };
declare const other: { amountMinor: number };
export const total = m.amountMinor * 2;
export const sum = m.amountMinor + other.amountMinor;
export const half = Math.floor(m.amountMinor / 2);
export let acc = 0;
acc += m.amountMinor;
export const plain: number = m.amountMinor;
