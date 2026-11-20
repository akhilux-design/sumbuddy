# Free server options for group splitting

Research from 2026-11-17.

**Decided (2026-11-17): setup B, Cloudflare (Workers, D1, Durable Objects, Pages) with Google sign-in.** The owner
chose it over Firebase for low lock-in and generous free limits, accepting more build work and no India data region.
Push notifications still go through FCM, which needs a free Firebase project used for messaging only. Phone OTP is
not used; the phone number is optional and unverified.

Facts checked on 17 Nov 2026 against each provider's own pages unless a line says otherwise. Free plans change often, so check again before you build.

**First, what the server is for.** Personal expense tracking stays on the phone and needs no server. The server is only for group splitting:
- sign-in
- groups, shared expenses, balances and settle-ups
- syncing changes between members' phones
- push notifications
- the public web link that friends open without installing the app

---

## 1. Shortlist: four zero-cost setups

### A. Firebase (free "Spark" plan): Google sign-in, Firestore, FCM, Hosting

**What it covers.** Nearly everything from one company:
- **Google sign-in:** one tap, using the Google account already on the phone.
- **Firestore:** Google's cloud database. It syncs live to every phone in a group and keeps working offline.
- **FCM (Firebase Cloud Messaging):** Google's push-notification service.
- **Hosting:** free web hosting for the friends' link page.

**Free limits that matter** (https://firebase.google.com/pricing):
- Sign-in is free up to 50,000 monthly users.
- Firestore stores 1 GiB. Each day it allows 50,000 reads and 20,000 writes. A "read" is one record delivered to a phone.
- 10 GiB of data out per month.
- FCM is listed as "No-cost", with no limit.
- Hosting gives 10 GB of storage and 360 MB of transfer per day.
- The link page gets a free address ending in `.web.app` or `.firebaseapp.com` (https://firebase.google.com/docs/hosting).

**When it starts costing.** It never does on Spark. If you go over a limit, that product is "shut off for the remainder of that month" and you get no bill (https://firebase.google.com/docs/projects/billing/firebase-pricing-plans).
- The first limit you would hit is reads. At about 1,000 daily users, 50,000 reads a day is about 50 records per user per day. That is fine if screens load carefully, and tight if every screen watches whole groups live.
- The next step up is the "Blaze" plan, which charges for what you use beyond the free limits and needs a card.

**Catches:**
- **No card needed** on Spark (same page as above).
- **No idle pausing or deletion** is documented.
- **Region:** the database can live in Mumbai (asia-south1). You cannot move it once created (https://firebase.google.com/docs/firestore/locations). Sign-in data is processed only in the US (https://firebase.google.com/support/privacy).
- **Server code:** Spark cannot run your own server code ("Cloud Functions" are "Not applicable" on Spark). Sending a push like "Arjun added ₹1,200" must happen on a server, so you need one small free helper outside Firebase. A free Cloudflare Worker is a short piece of code Cloudflare runs for you.
- **Lock-in is high.** The data model and access rules only work on Firebase. Moving later means rewriting the sync code.

**Work to build: lowest.** Google's official Android libraries handle sign-in, live sync, offline use and push. The extra pieces are the small push helper and access rules for the web link. With those rules, anyone holding the link can see that one group.

### B. Cloudflare: Workers, D1, Durable Objects and Pages, plus Google sign-in and FCM

**What it covers:**
- **Workers:** your own small server code, which Cloudflare runs worldwide.
- **D1:** a SQL database.
- **Durable Objects:** one live "room" per group, which pushes changes to members' phones instantly.
- **Pages:** free hosting for the link page at `yourname.pages.dev`.
- **Sign-in:** Google sign-in, checked by your Worker.
- **Push:** FCM, called by your Worker.

**Free limits, per day:**
- Workers: 100,000 requests (https://developers.cloudflare.com/workers/platform/pricing/).
- D1: 5 million rows read, 100,000 rows written, 5 GB stored (https://developers.cloudflare.com/d1/platform/pricing/).
- Durable Objects: 100,000 requests. Messages sent out to phones are free (https://developers.cloudflare.com/durable-objects/platform/pricing/).
- Pages: 500 site builds a month. Its server functions share the Workers request count (https://developers.cloudflare.com/pages/platform/limits/).
- Limits reset at 05:30 India time (00:00 UTC).

**When it starts costing.** It never does on the free plan. Requests fail until the daily reset, and nothing is billed or deleted.
- Rough estimate (my own, not a provider figure): 1,000 daily users × about 50 calls is about 50,000 requests a day. That is half the free allowance.
- The paid plan is US$5 a month for 10 million requests (https://developers.cloudflare.com/workers/platform/pricing/).

**Catches:**
- **No India region.** The nearest you can ask for is "Asia-Pacific", and Cloudflare says that is "best effort" (https://developers.cloudflare.com/d1/configuration/data-location/).
- **Card at signup: unconfirmed.** Third-party guides say no card is needed.
- **workers.dev is meant for hobby projects** in Cloudflare's own words, so put the link page on `pages.dev` (https://developers.cloudflare.com/workers/configuration/routing/workers-dev/).
- **Lock-in is low.** The data is standard SQLite and the code is plain web-standard code.

**Work to build: most.** Cloudflare has no Android library and no built-in sign-in. You write a small API and the phone talks to it. The phone keeps its own copy of the data in Room, the app's existing on-phone database, and sends changes up from a queue. In return you control everything, and the web link and the API live on one free address.

### C. Supabase Free plan (Mumbai), plus Google sign-in, FCM, and a free page host

**What it covers.** A hosted Postgres database with sign-in, live updates and small server functions. Postgres is a standard database you can move to any host later.

**Free limits** (https://supabase.com/pricing):
- 50,000 monthly users
- 500 MB database
- 5 GB data out per month
- 500,000 function calls per month
- 200 phones connected live at the same time
- 2 active projects

**When it starts costing.** It never bills on Free. The next plan is the Pro plan, from US$25 a month (https://supabase.com/pricing).
- The first limit is likely the 200 live connections, at around 1,000 daily users, unless phones connect only while the app is open.

**Catches:**
- **It pauses after 1 week without use** ("Free projects are paused after 1 week of inactivity", https://supabase.com/pricing). A paused project can be restored for up to a year (https://supabase.com/docs/guides/platform/free-project-pausing). That pause is a real risk while the app has few users.
- **Card at signup: unconfirmed.**
- **Mumbai region exists** (https://supabase.com/docs/guides/platform/regions). Whether Free can choose it is not stated. Sign-in data can stay in Mumbai too.
- **No web hosting.** Put the link page on `pages.dev` or `web.app`.
- **Lock-in is low.**

**Work to build: medium.** The Android library (supabase-kt) is community-maintained. Offline sync with Room is your own code.

### D. Self-hosted on Oracle Cloud's "Always Free" server

**What it covers.** A free rented computer (a VM, or virtual machine) running PocketBase, a one-file backend with a database, sign-in and live updates, or your own Kotlin server. You still use FCM for push.

**Free limits.** 2 processor cores, 12 GB memory, 200 GB disk and 10 TB data out per month (https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm). That is plenty for 10,000 users.

**Catches:**
- **Card required** (https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm).
- **Oracle may take back a server that is mostly idle for 7 days**, and a small app may look idle.
- **Oracle halved the free allowance in June 2026** without announcing it (https://infoq.com/news/2026/07/oracle-cloud-free-tier-limits/).
- **PocketBase is not finished:** it is version 0.42 and says it is not for production-critical apps (https://pocketbase.io/docs/).
- **You maintain it yourself:** security updates and backups are your job.
- **Region:** Mumbai and Hyderabad regions exist. This is general knowledge, not re-checked.

**Work to build: high,** plus ongoing upkeep. Only worth it if the other three fail.

**Ruled out:**
- Appwrite: no free phone sign-in, pauses weekly, no India region yet.
- Convex: no India region, small limits.
- Render: the server sleeps, and the free database is deleted after 30 + 14 days.
- Vercel Hobby: non-commercial use only.
- Deno Deploy: shuts down April 2027.
- InstantDB: signups closed.
- Fly.io: no free plan.
- AWS: the free plan ends after 6 months.
- PocketHost: paid only.

---

## 2. Can phone OTP sign-in be free?

**No, not at real scale.** An OTP is the 6-digit code sent by SMS. Every provider pays mobile networks for each SMS.
- **Firebase:** the cheapest "free" route. You need the paid Blaze plan with a card. The first 10 SMS a day are free (about 300 a month). After that India costs **US$0.07 per SMS, about ₹6**, and every resend counts (https://firebase.google.com/docs/auth/limits, https://cloud.google.com/identity-platform/pricing).
- **Indian SMS companies:** about ₹0.12–0.25 per SMS plus 18% GST (https://msg91.com/in/pricing/otp, https://2factor.in/v4/index.html). They also need TRAI DLT registration, the paperwork India requires for sender names and message templates.
- **WhatsApp codes:** about ₹0.14 each, according to third-party sites only.
- **Truecaller's sign-in kit:** its own FAQ says it is "100% free to use, no user verification limits" (https://truecaller4developers.gitbook.io/truecaller-sdk/faqs/general). It only works with one tap for people who have Truecaller installed. For everyone else it falls back to a missed call, which needs call-log permissions. Skip that part.

**Use Google sign-in instead** (Android's Credential Manager, https://developer.android.com/identity/sign-in/credential-manager-siwg). It is free up to 50,000 monthly users on Firebase or Supabase, needs one tap, and needs no code to type.

**What changes in the design:**
1. **Replace "Sign up with phone" with a "Continue with Google" screen.** Google recommends showing both the automatic account sheet and a visible button, because the sheet does not appear on phones with no Google account.
2. **Delete the "Enter code" screen.**
3. **Keep a phone-number field, but after sign-in, optional and not verified.** It is only used to recognise friends and to show beside your name in a group. Label it so people understand it is not checked.
4. **Friends using the web link still need no sign-in at all.**
5. **Optional later:** add "Continue with Truecaller" as a second button.

If you ever want proven phone numbers, add OTP later as a paid extra. It does not need to block version 1.

---

## 3. Recommendation

**Use setup A: Firebase Spark** with Google sign-in, Firestore in Mumbai, FCM and Hosting on `*.web.app`. Add one free Cloudflare Worker that only sends push notifications.

**Why:**
- It is the least code to build and test, because Google's official Android libraries already handle sign-in, live sync, offline use and push.
- It needs no card, has no idle pause, and stops instead of billing.
- Group data can stay in Mumbai.
- The link page gets a free address.

**The trade-offs to accept:**
- High lock-in.
- Sign-in data is processed in the US.
- At around 1,000 daily users, the 50,000 reads a day needs careful design.

**Runner-up: setup B, Cloudflare.** It has the most generous free limits, the lowest lock-in, and no pausing. It costs more build work and has no India region.

**Free addresses for the link host:** `name.web.app` (Firebase), `name.pages.dev` (Cloudflare Pages) or `name.appwrite.network`. Avoid `workers.dev`, because Cloudflare calls it hobby-only. Pick the name once: changing the link domain later breaks every link already shared.

---

## 4. Facts that stayed unconfirmed

- **Card at signup:** not stated on official pages for Cloudflare, Supabase, Convex, Nhost or MongoDB Atlas.
- **Mumbai on free plans:** whether the free plan can choose Mumbai for Firestore (Spark) and Supabase (Free). Nothing says it can't.
- **Commercial use on free plans:** confirmed only for Firebase Spark, and refused for Vercel Hobby. Not stated by Supabase, Cloudflare or Netlify.
- **Firebase inactivity rule:** whether Firebase has any rule for deleting inactive projects. None was found.
- **Firebase SMS price:** the 10 free SMS a day and the US$0.07 India price came from the page source and search snippets of https://cloud.google.com/identity-platform/pricing, because it would not render in the browser tools. One research lane read it from the page source.
- **Truecaller's FAQ:** it is undated, and whether its SMS fallback is also free is not stated.
- **WhatsApp India price:** third-party sources only. Whether Meta allows a free "user messages us" verification flow is also unconfirmed.
- **Oracle details:** whether pay-as-you-go accounts avoid idle reclaim. Reports that RuPay cards are refused and that Mumbai is short of free servers come from third parties.
- **Default addresses for Vercel, Netlify, Render and Fly:** from general knowledge only.
- **App Links on a free address:** whether Android App Links (`assetlinks.json`) and passkeys work on a free subdomain such as `web.app` or `pages.dev`. Probably yes for App Links; not checked. App Links make the link open the app when it is installed.
- **India's data law (DPDP Act 2023):** secondary sources say transfers abroad are allowed unless the government restricts a country, and none is restricted yet. They disagree on when that section takes effect (https://dpdpa.com/dpdpa2023/chapter-4/section16.html). Either way, say in the privacy notice where data is processed. This is not legal advice.

No personal data or project name was sent to any service during the research.
