// Pure-logic tests for the pre-token-gen handler. Run: `node test.mjs` (no AWS, no deps).
import assert from 'node:assert/strict';

process.env.MSFG_ORG_ID = '00000000-0000-0000-0000-0000000000aa';
const { handler } = await import('./index.mjs');

const ORG = '00000000-0000-0000-0000-0000000000aa';
const ev = (groups) => ({ request: { groupConfiguration: { groupsToOverride: groups } }, response: {} });

// 1. Group-less (the funnel-borrower case) → org_id injected AND Borrower defaulted into the token.
{
  const out = await handler(ev([]));
  assert.equal(out.response.claimsOverrideDetails.claimsToAddOrOverride.org_id, ORG);
  assert.deepEqual(out.response.claimsOverrideDetails.groupOverrideDetails.groupsToOverride, ['Borrower']);
}

// 2. Staff (has a group) → org_id injected, NO group override (real groups flow through untouched).
{
  const out = await handler(ev(['LO']));
  assert.equal(out.response.claimsOverrideDetails.claimsToAddOrOverride.org_id, ORG);
  assert.equal(out.response.claimsOverrideDetails.groupOverrideDetails, undefined);
}

// 3. Already a Borrower (post-confirm DID fire) → no override; real group emitted.
{
  const out = await handler(ev(['Borrower']));
  assert.equal(out.response.claimsOverrideDetails.groupOverrideDetails, undefined);
}

// 4. Missing groupConfiguration entirely → treated as group-less → Borrower.
{
  const out = await handler({ response: {} });
  assert.deepEqual(out.response.claimsOverrideDetails.groupOverrideDetails.groupsToOverride, ['Borrower']);
}

// 5. Pre-existing claimsToAddOrOverride is preserved alongside org_id.
{
  const e = ev([]); e.response.claimsOverrideDetails = { claimsToAddOrOverride: { foo: 'bar' } };
  const out = await handler(e);
  assert.equal(out.response.claimsOverrideDetails.claimsToAddOrOverride.foo, 'bar');
  assert.equal(out.response.claimsOverrideDetails.claimsToAddOrOverride.org_id, ORG);
}

console.log('OK — all pre-token-gen handler cases pass');
