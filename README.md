# Agent Control

One tap on your phone ends one AI agent's live phone call, and leaves the other agents alone.

The app talks straight to Twilio and ElevenLabs. There's no server to deploy and no GitHub secrets to set up.

## Setup (about 5 minutes, all on your phone)

1. **Install the app.** Download the APK and tap it. If Android blocks it, allow installs from that app. If Play Protect warns you, tap **More details**, then **Install anyway**.
2. **Make a Twilio API key.** Open the **Twilio → API keys → Create** link in the app.
   1. Name it `Agent Control`, keep the type **Standard**, and tap **Create**.
   2. Copy the **SID** (starts with `SK`) and the **Secret** into the app. Twilio shows the Secret only once.
   3. Copy your **Account SID** (starts with `AC`) from the Twilio home page into the app.
3. **Make an ElevenLabs API key.** Open the **ElevenLabs → API keys** link in the app.
   1. Tap **Create** and turn on **Restrict Key**.
   2. Set **ElevenLabs Agents** to **Write**. Leave everything else at **No Access**.
   3. Copy the key into the app.
4. Tap **Test and save**. The app checks both keys before saving.

The app has links and these same hints right on the setup screen.

## Using it

- **Main screen:** **End all calls** at the top, then a big red **End call** button for each agent. Pull down to refresh. New agents show up on their own.
- **Widget:**
  1. Long-press the home screen, tap **Widgets**, then **Agent Control**, and drag **End call** onto the screen.
  2. Pick an agent, or **All agents**.
  3. One tap ends that call, and a message pops up with the result, for example "Ended 1 call for Merida."
  - Add one widget per agent. To change which agent a widget controls, long-press it and tap the pencil.
- **Settings:** in the **⋮** menu.

## Security

A full security test was run on 27 Nov 2026; all 12 issues it found are fixed. See [SECURITY_REPORT.md](SECURITY_REPORT.md).

- **Keys stay on the phone.** They're encrypted by the phone's security chip (StrongBox / Knox Vault when available) and only sent to `api.twilio.com` and `api.elevenlabs.io` over HTTPS. Redirects are refused, and the app is excluded from backups.
- **Saved secrets are never shown again,** and the clipboard is wiped after you save.
- **No screenshots or screen recording,** and other apps' floating windows are hidden while the app is open.
- **Release build:** a computer plugged into the phone can't read the app's data.
- **The app never uses your main Twilio Auth Token,** only an API key.
  - **Best:** a *Restricted* key that allows only Voice → Calls.
  - **If your phone is lost:** delete the key in Twilio and ElevenLabs. That instantly cuts the app off.
- **One risk:** anyone using your unlocked phone can end your calls. Keep a screen lock on.

## How ending a call works, and how reliable each part is

**End all calls** asks Twilio for every live call on your account and hangs it up. In-progress calls are set to `completed`; ringing or queued ones are set to `canceled`.
- **Reliability: highest.** It only needs Twilio, so it works even if ElevenLabs is down. But it ends **every** call on the account, including ones not made by an agent.

**End call (one agent):**

1. It asks ElevenLabs for that agent's conversations from the last 6 hours whose status is `initiated` or `in-progress`.
2. **Main route:** for each one, it reads the Twilio call SID from `metadata.phone_call.call_sid` and hangs up that call. This field was confirmed on a real call in this account.
   - **Reliability: high** for calls placed through ElevenLabs' Twilio integration.
3. **Backup route:** if there's no SID, it looks for a live Twilio call between the two numbers ElevenLabs recorded (`agent_number` and `external_number`).
   - **Reliability: medium-high.** It also works while the call is still ringing. In the rare case of two calls between the same two numbers at once, it would end both.
4. **Last resort:** it asks ElevenLabs to end the conversation over its monitoring connection (`{"command_type":"end_call"}`). Then it re-checks, and only reports success if the call really stopped.
   - **Reliability: unverified.** This isn't in ElevenLabs' public SDK, so it may not work on every plan.

If an agent's button says "No live calls found" but you know one is live, use **End all calls**.

## Getting a new build

GitHub tests and builds the APK automatically whenever something in `android/` changes. The build fails if any attack test fails or the APK is debuggable. Go to **Actions**, then **Build Android APK**, then the newest green run, then **Artifacts**.

Each build is signed with a new throwaway key. If Android refuses to install a newer version over the old one, uninstall the old one first, then paste your keys in again.

## For the curious

```
cd android && ./gradlew testDebugUnitTest assembleDebug
```

The tests run the call-ending logic against fake Twilio and ElevenLabs servers. No real calls are made.
