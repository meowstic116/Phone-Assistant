## Setup on the phone

For a friend-testing build, follow the [private signing and sharing guide](../DISTRIBUTION.md).
Install only the signed **Pip Tester** APK from the person who invited you. (Old public-key
builds used to be archived under `dist/`; they were removed from the working tree and are
not tester releases.)

1. For local development, `./gradlew installDebug` (or Android Studio Run) installs the
   separate **Pip Dev** app. The first build fetches the pinned, checksum-verified Kokoro
   runtime from npm; the phone downloads the voice model separately only if requested.
2. On first launch, **Meet Pip** guides you into **Connect Pip**. Paste an API key and
   tap **Verify & continue**. Pip sends a short test prompt (no chat or screen data) to
   the configured HTTPS provider and model. You can’t continue until the model replies.
   The defaults point at Gemini (`https://generativelanguage.googleapis.com/v1beta/openai`
   with `gemini-3.5-flash-lite`); use the link to Google AI Studio for a Gemini key or
   enter another OpenAI-compatible endpoint. Existing installs have their saved settings
   prefilled for this one-time check. After setup, you can edit the connection from
   **Pip settings → API & model**. The developer does not collect what you tell Pip or ask
   him to save. Requests go directly to your selected provider and may include chat context
   or visible app text; provider policies apply. Passwords in Android-designated password
   fields and in Pip’s login vault are not sent. Avoid pasting passwords into chat or leaving
   them visible as plain text while Pip is working.
3. In **Make room for Pip**, grant **Display over other apps** to show Pip on your screen.
   **Notifications** for Pip’s status, **Notification access** for media and optional message
   previews, **Microphone**, **Usage access**, and **Accessibility** are optional. Granting the
   microphone does not start listening. Without accessibility you can still chat and use on-screen toys, but Pip can’t
   control other apps (including Doomscroll or in-app playback) or detect their keyboards.
   You can change those grants later in **Pip settings → Permissions**. **Pip settings → Debug options → Diagnostics** shows whether
   accessibility is connected and records the last phone job’s steps.
4. Press **Start assistant**. Open **Pip settings** below that button to change what Pip
   does. Changes are saved and apply without restarting the overlay.

   Settings is a list of controls, not an essay. Each row that needs explaining has a small
   ⓘ button on its right; tap it for that option’s description in a pop-up, and dismiss it
   with **OK**. Rows that need no explanation have no button, and section headings carry the
   longer explanation for the whole section. The permission rows put their explanation on the
   row that names the permission, so it always describes the thing you are about to open in
   Android.

   - **Movement & startup:** Turn off wandering to keep Pip in place between tasks.
     You can still drag him, and he can move for his chat box, keyboard, and toys.
     **Start Pip when the phone starts** is off by default. With **Display over other
     apps** permission, it starts the overlay after the first unlock, not old jobs.
   - **Phone access:** **Let Pip use accessibility** controls phone actions and other apps’
     keyboard tracking without changing Android’s grant. Chat and explicit links don’t need it.
   - **Toys:** Turn off **Allow toys** for no toys, or select individual toys. Turning off
     **Bring out toys on his own** leaves enabled toys available by request only. Pip skips the
     last toy when picking on his own, and waits if there’s no other eligible toy. You can
     still request the same toy yourself — each toy’s switch has an ⓘ button with that toy in
     the same words the toy menu uses. Open the **Painting gallery** below the toy switches,
     and set **Toy sound effects** and **Toy sound volume** there too — **Try a toy sound** plays
     a sample at the current volume.
   - **Personality & data:** Edit Pip’s personality, review his memory, or open his login vault.
   - **Permissions:** Manage notifications, Notification access, overlay, microphone, usage access, and accessibility.
     To turn off notifications while Pip keeps running, open **Manage notifications** and
     turn them off in Android. Android may still list Pip among active apps.
   - **API & model:** Configure the API key, base URL, and model in their own section.
   - **Debug options:** Open **Diagnostics** or choose **Run setup again** to revisit the
     welcome, API verification, and permissions screens. Your data stays in place; changes
     to the API connection are saved only if you finish the walkthrough.

### Questions about your phone

- **“What song am I listening to right now?”** checks Android’s active media controls.
  Enable **Notification access** under **Pip settings → Permissions**. Pip reads the active
  player’s title and artist on-device without sending it to the API or changing playback.
  Paused tracks aren’t reported as playing. Some players don’t share a title.
- **“What did Maya text me?”** reads a matching Android notification preview verbatim using
  an installed offline Android voice. In **Pip settings → Permissions**, turn on **Read
  message notifications** and allow Pip under **Manage Notification access**. You can name
  a messaging app, for example **“What did Maya text me on WhatsApp?”** If Pip can’t read
  the preview, he says so and opens the matching notification’s app (or your default SMS
  app when there’s no match and you didn’t name an app).
  To read text from inside any messaging app, select the message and choose **Share → Read
  with Pip**. If it only supports copying, copy the message and use **Pip settings →
  Permissions → Read with Pip → Read copied text aloud**. Pip doesn’t guess which chat
  bubble belongs to the sender or use the model to search your inbox. Readout requires an
  unlocked phone and doesn’t send the text to the API or save it to chat history. Voice
  requests still need a tap on **Send**. Model-facing screen snapshots skip the default SMS
  app, recognized messaging apps, and apps Pip has seen provide message notifications.
  Pip’s overlay captions are hidden in screenshots and screen sharing because that window
  can show private message previews.
- **“Pip, lock my screen.”** The screen goes dark and locks. This one is done on the phone
  before the model is ever asked, because the reply has to land while the screen is still
  lit — a round trip that returned a clarifying question would leave the phone awake. Pip
  says **“Locking up.”** and the phone sleeps.
  It uses the accessibility service Pip already needs. On Android 9 and newer Android locks
  through that service directly (`GLOBAL_ACTION_LOCK_SCREEN`): no device-admin permission,
  and fingerprint or face unlock still works afterwards. On Android 8 there is no such
  action, so Pip opens Quick Settings and taps a control labelled as a lock; if none is on
  screen he says so rather than tapping something else.
  Recognition is deliberately narrow: **“lock the door”**, **“lock the folder”**,
  **“unlock the screen”**, **“how do I lock my screen”**, and **“remind me to lock the
  screen”** are not lock requests. **“Lock it”** only counts straight after a lock request.
  While driving Pip leaves the screen alone and says he will lock it when you park. Try it
  from **Pip settings → Phone access → Lock the screen now**, and **Diagnostics** reports
  whether locking is ready and what happened last time.
- **“What’s the price shown on this screen?”** can trigger a one-time, read-only screen
  check when Pip’s accessibility access is enabled. Visible app text may go to your
  configured API to answer the question. Pip doesn’t tap, scroll, launch an app, or read
  the notification shade for this kind of check.

### Testing phone jobs

Run `tests/run-job-progress.sh` with `kotlinc` and `java` on your PATH. On a phone with
Pip's accessibility service enabled, try the Strava request above from a feed with
already-kudoed activities at the top and a missed activity below. Look for
`route → verified feed scan` and the per-screen counts in Diagnostics. Try selective Reels
browsing too: `route → selective feed browsing`, one choice at most per screen, and no
likes when it can't identify the control. For a
browser check, ask “Look up all the mods 9 in the browser and open the download page.”
If the first accessibility click doesn't navigate, check for `touch fallback` in Diagnostics.
“Look up pictures of cats” should still stop at Google Images.
