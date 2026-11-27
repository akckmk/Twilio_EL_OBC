# Agent Control: Security Test Report (v2, dashboard redesign)

**Date:** 27 November 2026
**Version tested:** dashboard redesign, commit `75b657c`; fixes in commit `d9822ed`
**Tester:** Claude Code, at the owner's request
**Scope:** everything in the repository:
- the `agentcontrol` backend (9 endpoints);
- the Android app (5 tabs, widgets, notifications, fingerprint lock);
- both GitHub Actions workflows;
- the public git history.

The first review (v1, the earlier keys-on-phone version) is in the git history. Its protections that still apply are listed in section 4.

---

## 1. Summary

The redesign moved your Twilio and ElevenLabs keys off the phone into the backend, and added transcripts, recordings, phone numbers and costs. That creates new things to protect: the backend's endpoints, the build pipeline that holds your keys, and the private call data itself.

**Results:**
- **9 issues found, all fixed:** 1 High, 2 Medium, 6 Low.
- **The most serious one was in the build pipeline, not the app.** Your Twilio token and ElevenLabs key were visible to steps that install and run code downloaded from npm. A malicious package could have stolen them.
- **The repo is public.** Nothing secret is in it: I scanned the whole history. But GitHub run summaries are public too, and the deploy summary would have shown agent names, live-call counts and credits.
- **The backend held up against 13 attack payloads on 8 parameters.** Bad access keys, a header-spoofing trick and path-traversal attempts were all refused. A request without the right key never reaches Twilio or ElevenLabs.
- **New attack tests** (8 for the backend, 4 for the app) now run on every build.

**Your one most useful action:** make the repo private (section 6, R-1). It's free and takes one click.

| Severity | Found | Fixed |
|---|---|---|
| High | 1 | 1 |
| Medium | 2 | 2 |
| Low | 6 | 6 |

---

## 2. How it was tested

| Method | What was done |
|---|---|
| **Secret and personal-data scan of the public history** | Every commit searched for Twilio SIDs and keys, ElevenLabs keys, call and conversation IDs, real phone numbers, your name and email, the signing password and the access key. |
| **Backend attack tests** | 8 automated attacks (`backend/test/functions.test.js`, "ATTACK:" tests) against all 9 endpoints, with fake Twilio and ElevenLabs services to see what an attack would reach. |
| **Twilio runtime review** | Read the source of Twilio's runtime (`@twilio/runtime-handler` 2.1.2) to see exactly how a request's headers and parameters reach the code. |
| **App attack tests** | 4 attack tests on the real app screens on a simulated phone (Robolectric), run on GitHub. |
| **App package inspection** | `aapt2`: permissions, which parts other apps can open, debuggable flag; `apksigner`: signing key. |
| **Static scanners** | `mobsfscan` (OWASP mobile rules), Android Lint. |
| **Dependency vulnerabilities** | `npm audit` on the backend's locked dependency tree. Android libraries: the OSV database was unreachable from here, so Dependabot now does this on GitHub (L-6). |
| **Pipeline review** | Which steps see which secrets, what is published, and whether third-party code runs alongside secrets. |

### Attackers considered
1. **Anyone on the internet** who finds the backend address. That's easy, since it's printed in a public repo's run summary.
2. **A malicious app on your phone.**
3. **Someone holding your unlocked phone,** or a thief with a locked one.
4. **A hostile network** (rogue Wi-Fi, an interception proxy).
5. **A supply-chain attacker:** a compromised npm, Maven or GitHub Actions package.
6. **Anyone reading the public repo,** its history, its run summaries and its downloads.

### Limits
- The backend wasn't attacked live on Twilio, because it isn't deployed yet and Twilio's servers are blocked from this machine. The attacks ran against the same code with fake services.
- Nothing ran on a real phone, only the simulator.
- The Android libraries weren't checked against a vulnerability database here (Dependabot now does this).

---

## 3. Findings and fixes

### H-1. Build pipeline exposed your Twilio and ElevenLabs keys to third-party code (High), fixed
- **Attack:** the deploy workflow gave **all** its steps your Twilio Auth Token, ElevenLabs key and access key. That includes `npm install -g twilio-cli`, which downloads hundreds of packages and runs their install scripts, and `npm test`. One compromised npm package could have read the keys and sent them away. That would mean full control of your Twilio account and ElevenLabs workspace. The APK workflow similarly gave the signing password to the test step, which runs third-party test libraries.
- **Fix:**
  - Secrets are now given **only to the steps that need them**. The install and test steps see none.
  - Tests install with `npm ci --ignore-scripts` from a committed lockfile, so no install scripts run and versions can't drift.
  - The `.env` file holding secrets is removed even if the deploy fails (`trap`).
- **Remaining:** the steps that do need secrets still run the Twilio CLI and Gradle, which are third-party code. Their versions are pinned (section 6, R-4).

### M-1. Public run summaries would publish private details (Medium), fixed
- **Attack:** this repo is public, so anyone can read its Actions run summaries and logs. The deploy summary listed your agents' names, how many calls were live, and your remaining ElevenLabs credits.
- **Fix:** the summary now shows only ✅/❌ setup checks and the backend URL, which is useless without the access key, with a note explaining why. The setup check prints check names and missing permissions, never names, numbers or usage.

### M-2. A transcript matched by time could be the wrong call's (Medium), fixed
- **Risk:** when a call can't be matched to its ElevenLabs conversation by the exact call ID, the backend pairs them by start time. If the conversation carries **no** call ID (for example a SIP call), the backend couldn't double-check, and two calls starting at the same moment could show each other's transcript.
- **Fix:**
  - The backend now labels such matches `matchedBy: "time"`.
  - The app shows a warning on the call page: "matched by start time, could belong to a different call".
  - When the conversation **does** carry a call ID, a mismatch still hides the transcript entirely.
- **Evidence:** the tests "a transcript found only by time is labelled" (backend) and "time-matched transcript carries a warning" (app).

### L-1. The key check accepted lists, and Twilio lets parameters overwrite headers (Low), fixed
- **Discovery:** Twilio's runtime builds each request as `{ request: { headers }, ...URL parameters, ...body }`, so **a parameter named `request` replaces the real headers**. In this code, the attacker's fake headers still had to contain the **correct key**. The check also turned a list into text before comparing, so `[key]` matched `key`. Neither is a way in without the key, but the check was looser than it should be.
- **Fix:** the key must now be one plain text value of sane length, compared in constant time. Lists, objects and forged `request` values are refused. All 9 endpoints are tested against forged headers.

### L-2. Malformed parameters caused server errors (Low), fixed
- **Attack:** sending parameters as objects or lists, for example JSON bodies or `?agent[x]=y`, could make `String()` throw, so the backend returned a 500 error instead of a clean refusal.
- **Fix:** every parameter now goes through one helper that accepts only plain text of 200 characters or fewer. 13 hostile payloads on 8 parameters now get only 200, 400 or 404 answers, with no stack traces. The payloads included path traversal, encoded traversal, null bytes, query and fragment injection, very long text and SQL-style strings. None of them reached Twilio or ElevenLabs in an unsafe form.

### L-3. An unconfigured server told strangers which secret was missing (Low), fixed
- **Fix:** strangers now see only "Server is not set up". The detail goes to the private Twilio log.

### L-4. Notifications showed full phone numbers (Low), fixed
- **Risk:** apps with notification access, such as smartwatch, automation and launcher apps, can read every notification's text.
- **Fix:** call notifications show the contact's name if you saved one, otherwise "number ending 2222". The lock screen still shows only "Call started" or "Call ended".

### L-5. Other apps could open the main screen with any tab value (Low), fixed
- **Fix:** only the app's own five tab IDs are accepted; anything else opens the Agents tab. No crash or exploit was possible before, but the screen now refuses unexpected input outright.

### L-6. No automatic dependency-vulnerability monitoring (Low), fixed
- **Fix:**
  - Added `.github/dependabot.yml`, so GitHub checks npm, Gradle and GitHub Actions dependencies weekly and proposes fixes.
  - Added `backend/package-lock.json`, so dependency versions are fixed and audited.
  - `npm audit` reports **0** known vulnerabilities.

---

## 4. Checked and found safe

| Area | Result |
|---|---|
| Secrets in the public repo or history | **None.** Only made-up test numbers (+1555…). The signing key file is encrypted (AES-256) and its password appears nowhere. |
| Your personal data in the repo | **None.** No real phone numbers, call IDs, transcripts, name or email. |
| Access key on all 9 endpoints | Required everywhere; wrong, missing, near-miss, duplicated and forged keys all get 401. |
| Requests without the key | **Never reach Twilio or ElevenLabs** (tested), so no data leaks and no cost. |
| Twilio stripping the key header | Checked against Twilio's runtime: `x-agent-control-key` is not on its stripped-header list. |
| Path / URL injection | IDs are checked against strict patterns before use; upstream addresses are fixed. |
| Ending calls via a link (cross-site) | Not possible: the key travels in a custom header, which browsers can't send cross-site without permission, and the backend grants none. |
| Response caching | Every response is `Cache-Control: no-store`. |
| Logs | The backend logs only endpoint names and status codes. A test fails if a phone number, name, summary or transcript is ever logged. |
| Redirects | Refused by the app and by the backend's ElevenLabs requests, so a redirect can't carry a key away. |
| Recordings | Streamed through the backend in pieces. No ElevenLabs key or signed link reaches the phone. The temporary file is deleted when you leave the page, or on the next start after a crash. |
| Phone storage | Backend URL, access key, contacts and the cached history are AES-256-GCM encrypted with a key in the phone's security chip. Excluded from backups. |
| Key display | The access key is never shown again after saving; the clipboard is wiped. |
| App package | Release build (not debuggable), signed with your permanent key (`6ac2f9ce…9e490883`). Only the launcher and widget-setup screens are open to other apps, as Android requires. The new notification service is private. |
| Permissions | Internet, notifications, fingerprint, foreground service, hide overlays. **No contacts, phone, microphone or location access.** |
| Screens | Screenshots, screen recording and Recent-apps previews blocked; overlays hidden; buttons ignore covered taps. |
| Network | HTTPS only; only Android's built-in certificate authorities are trusted. |
| Widget retargeting by other apps | Refused (tested). |
| Fingerprint lock | Content stays hidden until unlocked (tested). |

---

## 5. Attack tests that now run on every build

**Backend** (`npm test`; 29 tests in all, 8 of them attacks):
1. A forged `request` parameter can't fake the key header (all 9 endpoints).
2. Duplicate, padded or truncated keys, or a key in the URL, are refused.
3. Refused requests never reach Twilio or ElevenLabs.
4. A misconfigured server doesn't say which secret is missing.
5. 13 injection and traversal payloads on 8 parameters are refused or harmless.
6. No caching and no cross-site access.
7. Time-matched transcripts are labelled.
8. Nothing private is logged, during normal use or under attack.

**App** (GitHub "Build Android APK", simulated phone):
1. A bogus tab value from another app can't crash the app.
2. The fingerprint lock hides everything until unlocked.
3. Notifications never show full phone numbers.
4. A time-matched transcript shows its warning.

These run alongside the earlier tests: access key on every request, redirects refused, no provider keys in the app, old keys wiped on upgrade, widget retargeting refused, key never displayed, history cache encrypted.

---

## 6. Remaining risks and recommendations

**R-1. Make the repository private (recommended, your action).**
- To do it: GitHub, then **Settings → General → Danger Zone → Change visibility → Private**.
- Nothing secret is exposed today. But a private repo hides your run summaries, backend address, downloadable APKs and code from strangers, and it costs nothing.

**R-2. The access key is a master key to your calls.**
- Whoever has it can end calls and read transcripts. It's 192-bit random, so guessing it is infeasible, but it could still be stolen, from your password manager or an unlocked phone.
- **If you suspect that:** change the `AGENT_CONTROL_KEY` secret, run **Deploy backend**, and enter the new key in the app.

**R-3. The backend uses your Twilio Auth Token**, which controls the whole Twilio account.
- Keep two-factor login on GitHub, Twilio and ElevenLabs, since those accounts guard the secrets.
- Only you should have write access to the repo.

**R-4. Steps that need secrets still run pinned third-party tools** (Twilio CLI 7.3.0, Gradle plugins). This is inherent to deploying. Dependabot will flag known problems.

**R-5. The fingerprint lock is a screen lock, not encryption.**
- It stops someone holding your unlocked phone from reading calls. It isn't designed to resist a rooted or hacked phone.
- The widgets end calls without a fingerprint, by design (one tap).

**R-6. Data held outside the phone:**
- Twilio's memory holds a call's transcript for up to 10 minutes after you view it (cache).
- Twilio Sync stores only call IDs and conversation IDs, no transcripts or numbers.

**R-7. No request-rate limit on the backend.** Guessing the key is infeasible, but someone could send junk requests, each costing a tiny fraction of a cent in Twilio Function usage. Twilio's own abuse protection applies.

**R-8. Not covered by this test:**
- the live Twilio deployment (not deployed yet);
- a real phone;
- Twilio's and ElevenLabs' own security;
- a vulnerability-database check of the Android libraries (Dependabot now does this).

---

## 7. How to re-run

```
cd backend && npm test                       # 29 tests incl. 8 attacks
cd android && ./gradlew testDebugUnitTest    # app tests incl. 4 attacks (simulated phone)
```

---

## 8. Addendum: one-time setup inside the app (added 27 Nov 2026)

**What changed:** you can now paste your Twilio Account SID, Auth Token and ElevenLabs key into the app once. The app installs the backend into your Twilio account, using the same steps as Twilio's own deploy tool, and then forgets the keys.

**New risk:** your most powerful secrets are typed on the phone for a minute or two. Here's how that's contained:

| Protection | How it's checked |
|---|---|
| **Keys never saved.** The Twilio Auth Token and ElevenLabs key are kept only in memory during setup. Only the backend URL and a new random access key are saved, encrypted. | A simulated-phone test runs the whole setup, then searches **every file** the app wrote for the token and the ElevenLabs key: none found. A code test also checks that setup never writes files or preferences. |
| **Boxes and clipboard wiped** when setup finishes or fails. | Tested (the boxes are empty afterwards). |
| **The Twilio login goes only to Twilio:** `api`, `serverless`, `serverless-upload` and `sync.twilio.com`, over HTTPS, with redirects refused. | Tested: a deployer pointed at any other address refuses **before sending anything**. |
| **The ElevenLabs key goes only into your Twilio account,** as a private environment variable the backend reads. The app never contacts ElevenLabs. | Tested; a code test fails if the app ever contains an ElevenLabs address. |
| **The installed code comes from inside the signed app,** bundled at build time from `backend/`. Nothing is downloaded from elsewhere. | Tested: the uploaded code is the real backend code. |
| **Fresh access key on every setup run** (192-bit, from the phone's secure random generator). Re-running setup locks out any old key. | Tested: keys are unique and re-running rotates the key. |
| **Screen protections:** setup screen screenshots and recording blocked, keyboard learning and autofill off, secret boxes masked. | Same window protections as every screen. |
| **Error messages never repeat your keys.** | Tested. |

**Remaining risks:**
- **A compromised phone or keyboard app** could capture what you type during setup. Use your phone's built-in keyboard, and only run setup on your own phone.
- **The backend still uses your Twilio account credentials,** as before (R-3). Twilio gives them to your own functions automatically.
- **Not tested against real Twilio.** Twilio's servers were unreachable from the test machine, so setup was proven against a fake Twilio that follows the real API's steps. Those steps were copied from Twilio's official deploy tool (`@twilio-labs/serverless-api` 5.8.0). The first real run is yours. If any step fails, the app shows Twilio's exact message, and nothing is half-saved on the phone.
