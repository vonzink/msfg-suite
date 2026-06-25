// Cognito Pre Sign-up trigger for the shared MSFG user pool (us-west-1_S6iE2uego).
//
// WHY: EMAIL_OTP is only offered as a USER_AUTH first factor to users whose email is already
// VERIFIED. A brand-new funnel borrower self-signs-up with an UNVERIFIED email, so Cognito returns
// SELECT_CHALLENGE with only PASSWORD (no EMAIL_OTP, no code) — passwordless login fails for exactly
// the users the funnel produces. Auto-confirming + auto-verifying the email at sign-up makes EMAIL_OTP
// available immediately on the next InitiateAuth.
//
// SAFE for passwordless: the OTP sent to that email is still the gate — a sign-up with someone else's
// email can't sign in (the code goes to the real owner). We do NOT auto-verify phone.
//
// SCOPED: only self-service sign-ups (PreSignUp_SignUp) from the funnel/borrower app client. Admin-
// created users and federated/external-provider sign-ups are left untouched. Other app clients
// (e.g. staff console) are left untouched. Reuses MsfgPreTokenGenRole (basic logs only; no AWS calls).
//
// Env: FUNNEL_CLIENT_ID (the borrower app client id; defaults to the known mortgage-app-web client).

const FUNNEL_CLIENT_ID = process.env.FUNNEL_CLIENT_ID || '34rg0vqoobfv8hhvg8kunkd738';

export const handler = async (event) => {
  const clientId = event?.callerContext?.clientId;
  const isFunnel = clientId === FUNNEL_CLIENT_ID;
  const isSelfSignUp = event?.triggerSource === 'PreSignUp_SignUp';

  event.response = event.response || {};

  if (isFunnel && isSelfSignUp) {
    event.response.autoConfirmUser = true;
    // Only auto-verify the email if one was supplied (it's the sign-in username + OTP destination).
    if (event?.request?.userAttributes?.email) {
      event.response.autoVerifyEmail = true;
    }
    console.log(`presignup: auto-confirm+verify for funnel client ${clientId}`);
  } else {
    console.log(`presignup: left untouched (clientId=${clientId}, trigger=${event?.triggerSource})`);
  }

  return event;
};
