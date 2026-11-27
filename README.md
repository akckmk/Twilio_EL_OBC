# Agent Control

A dashboard on your phone for your AI calling agents: Merida, Fergus, Eleanor and any agent you add later. These are ElevenLabs Conversational AI agents that call out through your Twilio number.

- **Agents:** a big red **End call** button per agent, and **End all calls**. Same as before.
- **Home-screen widgets:** one tap ends one agent's call, or all calls. Same as before.
- **Live:** every live call as a card, with ringing and talking timers and its own End call button.
- **History:** every past call, with filters, search, and a detail page with summary, transcript, recording and analysis.
- **Usage:** ElevenLabs credits, Twilio balance and spend, and cost per call, agent, day, week and month.
- **Analytics:** charts of calls per day, answer rate, call length, cost by agent and busiest hours, plus outcome counts.
- **Contacts:** names you add by hand, so calls show "Dustin" instead of a number.

## How it fits together

```
Phone app  ──(HTTPS + access key)──►  "agentcontrol" backend (Twilio Serverless)  ──►  Twilio API
                                                                                    └─►  ElevenLabs API
```

- **The phone holds only the backend URL and the access key.** Both are encrypted.
- **Your Twilio and ElevenLabs keys live only in the backend.** The app never sees them. If you used the older version that stored them on the phone, this version deletes them on first launch.

---

## Setup

### 1. Repo secrets (GitHub → Settings → Secrets and variables → Actions → New repository secret)

| Name | What |
|---|---|
| `TWILIO_ACCOUNT_SID` | Twilio Console home page, **Account Info** (starts with `AC`) |
| `TWILIO_AUTH_TOKEN` | Same box; tap **Show** |
| `ELEVENLABS_API_KEY` | See "ElevenLabs key permissions" below |
| `AGENT_CONTROL_KEY` | A random password, 32+ letters and numbers. You type it into the app once. |
| `ANDROID_SIGNING_PASSWORD` | The signing password you were given. It lets updates keep your settings. |

### 2. ElevenLabs key permissions

Create or edit the key at **elevenlabs.io → Developers → API Keys**, with **Restrict Key** on. The backend needs:

| Permission (as the API names it) | What the screen calls it | Used for |
|---|---|---|
| `convai_read` | **ElevenLabs Agents: Read** | Agents, conversations, transcripts, recordings |
| `convai_write` | **ElevenLabs Agents: Write** | Only the last-resort "end on the ElevenLabs side" step |
| `user_read` | **User: Read** | Credits used and left, plan total, reset date |

Leave everything else at **No Access**.

- The permission names come from ElevenLabs' official SDK (v2.80). The on-screen labels may be worded slightly differently.
- **You don't have to guess.** After deploying, the deploy summary, and **Settings → Check backend setup** in the app, list any permission that's missing by its exact name.

### 3. Deploy the backend

1. Go to **Actions → Deploy backend → Run workflow**.
2. The run summary shows the **Backend URL** and a setup check. Every ✅ means that part works; a ❌ says exactly what's missing.

The deploy only reads data afterwards. It never ends calls.

### 4. Install the app

1. Go to **Actions → Build Android APK**, open the newest green run, then **Artifacts**. Or use the APK sent in chat.
2. Open the app and enter the **Backend URL** and **access key**.
3. Updates install over the old version and keep your settings. If you're coming from the older keys-on-phone version, you'll enter the URL and key once.

---

## What's exact, what's an estimate

| Number | Exact? | Where it comes from |
|---|---|---|
| Call status (queued, ringing, in progress, completed, busy, no answer, failed) | **Exact** | Twilio Calls API |
| Talk time of a finished call | **Exact** | Twilio `duration` |
| Ring time of a finished call | **Estimate (±1 s)** | Twilio has no "answered at" field. Ring time = (end − start) − duration. |
| Live talking timer | **Estimate (a few seconds)** | Counts from when ElevenLabs started the conversation. If that's missing, from when the backend first saw the call answered. |
| Live ringing timer | **Exact start** | Twilio `start_time`, when the call was dialed. Ticks on the phone. |
| "Hung up by you" | **Exact for this app's buttons** | The backend records each call it ends. Calls you ended some other way (Twilio console, ElevenLabs) show as Completed or No answer. |
| ElevenLabs credits per call | **Exact** | ElevenLabs conversation `cost` (credits) |
| ElevenLabs dollars per call | **ElevenLabs' estimate** | ElevenLabs `cost_fiat`. It's empty for older conversations. |
| Twilio cost per call | **Exact once posted, shown as "pending" until then** | Twilio fills in a call's `price` some time after the call ends, often minutes and sometimes longer. Until then the app shows **pending** and leaves the call out of dollar totals, and says how many are pending. Twilio's price covers the phone connection only, not extras like answering-machine detection. |
| Credits used / left / plan total / reset date | **Exact** | ElevenLabs `/v1/user/subscription` (`character_count`, `character_limit`, `next_character_count_reset_unix`) |
| Twilio balance | **Exact, may lag** | Twilio Balance API. Twilio says changes may take a while to show. |
| Twilio spend this month | **Exact** | Twilio Usage Records (`totalprice`, this month) |
| Which agent made a call | **Exact, or a close match** | See below |

### How calls are matched to agents
- **Exact:** the backend reads the Twilio call SID ElevenLabs stores on each conversation, in `metadata.phone_call.call_sid`. I confirmed this field on a real call in your account and in ElevenLabs' SDK. Each match is remembered in Twilio Sync, so it doesn't need looking up again.
- **History fallback:** for calls not looked up yet, the backend pairs a conversation with the Twilio call that started closest to it in time. It looks up 8 more exact matches each time the history loads, so the list gets fully exact as you use it. The detail page always checks the exact SID before showing a transcript, so you never see the wrong call's transcript.
- **Live fallback:** live calls fall back to matching the other person's phone number. Anything still unmatched shows as **Unknown agent**.

## How far behind real time is the Live screen?

- **While a call is live:** it asks the backend every **3 seconds**. A change shows up within about **3–5 seconds**: up to 3 s of waiting, plus roughly 1–2 s for the backend to ask Twilio and ElevenLabs.
- **With no call live:** it asks every **30 seconds**, so a new call can take up to about **30 seconds** to appear. Pull down to refresh at once.
- **Timers** tick every second on the phone, using the backend's clock so the phone's clock can't skew them.
- **"Last updated X seconds ago"** shows how fresh the data is.
- **When busy:** if Twilio or ElevenLabs is rate-limiting, the screen keeps the last good data and says so.

**Call notifications** (optional, in Settings) use the same checks. A "call started" notice can be up to about 30 seconds late, and "call ended" up to about 3 seconds late.
- Android requires a small "Watching for calls" notification while this is on.
- Samsung's battery saver may pause it. If notifications stop, set Agent Control's battery use to **Unrestricted**.
- It doesn't restart by itself after the phone reboots. Open the app once.

## Caching and rate limits

- **Backend:** keeps results in memory while Twilio keeps it running:
  - live calls 2 s;
  - history and analytics 60 s;
  - a call's details 10 min;
  - usage 5 min;
  - agents 60 s.
- **Rate limits:** if ElevenLabs answers "too many requests", the backend waits and retries once. If it's still busy, it serves the last good data marked as **stale** instead of failing.
- **Phone:** the history list is saved encrypted, so the History tab opens instantly and refreshes in the background.
- **Limits per request:** history covers up to **92 days** at a time and up to **1,000 calls**. The app tells you if you hit that.

## Privacy

- **Every backend address needs the access key.** That includes transcripts, recordings and phone numbers.
- **The backend never logs** phone numbers, names, transcripts, summaries or audio. Only endpoint names and status codes. A test checks this.
- **Recordings pass through the backend** in pieces. The phone never gets an ElevenLabs key or a signed link. The audio is written to a private temporary file while you listen, and deleted when you leave the call's page (or on the next app start if it crashed).
- **On the phone:**
  - Contacts and the cached history are encrypted with a key kept in the phone's security chip.
  - The app never reads your phone's contacts; it doesn't even ask for that permission.
  - Screenshots and screen recording are blocked.
- **Fingerprint lock** (Settings, optional): asks for your fingerprint or phone PIN each time you open the app. **The home-screen widgets still end calls with one tap and no fingerprint,** because that's their job.
- **Notifications** show the agent and contact on an unlocked phone, and only "Call started" or "Call ended" on the lock screen.

---

## How each part was tested

| Part | How | Result |
|---|---|---|
| Backend endpoints | 29 automated tests (8 of them attacks) run every endpoint against fake Twilio, ElevenLabs and Sync (`backend/test`). They cover the access key, ending calls (unchanged behaviour), live matching and timers, history merging, outcomes, ring and talk time, pending prices, analytics, transcripts, recordings, usage, missing-permission messages, rate-limit fallback, daylight saving, and that nothing private is logged. | All pass |
| App ↔ backend agreement | The backend tests save their real responses; the app's tests read those files and check every field the screens use. | All pass |
| App security | Access key on every request, redirects refused, the app never contains Twilio or ElevenLabs addresses or keys, old keys-on-phone settings are wiped. | All pass |
| Every screen | Robolectric tests open each screen on a simulated phone against a fake backend: Agents (including tapping End call), Live, History, Usage, Analytics, Call details, Settings, Contacts, and the widget's protection. They run on GitHub (Maven Central rate-limited the machine this was built on). | All app tests pass on GitHub (run #9), incl. 4 attack tests |
| Real field names | Twilio fields checked against Twilio's official Node SDK (v6.3.1). ElevenLabs fields checked against ElevenLabs' official JS SDK (v2.80) and one real call in your account. | Confirmed |
| Release APK | Built and signed with your permanent key; not debuggable; Android lint clean apart from wording suggestions. | OK |

### Not built or not tested
- **Not run against your real accounts yet.** The backend has never been deployed with real keys. The first real test is the deploy's setup check.
- **Not tried on a real phone.** Fingerprint prompt, notifications, the audio player and the date pickers were built to Android's documented behaviour, but only a simulated phone ran them.
- **Twilio Functions' response size limit:** recordings are sent in ~1.5 MB pieces to stay well under it. The exact limit couldn't be confirmed here (Twilio's docs were unreachable).
- **The ElevenLabs "end_call" fallback** (used only when Twilio can't find the call) isn't in ElevenLabs' public SDK, so it may not work on every plan.
- **Twilio Sync** is created automatically by the deploy and used to remember matches and calls you ended. It isn't free, but the cost is tiny for this volume. Without it, "Hung up by you" is only remembered briefly.
- **Not built:** importing phone contacts (by design), push notifications from Twilio (they'd need Firebase), and the live screen updating faster than every 3 seconds.

---

## For the curious

```
cd backend && npm test                        # 29 backend tests (also writes the app's contract files)
cd android && ./gradlew testDebugUnitTest     # app tests, incl. every screen on a simulated phone
```

The latest security test (v2, this design) is in `SECURITY_REPORT.md` and `Agent_Control_Security_Report.pdf`.
