# Agent Control

One tap on your phone ends one AI agent's live phone call, and leaves the other agents alone.

- **backend/**: a small Twilio Serverless service named `agentcontrol`.
- **android/**: the "Agent Control" Android app, with a home-screen widget.
- **.github/workflows/**: one workflow deploys the backend, the other builds the APK.

---

## 1. Make the four secrets

You need these four values. Nobody types them into code or chat. They only go into GitHub's secret store.

| Secret name | Where to get it |
|---|---|
| `TWILIO_ACCOUNT_SID` | [Twilio Console](https://console.twilio.com) home page, under **Account Info**. It starts with `AC`. |
| `TWILIO_AUTH_TOKEN` | Same box, next to it. Click **Show** to reveal it. |
| `ELEVENLABS_API_KEY` | Make a restricted key, see below. |
| `AGENT_CONTROL_KEY` | A long random password you make yourself, see below. |

### ElevenLabs key with only the permissions it needs

1. Go to [elevenlabs.io](https://elevenlabs.io), then **Developers** (left menu), then **API Keys**, then **Create Key**.
2. Name it `agent-control`.
3. Turn on **Restrict Key**.
4. Set **ElevenLabs Agents** (it may be called *Conversational AI*) to **Write**. Leave everything else at **No Access**.
   - Read is enough to list agents and find calls. Write is only needed for the last-resort "end it on the ElevenLabs side" step. If you'd rather not grant Write, choose Read; everything else still works.
5. Optional: set a low credit limit, since this key never generates audio.
6. Copy the key. ElevenLabs shows it only once.

The menu names above are from memory. ElevenLabs renames things now and then, so look for the closest match.

### The access key (`AGENT_CONTROL_KEY`)

This is the password the app sends with every request. It must be at least 32 characters, using letters and numbers only. Ways to make one:

- **Password manager:** generate a 48-character password with symbols turned off.
- **Mac Terminal:** `openssl rand -hex 32`
- **Windows PowerShell:** `"$([guid]::NewGuid())$([guid]::NewGuid())" -replace '-',''`

Save it in your password manager. You'll paste it into the app on your phone once.

### Add the secrets to GitHub

1. Open this repo on github.com.
2. Click **Settings** (top bar of the repo). If you don't see it, tap the **⋯** menu.
3. In the left menu, click **Secrets and variables**, then **Actions**.
4. Click **New repository secret**. For **Name** type `TWILIO_ACCOUNT_SID`, paste the value into **Secret**, then click **Add secret**.
5. Repeat for `TWILIO_AUTH_TOKEN`, `ELEVENLABS_API_KEY` and `AGENT_CONTROL_KEY`.

---

## 2. Deploy the backend

1. In the repo, click the **Actions** tab.
2. Click **Deploy backend** on the left.
3. Click **Run workflow**, then the green **Run workflow** button.
4. When it turns green, click the run. The summary at the bottom shows:
   - your **Backend URL**, for example `https://agentcontrol-1234.twil.io`. You'll type this into the app.
   - the results of `/agents` (your agents) and `/end?agent=all`.

> ⚠️ **Heads-up:** the deploy ends with a test call to `/end?agent=all`. That hangs up **every live call in your Twilio account**. Only run the deploy when no agent is on a call. This is why it only runs when you click the button, never on its own.

Each deploy replaces the previous one, and the URL stays the same.

---

## 3. Build and install the app

### Build it

The APK builds automatically whenever something under `android/` changes. To build it by hand: **Actions**, then **Build Android APK**, then **Run workflow**.

### Install it on your phone

1. On your phone, open **Chrome** and sign in at github.com.
2. Go to this repo, then **Actions**, then **Build Android APK**, then the newest green run.
3. Scroll to **Artifacts** and tap **AgentControl-apk**. A `.zip` file downloads.
4. Open **My Files**, then **Downloads**. Tap the zip and choose **Extract**.
5. Tap the `AgentControl-….apk` file inside.
6. If Android says it's blocked, tap **Settings**, turn on **Allow from this source** for **My Files**, then go back and tap **Install**.
7. If Play Protect warns you about an unknown app, tap **More details**, then **Install anyway**. It warns because you built the app yourself, not because something is wrong.

**Updating later:** each build is signed with a new throwaway key, so Android may refuse to install it over the old one ("App not installed"). If that happens, uninstall Agent Control first, install the new one, then type the URL and key in again.

### First launch

1. Open **Agent Control**.
2. Type in the **Backend URL** from step 2 and paste your **access key**.
3. Tap **Test and save**. It checks the connection before saving. Both values are stored encrypted using the phone's secure hardware (Android Keystore).

### Main screen

- **End all calls** at the top hangs up every live call in your Twilio account.
- Below it there's one card per agent, each with a big red **End call** button.
- Pull down to refresh the list. New ElevenLabs agents show up on their own.
- Use the **⋮** menu to get back to Settings.

### Home-screen widget

1. Long-press an empty spot on the home screen and tap **Widgets**.
2. Find **Agent Control** and drag **End call** onto the screen.
3. A list pops up. Pick an agent, or **All agents**.
4. Tap the widget to end that agent's call. A toast shows the result, for example "Ended 1 call for Merida."

Add as many widgets as you like, one per agent. To change which agent a widget controls, long-press it and tap the settings (pencil) icon.

---

## How the backend works

Every request must carry the header `X-Agent-Control-Key: <your access key>`. Anything without the right key gets `401`. The server also refuses to run at all if the key is missing or shorter than 32 characters. The key goes in a header, never in the URL, so it doesn't end up in logs.

| Route | What it does |
|---|---|
| `GET /agents` | Lists your ElevenLabs agents (`id`, `name`), read live from `GET /v1/convai/agents`. |
| `GET /end?agent=<agent_id>` | Ends only that agent's live calls (details below). |
| `GET /end?agent=all` | Ends every live call in the Twilio account: `in-progress` calls are set to `completed`, `ringing` or `queued` ones to `canceled`. |

Every response is JSON with `ok`, a plain `message` like `"Ended 1 call for Merida."`, and the details of each call it touched.

How `/end?agent=<id>` finds the calls:

1. It asks ElevenLabs for that agent's conversations from the last 6 hours (`GET /v1/convai/conversations?agent_id=…`) and keeps the ones whose `status` is `initiated` or `in-progress`.
2. For each one it reads `GET /v1/convai/conversations/{id}`. The Twilio call SID is at **`metadata.phone_call.call_sid`** (with `metadata.phone_call.type` = `"twilio"`). I confirmed this on a real call in your account, and the same SID also appears as `system__call_sid` in the dynamic variables, which is used as a backup.
3. **Route A:** hang up that call SID through `context.getTwilioClient()`.
4. **Route B** (no SID, or Twilio doesn't know it): look for a live Twilio call between the two numbers ElevenLabs recorded (`agent_number` and `external_number`) and end it.
5. **Route C** (still nothing): ask ElevenLabs to end the conversation itself by sending `{"command_type":"end_call"}` over its conversation-monitoring WebSocket. Then it re-checks the conversation status, so it only reports success if the call actually stopped.

### How reliable is each route?

| Route | Reliability | Why |
|---|---|---|
| `/agents` | **High** | Reads ElevenLabs' agent list directly. |
| `/end?agent=all` | **Highest** | Talks to Twilio only, so it works even if ElevenLabs is down. But it ends **every** call on the account, including any that aren't from agents. |
| `/end?agent=<id>`, Route A (call SID) | **High** for calls placed through ElevenLabs' Twilio integration | The field is confirmed on your real calls. It depends on ElevenLabs marking the conversation as live, which may lag by a second or two right at the start of a call. |
| Route B (number match) | **Medium-high** | Works while the call is still ringing. In the rare case two calls run between the same two numbers at once, it would end both. |
| Route C (ElevenLabs `end_call`) | **Unverified** | Not in ElevenLabs' public SDK (checked v2.80), and I couldn't read their docs from where this was built. It may need a feature your plan doesn't have, or the Write permission. The JSON tells you honestly whether it worked. |

Other limits:

- Calls that started more than 6 hours ago aren't searched. Use **End all** for those.
- Twilio stops a function after 10 seconds. Normally `/end` finishes in 1–3 seconds.
- If an agent's button says "No live calls found" but you know a call is live, use **End all calls**.

---

## For the curious: running the tests

```
cd backend && npm test        # fake ElevenLabs/Twilio, no network, no real calls
cd android && ./gradlew assembleDebug
```
