// Local unit test for the PreSignUp handler. Run: node test.mjs
import { handler } from './index.mjs';

const FUNNEL = '34rg0vqoobfv8hhvg8kunkd738';
let fails = 0;
const ok = (cond, name) => { console.log(`${cond ? 'PASS' : 'FAIL'} — ${name}`); if (!cond) fails++; };

const ev = (clientId, triggerSource, email) => ({
  triggerSource,
  callerContext: { clientId },
  request: { userAttributes: email ? { email } : {} },
  response: {},
});

// funnel self-signup with email → auto-confirm + auto-verify
let r = await handler(ev(FUNNEL, 'PreSignUp_SignUp', 'new@borrower.com'));
ok(r.response.autoConfirmUser === true, 'funnel signup auto-confirms');
ok(r.response.autoVerifyEmail === true, 'funnel signup auto-verifies email');

// funnel signup WITHOUT email → confirm but no email-verify
r = await handler(ev(FUNNEL, 'PreSignUp_SignUp', null));
ok(r.response.autoConfirmUser === true, 'funnel signup (no email) still confirms');
ok(r.response.autoVerifyEmail !== true, 'funnel signup (no email) does NOT verify email');

// different client → untouched
r = await handler(ev('some-other-client', 'PreSignUp_SignUp', 'x@y.com'));
ok(!r.response.autoConfirmUser, 'other client NOT auto-confirmed');
ok(!r.response.autoVerifyEmail, 'other client email NOT auto-verified');

// admin-create on the funnel client → untouched (only self-signup is auto-confirmed)
r = await handler(ev(FUNNEL, 'PreSignUp_AdminCreateUser', 'a@b.com'));
ok(!r.response.autoConfirmUser, 'admin-create NOT auto-confirmed');
ok(!r.response.autoVerifyEmail, 'admin-create email NOT auto-verified');

console.log(fails === 0 ? '\nALL PASS' : `\n${fails} FAILED`);
process.exit(fails === 0 ? 0 : 1);
