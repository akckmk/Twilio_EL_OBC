# Handoff: Agent Control (Android dashboard + Twilio backend), 2026-11-27
## Done
- Repo `akckmk/Twilio_EL_OBC` (public), branch `ccr-f977b0b7-gv22jh`, latest commit `51c1c67`. All work is here; no `agent-control` repo exists.
- **Backend** `backend/` (Twilio Serverless "agentcontrol", node24). Endpoints: /agents /end /live /calls /call /recording /usage /analytics /health. All require the `X-Agent-Control-Key` header. 31 tests: `cd backend && npm test`.
- **App** `android/` (Kotlin). Tabs: Agents, Live, History, Usage, Analytics; plus Contacts, Settings, widgets, fingerprint lock, call notifications.
- **One-time in-app setup** (`Deployer.kt`, `SetupActivity.kt`): the user pastes Twilio SID, Auth Token and ElevenLabs key; the app deploys the bundled backend, saves only the URL and a new access key, and forgets the provider keys.
- **Security:** pen test v2 plus setup addendum in `SECURITY_REPORT.md` and `Agent_Control_Security_Report.pdf`. 9 findings, all fixed.
- **Last fix:** removed invalid Twilio status `'initiated'`, which broke /live, End all and the number-match fallback. Twilio errors now show as "Twilio error CODE: msg" with numbers removed. Latest APK sent in chat (`AgentControl.apk`).
## Decisions
- The app must never store Twilio or ElevenLabs keys. Everything goes through the backend, which is protected by the access key.
- Live must show ALL live calls in the account, including future agents automatically (tested). Non-agent calls show "Unknown agent".
- Everything is in Eastern time. Polling: 3 s while a call is live, 30 s otherwise. Twilio price shows "pending" until posted.
- Permanent signing key at `android/signing/agent-control-release.p12`. Its password is only in the GitHub secret `ANDROID_SIGNING_PASSWORD` and the user's password manager. Never change storage names (`KeepSettingsTest`).
- The user is a beginner: very short, plain steps; never ask them to paste keys into chat.
## Open / Next
- ✅ 2026-11-27: the user re-ran in-app setup with the newest APK (fresh ElevenLabs key, Twilio SID and Auth Token). **The Live tab works on real Twilio** (first real-world confirmation).
- Still to confirm on real data: End all, History (incl. a call's detail page and recording), Usage, Analytics.
- 2026-11-27: the user's ElevenLabs key lacks `user_read` (Usage shows that message). They need to add **User: Read** to the key and, if it's a new key, re-run setup.
- Added (commit d9e2996): recording slider and time, jump ±15 s, speed 1/1.5/2×, tap a transcript line to jump (highlighted while playing), "add a name for this number", History Today/7/30-day buttons and summary line, usage cache cut to 60 s with "updated X ago".
- The user prefers that screenshots stay blocked: ask them to TYPE error messages instead.
- Recommended to the user: make the repo private, turn on 2FA, review the open Dependabot PRs (none merged).
## Gotchas
- From this sandbox, Twilio, the ElevenLabs API/docs, OSV and the GitHub artifact blob host are blocked, and Maven Central rate-limits big files. Robolectric screen tests therefore run only on GitHub Actions (check with `actions_list` / `get_job_logs`).
- The Twilio runtime spreads query/body params over `event.request` (they can overwrite headers). The key check only accepts a plain string.
- The ElevenLabs MCP connector works for reading real conversations (field `metadata.phone_call.call_sid` confirmed).
- GitHub APK builds stop at the signing-secret check until the user adds `ANDROID_SIGNING_PASSWORD`. Tests still run first.
## Key files
- `README.md` (setup, what's exact vs estimated), `SECURITY_REPORT.md`
- `backend/functions/common.private.js`, `backend/functions/live.js`, `backend/test/functions.test.js`
- `android/app/src/main/java/com/agentcontrol/app/{Api,Deployer,SetupActivity,LiveFragment,SecureStore}.kt`
- `.github/workflows/{build-apk,deploy-backend}.yml`
