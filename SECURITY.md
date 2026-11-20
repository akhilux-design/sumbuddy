# Security policy

SumBuddy handles money and reads SMS, so security reports matter a lot. Thank you for taking the time.

## Reporting a problem

Please report privately, not in a public issue:

- On GitHub, open <https://github.com/akhilux-design/sumbuddy/security/advisories/new> ("Report a vulnerability" under
  the Security tab).

Say what you found, how to reproduce it, and what an attacker could do with it. Use made-up data: never include real
SMS, account numbers, tokens or anyone's personal details.

You will get an answer within a week. Please give us a reasonable time to fix the problem before you make it public;
we will tell you when the fix is out and credit you if you want.

## In scope

- The Android app (`app/`, `parser/`, `splits/`, `splitwise/`): for example SMS content leaving the phone or reaching
  logs, data readable by other apps, link handling.
- The server (`server/`): sign-in and sessions, access to groups you are not in, share links, rate limits.
- The friends' web page (`server/web/`).

Out of scope: problems that need a rooted or already compromised phone, denial of service by heavy traffic, and
reports from automated scanners without a demonstrated problem.

## Supported versions

Only the latest release gets fixes. Update through
<https://github.com/akhilux-design/sumbuddy/releases/latest> or Obtainium.
