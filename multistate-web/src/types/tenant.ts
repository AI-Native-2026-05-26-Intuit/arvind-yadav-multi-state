// Mirror of the W2 D5 Mongo read-model shape. All money fields are STRINGS,
// not JS numbers — JS `number` is IEEE-754 binary64 and loses cents at
// scale. The page never does arithmetic on these strings; if it ever does,
// reach for `decimal.js` (the JS equivalent of BigDecimal).

export type TenantLine = {
  readonly id:     string;
  readonly amount: string;            // BigDecimal-as-string, scale 2
};

export type Tenant = {
  readonly id:              string;
  readonly primaryState:    string;
  readonly stateCount:      number;
  readonly totalAllocation: string;   // BigDecimal-as-string, scale 2
  readonly lines:           ReadonlyArray<TenantLine>;
};
