// Cognito Pre Token Generation (V1_0) for the shared MSFG user pool (us-west-1_S6iE2uego).
//
// Two responsibilities, both required for the suite to accept a token:
//
//  1. org_id — the suite (msfg-suite) fail-closes (401) on a missing/!UUID `org_id` claim. Inject a
//     constant tenant id so every token from this single-tenant pool authenticates. NOTE (V1): this
//     lands on the ID token ONLY; the access token gets no org_id. The FE sends the id_token as the
//     bearer, so this works — see the spec's V1→V2 note before widening callers to the access token.
//
//  2. Borrower default (G8 fix) — a self-service funnel signup confirmed via passwordless EMAIL_OTP
//     does NOT reliably fire the PostConfirmation trigger, so the `Borrower` group is never assigned
//     → `cognito:groups` is empty → the suite's SecurityConfig 403s the (role-less) token at the
//     filter, before it ever reaches /api/me, so no suite-side backstop can run. We fix it at the
//     only place that can put the role INTO the token: here. Any user the pool would emit with NO
//     groups is defaulted to `Borrower` (least privilege: own linked loan only) in the token on every
//     mint. Users with ANY group (staff, agent, or an already-assigned Borrower) are left untouched —
//     so this can never elevate or relabel a staff member. Pure claim override; no AdminAddUserToGroup,
//     hence no new IAM on MsfgPreTokenGenRole.
//
// Env: MSFG_ORG_ID (required, the tenant UUID) · DEFAULT_GROUP (optional, defaults to "Borrower").

const ORG_ID = process.env.MSFG_ORG_ID;
const DEFAULT_GROUP = process.env.DEFAULT_GROUP || 'Borrower';

export const handler = async (event) => {
  if (!ORG_ID) {
    console.error('MSFG_ORG_ID env var is not set — org_id will NOT be injected');
    return event;
  }

  event.response = event.response || {};
  const prev = event.response.claimsOverrideDetails || {};

  const details = {
    ...prev,
    claimsToAddOrOverride: { ...(prev.claimsToAddOrOverride || {}), org_id: ORG_ID },
  };

  // The groups Cognito would otherwise emit for this user (id + access token `cognito:groups`).
  const groups =
    (event.request &&
      event.request.groupConfiguration &&
      event.request.groupConfiguration.groupsToOverride) ||
    [];

  // Group-less → least-privilege Borrower, in this token. (Preserve an explicit prior override if set.)
  if (groups.length === 0 && !(prev.groupOverrideDetails && prev.groupOverrideDetails.groupsToOverride)) {
    details.groupOverrideDetails = { groupsToOverride: [DEFAULT_GROUP] };
  }

  event.response.claimsOverrideDetails = details;
  return event;
};
