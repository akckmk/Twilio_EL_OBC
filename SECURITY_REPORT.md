# Agent Control: Security Test Report

**Date:** 27 November 2026
**App version tested:** commit `2511b42` (direct-API build), then the patched build in this commit
**Tester:** Claude Code, at the owner's request
**Scope:** the Android app (`android/`), its build pipeline (`.github/workflows/build-apk.yml`) and the repository history.

---

## 1. Summary

The app was tested two ways:

- **White hat:** reviewed the code, configuration and build.
- **Red hat:** acted as an attacker, with a malicious app on the phone, a USB cable, a hostile network or server, and a compromised build pipeline.

**Results:**

- **12 issues were found. All 12 are fixed in this build.**
- The two most serious were:
  - **H-1.** The APK was a *debug* build, so anyone with USB access could read your keys out of it.
  - **H-2.** A server redirect could carry your ElevenLabs key to another server.
- 4 issues were proven with real attack tests that **failed before the fix and pass after it**. Those tests now run on every build.
- **No keys were found** in the APK or anywhere in the git history.

**The one important thing left for you to do** is choose the narrowest Twilio key type you can (see section 5, R-1).

| Severity | Found | Fixed |
|---|---|---|
| High | 2 | 2 |
| Medium | 4 | 4 |
| Low | 6 | 6 |

---

## 2. What was tested and how

| Method | Tool / approach |
|---|---|
| Static code scan | `mobsfscan` 1.2.0 (OWASP MASVS rules), Android Lint (release) |
| APK inspection | `aapt2` (final manifest, permissions, exported parts), `apksigner` |
| Secret scan | Searched the APK contents and all git history for Twilio (`AC…`, `SK…`) and ElevenLabs key patterns, and for the old access key |
| Attack simulation | 5 automated red-team tests (`AttackTest.kt`) running the real network code against a fake hostile server |
| Function tests | 8 tests (`CallControlTest.kt`) proving the right calls, and only those, get ended |
| Manual review | Every exported screen, the widget, key storage, input fields, errors, the CI workflow |

**Limits.** There's no phone emulator in this environment, so the app wasn't driven on a real device. On-device behaviour was confirmed from the built APK and the Android documentation, not by tapping through it. Twilio and ElevenLabs themselves weren't tested; they are out of scope.

### Attack models

1. **A malicious app installed on the same phone.**
   - It can open any screen the app makes public.
   - It can draw over the screen.
   - It can read the clipboard.
2. **Someone who borrows the phone, or plugs it into a computer.**
3. **A hostile network or server**, for example rogue Wi-Fi, a bad redirect, or a compromised API reply.
4. **A supply-chain attacker** who tampers with the build tools or GitHub Actions.

---

## 3. Findings and fixes

### H-1. App shipped as a debug build (High), fixed
- **Attack:** with the phone unlocked and USB debugging on, `adb shell run-as com.agentcontrol.app` reads the app's private files. A debugger can also attach and read the decrypted keys from memory.
- **Evidence:** `aapt2` showed `android:debuggable=true` in the APK.
- **Fix:**
  - The app now ships as a **release** build (`isDebuggable = false`), with unused code removed (APK went from 10 MB to 1.8 MB).
  - The build pipeline now **fails if the APK is ever debuggable**.

### H-2. Redirects could leak the ElevenLabs key (High), fixed
- **Attack:** a compromised or spoofed API reply sends `302 Location: https://attacker`. The app followed it and sent `xi-api-key` along. The network library only strips the standard `Authorization` header on redirects, not custom ones.
- **Evidence:** `elevenLabsKeyIsNotLeakedByRedirect` failed before the fix, because the attacker server received the request.
- **Fix:** redirects are switched off completely (`followRedirects(false)`, `followSslRedirects(false)`). A redirect now stops with the message "unexpected redirect. Nothing was done."

### M-1. Twilio requests also followed redirects (Medium), fixed
- **Evidence:** `twilioKeyIsNotLeakedByRedirect` failed before the fix. The Twilio key header itself was stripped, but the request still went to the other server.
- **Fix:** same as H-2.

### M-2. Poisoned call ID could make the app write to other Twilio endpoints (Medium), fixed
- **Attack:** a tampered Twilio reply lists a call with `sid = "../../IncomingPhoneNumbers/PN1"`. The app then POSTed to `/Accounts/IncomingPhoneNumbers/PN1.json`, an unrelated Twilio resource.
- **Evidence:** `poisonedCallSidIsIgnored` failed before the fix.
- **Fix:** every call ID must match `CA` plus 32 hex characters before it's used. Anything else is dropped.

### M-3. Poisoned conversation ID could steer ElevenLabs requests (Medium), fixed
- **Attack:** a conversation ID like `../../agents/x?delete=1` changed which address the app requested.
- **Evidence:** `poisonedConversationIdIsIgnored` failed before the fix.
- **Fix:**
  - IDs must match `[A-Za-z0-9_-]{1,100}`.
  - IDs are added to addresses as a single, encoded path segment, never pasted into the text of the address.

### M-4. Saved secrets were shown again in Settings (Medium), fixed
- **Attack:** someone holding the unlocked phone opens Settings, taps the eye icon, and copies your Twilio secret and ElevenLabs key.
- **Fix:**
  - The two secrets are **never shown again** after saving. The box says "Saved (hidden). Leave empty to keep."
  - The two IDs (Account SID and key SID) are still shown, because they aren't secret on their own.

### L-1. Screenshots and Recent-apps previews (Low), fixed
- **Fix:** every screen sets `FLAG_SECURE`. That blocks screenshots, screen recording, casting, and the preview in Recent apps.

### L-2. Tapjacking, where an overlay tricks you into tapping "End all" (Low), fixed
- **Fix:**
  - Other apps' floating windows are hidden while Agent Control is open (`setHideOverlayWindows`, Android 12 and later).
  - Every button ignores taps while something covers it (`filterTouchesWhenObscured`).

### L-3. Another app could open the widget-setup screen to retarget your widget (Low), fixed
- **Attack:** a malicious app opens the setup screen with the number of your "Merida" widget. If you tap "All agents", that widget would end every call.
- **Fix:** the screen now refuses any widget number that doesn't belong to Agent Control's own widget. It's also hidden from Recent apps.

### L-4. Task hijacking (StrandHogg) (Low), fixed
- **Fix:**
  - Every screen now sets `taskAffinity=""`, so a malicious app can't slip its own screen in front of Agent Control's.
  - The minimum Android version is raised to 10, which carries the system-level fix.
  - Your S26 is far above that.
- **Note:** `mobsfscan` still prints `android_task_hijacking2`. That's a false alarm: the scanner reads the target Android version from the source manifest, where it isn't set, so it assumes Android 8. The real APK targets Android 15 (SDK 35), confirmed with `aapt2`.

### L-5. Keys left on the clipboard and in keyboard memory (Low), fixed
- **Fix:**
  - The clipboard is wiped after a successful save.
  - Every key box tells the keyboard not to learn what's typed (`flagNoPersonalizedLearning`, no suggestions, no autofill).

### L-6. Supply chain: build actions pinned by tag only (Low), fixed
- **Fix:**
  - GitHub Actions are pinned to exact commit hashes.
  - `gradle/actions/wrapper-validation` now checks that the Gradle wrapper is genuine.
  - Checkout no longer keeps the GitHub token (`persist-credentials: false`).
  - The workflow is read-only.

### Extra hardening (not tied to a finding)

- **Encryption key in the security chip:** it's now created in **StrongBox** (Samsung Knox Vault, a separate security chip) when available, falling back to the normal Keystore.
- **Network rules:**
  - HTTPS only (`cleartextTrafficPermitted=false`).
  - Only Android's built-in certificate authorities are trusted. A certificate a user or app installs can't be used to spy on traffic.
- **Error messages:** generic errors no longer include internal exception text. A dedicated test checks that on-screen errors never contain a key.

---

## 4. Checked and found safe

| Area | Result |
|---|---|
| Secrets in APK or git history | None found |
| Key storage | AES-256-GCM, key never leaves the Keystore or StrongBox, new random IV each time |
| Backups | Off (`allowBackup=false`, cloud and device-transfer backups exclude app data) |
| Exported parts | Only the launcher and the widget-setup screen, which Android requires; both now validated. The widget receiver isn't exported. The one AndroidX receiver is protected by the system-only `DUMP` permission. |
| Widget tap intent | Explicit and immutable (`FLAG_IMMUTABLE`), so other apps can't change it |
| Logging | The app logs nothing; no HTTP logging |
| Where keys go | Only `api.twilio.com` and `api.elevenlabs.io`, over TLS 1.2 or higher |
| Agent names from ElevenLabs | Shown as plain text, never as HTML, so they can't inject anything |
| Permissions | `INTERNET` and `HIDE_OVERLAY_WINDOWS` only. No contacts, phone, mic or location access. |

---

## 5. Remaining risks and recommendations

**R-1. Twilio key scope (your action, most important).**
- A **Standard** Twilio API key can do almost anything on your Twilio account, not just end calls.
- **Best:** if Twilio offers a **Restricted** key type when you create the key, choose it and allow only **Voice → Calls** (read and update). The app needs nothing else.
- **Good:** if only Standard is offered, it's acceptable. Never put your main Auth Token in the app; the app doesn't ask for it.
- **If your phone is lost:** delete the key in Twilio and in ElevenLabs. That cuts the app off instantly.

**R-2. ElevenLabs key scope.**
- "Agents: Write" is only needed for the last-resort "end on ElevenLabs" step.
- If you prefer, choose **Read**. Everything else still works.

**R-3. Unlocked phone.**
- Anyone using your unlocked phone can tap **End call**. That's what one tap means.
- Keep a screen lock on.
- A fingerprint check before ending calls could be added, but it would make the widget two steps.

**R-4. Signing key.**
- Each build is signed with a throwaway key. That doesn't weaken your keys' protection, but it means:
  - **Only install APKs from your own repo's Actions page, or from this chat.**
  - An update may need uninstalling the old version first.

**R-5. Not added, by choice.**
- **Certificate pinning:** Twilio and ElevenLabs rotate their certificates, and a pinned app would suddenly stop working. System-CA-only trust plus HTTPS-only is the safer trade-off.
- **Root detection or Play Integrity:** these mainly protect servers from tampered clients. This app has no server, and a rooted attacker could bypass them anyway.

**R-6. Gradle download checksum.**
- The Gradle distribution is fetched over HTTPS but has no pinned checksum. The checksum site was blocked here.
- Adding `distributionSha256Sum` to `gradle-wrapper.properties` is a small future improvement.

---

## 6. How to re-run these tests

```
cd android
./gradlew testReleaseUnitTest   # 13 tests: 8 function tests + 5 attack tests
./gradlew lintRelease
```

GitHub Actions runs all of these on every change, and refuses to publish a debuggable APK.
