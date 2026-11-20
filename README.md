# Phone-Assistant

A little phone-shaped character named Pip that walks around your Android screen, over
your other apps, and talks back. He also puts things on the screen and plays with them
with you — a lemonade stand, a canvas, a soccer pitch, bubble wrap, a plant.

## What it does

- A floating character drawn entirely in code (no sprite sheets) that wanders the whole
  screen, blinks, sits down, and reacts. Pip pops into a cyan glow when started and
  folds into a tiny spark when stopped from the app or notification. Stopping still
  cancels any phone job immediately, and Android's animation-off setting is respected.
- Pip reaches out to bring his toys onto the screen: the lemonade stand slides in, the
  canvas unfolds, the soccer ball spins into place, bubble wrap flutters, and the plant
  bounces. When he tidies up, the pieces fold or shrink back toward his hand. A prop lifts
  slightly under your finger while you drag it, and the speech bubble eases in, then drifts out. A line stays up for as long as it takes to read.
  Drag Pip or a toy and it follows your finger, with a short glide on release if you
  were still moving. Pause before lifting to leave it exactly where you put it.
- He is drawn no more often than there is something to see: a walk gets every frame, the
  idle sway twenty a second, and nothing at all while the screen is off. Drawing the same
  small body sixty times a second to show a 1dp bob was the largest fixed cost on the overlay.
- And nothing sits still once it is down: the stand's sign swings on its nail, the plant
  leans toward wherever he is standing, the painting keeps catching the light as the paint
  dries, and the bubble wrap sheet glints between pops. None of it redraws while it isn't
  moving, so an idle screen stays cheap.
- Tap it to open a small input box under his feet. He stops to listen, shows animated
  thinking dots, then answers in a speech bubble that follows him around and flips below
  him near the top of the screen. The box leaves as soon as you send, or when you press
  back. He steps straight up clear of the keyboard using its measured height, not a guess —
  and he keeps clear of a keyboard you open in any other app too — clearing the row above it
  as well as the keyboard, so the box you are typing in stays visible instead of being stood
  on — because a small accessibility service reports where the keyboard window is. That report is the only place
  the keyboard is measured, and a toy he puts out while you are typing is placed above it
  rather than in the middle of your words. Without the service switched on he still steps
  clear of his own chat box, but not of anything else's keyboard.
  Replies come from any OpenAI-compatible API.
- A **Chat history** screen shows the whole conversation and lets you keep talking from
  there; replies still appear in his overlay bubble, which floats above the chat.
- A **Personality** screen shows the full prompt that defines him, and lets you rewrite it.
- A **Memory** screen lists the facts he keeps between conversations, and lets you add or
  delete them.
- The **Logins** screen stores entries encrypted on the phone. Pip pauses at sign-in
  instead of autofilling a form it cannot verify. You can reveal an existing password
  after confirming your device lock, or sign in through Android Autofill. If the vault
  can't be opened, Pip leaves it untouched. At capacity, adding a new site requires
  deleting an entry first; older entries aren't automatically removed.
- Ask him to **stand still** and he stops where he is: "stand still for two minutes" for
  that long, or just "stand still" for as long as you like — until you tell him he can move
  again. He is still there to talk to, and the hold is kept on the phone like a toy break, so
  a restart does not lose it.
- A **Diagnostics** screen shows, live, exactly what Android tells the app rather than what
  it should: the keyboard height the accessibility service reports, whether that service is
  switched on,   whether a keyboard is up, where he is sitting against it, how many frames a second the
  character is really drawing (and whether he is drawing at all — the screen-off pause and
  the idle rate are both visible there, measured in `PetView.onDraw` rather than assumed),
  what is on the stage, the last phone job step by step — what was asked for, its recent actions,
  whether Android took that action, and why the job stopped — and the last two failures: the
  most recent refused request and the most recent exception that was swallowed to keep the
  overlay alive. Nothing is written to disk or sent
  anywhere. A **floating readout** can be turned on from that screen: a small panel, draggable
  and closable with its ×, that stays over other apps so the keyboard height and where he is
  against it can be watched live while you type somewhere else. (The panel is one of his own
  windows, so it needs him running.)
- **Phone jobs.** Ask him to do something in another app and he gets on with it: "open
  Messages and tell Mum I'm late", "search for the parcel number", "scroll down and tap the
  third one", "turn do not disturb on". He reads the app in front as a short numbered list of
  the things that can be pressed, typed into or scrolled, picks one step, does it, and looks
  again. General jobs have no fixed step or elapsed-time limit. Pip keeps going while he can
  verify progress and stops after repeated failures or when the task is complete.
  A shared feed runner can apply missing
  reactions across apps — for example, **“Please open Strava and give kudos to every
  activity that I haven't given kudos to yet,”** **“Give kudos on any Strava activity I haven't
  yet,”** or **“Open Instagram and like every post I
  haven't liked yet.”** Pip reads the reaction controls Android exposes, skips already-applied
  reactions, and counts a press only after verifying that the control changed. Screens
  with no recognizable controls or only already-applied reactions don't end the scan:
  Pip keeps going while he can reach new screens. Feed jobs swipe up in the app window
  instead of using the generic accessibility scroll, which can move carousels sideways.
  List swipes overlap the previous screen, and Diagnostics shows what moved and how long
  verification took. An uncertain kudos doesn't end the scan if Pip can still identify the
  feed; he skips that attempt and reports only confirmed reactions. Use **Stop** to end a long scan.
  For something more selective, try **“Doomscroll Instagram Reels and like any you want.”**
  Pip may pick one like per screen or skip it. He judges only the captions and labels Android
  exposes, not the video or audio. **“Doomscroll Instagram Reels for me”** browses without
  reacting. Optional likes can be a separate sentence: **“Open Instagram and doomscroll for
  1 minute. You may like reels you like.”** Instagram doomscrolling defaults to Reels,
  not Home; Pip waits for evidence that the requested tab or player opened. If he can't
  read a doomscroll or bulk kudos instruction, he can ask the configured API for a suggested
  rephrasing. He waits for you to send it before acting. If a tab is slow to confirm, the API
  may suggest one extra read, not another tap. These requests send the original feed command
  for clarification or the app/tab and obstacle for recovery, not the screen tree or chat history.
  Say **“Doomscroll Instagram Reels for 2 minutes”** or **“Doomscroll 10 reels on
  Instagram”** to set a time or count. The timer starts when the feed opens. A count means
  distinct reel screens Android exposes, not full videos played. Pip skips disclosed
  Instagram ads without waiting, liking, or counting them toward the limit.
  With neither limit specified (including “for a little bit”), Pip picks a random duration
  from 30 seconds to 5 minutes. Pip waits for an exposed playback end or loop before
  deciding whether to like and swipe. With accessible playback data, he checks roughly
  four times a second to catch the restart. If playback isn't exposed, he pauses for about 12
  seconds instead. Missing or conflicting playback readings aren't treated as a loop.
  Changing captions or counters alone don't prove that the next reel
  opened. If the reel is hard to identify but the viewer is still readable, Pip can finish
  the pause and swipe without liking it. The session timer and Stop take priority.
  He may stop sooner if the feed stalls, and **Stop** works at any time. If Pip can't find
  the requested tab, open it in the app and retry. If a request names an app, Pip opens it before his first step
  unless you're already in it, in which case he leaves your
  screen alone — so the job happens in front of you rather than somewhere behind   it, and he moves into the bottom corner so he is not covering it. He is given the list of apps really
  installed on the phone, so "open WhatsApp" reaches that WhatsApp and not a guess.
  “What's the weather like today?” opens a launchable Weather app without asking the
  chat API. If no Weather app can open, or if you name a city, Pip opens a forecast
  search in the browser. This doesn't require accessibility or an API key.
  “Play the Emo Rap Mix playlist on Spotify” checks the visible Home/Recently Played
  cards for that exact title before asking the model to search. It starts playback locally
  when Spotify exposes the playlist's own play control. Diagnostics show how long phone-job
  model calls took, separately from the time spent on the phone.
  “Message Sam and say hello” and “Call Sam” use Android's default SMS and Phone apps.
  Name another app explicitly to use it instead, e.g. “Open Discord and message Sam.”
  App names inside the message body don't change the selected app.
  Looking something up is one action, not a browser driven by hand: the query goes to
  whichever browser Android has been told to use — yours, wherever you set it — as a search
  URL, and "take me to the images" is the pictures page of it. An exact request such as
  **“Open https://example.com”** goes directly to Android without a model call. If you also ask Pip to open
  a result, he checks for actual page content after the accessibility click. If the same
  title is still in the same spot on the same page, he can try one touch on that title.
  He stops rather than keep cycling through unchanged results. A browser's search box
  cannot be typed into usefully, because submitting it needs an enter key the accessibility
  service has no way to press. Pip uses a search URL instead. A `wait` step really waits, rather than re-reading
  the same loading screen and asking to wait again. While a
  job is running there is a small **Stop** chip in the bottom left corner, over whatever app
  he is working in — a corner rather than the middle of the bottom edge, which is where a job
  is most likely to need to press: one tap ends the job where it stands, and the record on
  Diagnostics says it was cancelled. Drag the chip anywhere you like and it stays there, for
  this job and the next one; it lifts above the keyboard when one comes up, so it is never
  behind the app. Pip and Stop stay visible during accessibility clicks and scrolls, which go straight to the
  app without going through Pip's windows. Pip parks in the bottom right and Stop stays in
  its usual corner. For a simulated touch, only an overlay in the actual touch path moves
  aside; Pip and Stop dim rather than disappear even if the touch crosses them. An
  overlapping Stop chip is briefly untappable until Android finishes the touch. If an
  overlapping window can't move aside, Diagnostics marks the attempted gesture **SKIP**.
  An action already dispatched can't be undone, but Stop or a newer message prevents later
  actions. Before touching a control Pip rechecks that its window and target are still current.
- Drag it anywhere. It lives in a foreground service with a persistent notification.

## Toys

Everything below is real, movable screen furniture. Drag any of it wherever you want and
tap it to use it. Each piece is its own small overlay window, so it never blocks the app
underneath, and anything that ends up under the chat box is nudged clear of it. Bring them
out from the separate **Toys** menu in the app, ask him for one in chat ("open your lemonade
stand", "paint something", "let's play soccer"), or just leave the screen alone — he gets
bored every few minutes and starts something himself.

**Pip settings → Painting gallery** lets you turn individual paintings on or off. Pip
can paint his original subjects plus three crayon scenes: a space kitten, a frog in a
teacup, and a ghost holding a daisy. Use **Add pictures** to copy up to 24 images from
Android's picture picker. These copies are resized and stay on the phone; neither the
images nor their filenames are sent to the AI. **Remove** deletes Pip's copy without
changing the original. Only enabled pictures are picked for a new canvas.

He puts things away when you ask ("put the plant away", "pack the lemonade away", "tidy
up"), and also on his own: an untouched toy goes away after about 15 seconds. Tapping
or playing with it keeps the whole toy out for about one minute after your last tap.
Dragging doesn't reset the timer, but Pip waits until you let go. A soccer pitch or
lemonade stand goes away as a set, not one piece at a time.

He can be sent on a break too: "no toys for half an hour", "take a break for 20 minutes",
"not now" (which means 15 minutes), or "leave me alone for the evening" (8 hours). He puts
everything away, sulks about it, and gets nothing out until the time is up. The break is
saved on the device, so it survives restarting him, and he knows exactly how much of it is
left — ask him and he will tell you, with feeling. When the time runs out he says so in his
bubble, from a local list of lines, so it costs no API call and feels like he was actually
waiting. The timer is checked against the real deadline the moment the screen comes back on,
so a phone asleep through the end of a break still gets told about it immediately rather than
whenever the sleeping timer wakes up, and an announcement he never got to make is remembered
on disk — if Android kills the service mid-break he still says it when he comes back. The **Toys** menu can start one too — **Give Pip a break** offers 15 minutes, 30, an hour or two —
and while a break is on it shows a live countdown next to an **End break** button that cuts
it short and gets him talking again. "You can play again" does the same from chat. Phrases only count when they are aimed at him, so "I need a break
from work" leaves the lemonade stand alone.

Open **Toys** from the main screen to see a description and play button for each toy.
The same menu has **Tidy up**, break controls, and a shortcut to Toy settings. A toy that
is disabled stays in the catalog, with a note explaining why it can’t be launched.

- **Doomscroll** — tap the toy button (or say “doomscroll”) to open Instagram Reels for
  a random 30 seconds to 5 minutes, with no automatic likes. You can still ask for a
  particular time or reel count in chat. Pip leans in and follows the reel with his eyes,
  then flicks his hand up as he swipes. With **Bring out toys on his own** enabled, Pip
  may also pick Doomscroll at random, but only when he can verify the unlocked launcher’s
  Home workspace is visible with no other app or panel open. Unrecognized launcher layouts
  are skipped. Manual Doomscroll does not need to start on Home. This toy needs accessibility;
  **Stop**, **Tidy up**, or turning the screen off ends the session.
- **Lemonade stand** — a mint counter, scalloped pink canopy, and a smiling lemon badge,
  with matching glasses and bendy straws. Tap the stand and he pours you a glass, up to
  two at a time. The glass is its own prop: tap it once to drink, tap the empty glass
  again to hand it back. Ask him for "a lemonade" in chat and the glass is on the screen
  at once: he pours first and hurries round behind the counter with it, rather than making
  you wait out the crossing. One request makes exactly one glass: the debt is cleared
  before each pour and capped at the stand's two glasses, so a second pour can never double
  it. Ask twice quickly and the second glass arrives about a second after the first,
  silently — one serve line per pair, not the same line twice. The counter is drawn
  over his hips and feet — it starts 6dp below his chin — with the sign and the awning
  clear of him, so he stands behind the stand rather than in front of it. Tapping him
  while he is back there still talks to him; tapping the counter pours a glass. Drag the
  stand and he walks back round behind it at the new spot, says something about it and
  keeps station there — the stand holds a post he will not wander away from — until it is
  taken off the screen.
- **Canvas** — he walks somewhere, sits down with folded legs, and paints for about seven
  seconds while the picture fills in brush-stroke by brush-stroke. There are five paintings,
  all drawn in code: a dog in a tiny hat who owes him twenty bucks, Pip as a hundred-foot
  city-stomping monster, a crowned sandwich with one small patient person beside it, a
  toaster in sunglasses plugged into a lemon, and a fish in a space helmet planting a flag
  on the moon. When he is done he gets up, says what it is called, and wanders off leaving
  it on the screen. Tap the painting to hear his artist's statement, or ask him about it.
- **Soccer** — goals at the top and bottom of the pitch, a ball in the middle, and a score
  pill. If a keyboard is showing when the game starts, the pitch fits above it rather than
  putting his goal behind it, and it re-fits once that keyboard has gone — but only if the
  ball is provably untouched, since a goal line must never move under a ball in play. Tap the ball to
  boot it at Pip's goal (tap left or right of the middle to angle it);
  he walks over and kicks it back at yours. First to three or ninety seconds, and he
  announces the result either way. The goals are draggable, which he notices: he calls it
  cheating (cheerfully), and three goalpost moves and he calls the game off.
- **Bubble wrap** — A compact sheet of 20 candy-colored bubbles with confetti. Tap any
  bubble, or watch Pip hop onto a random unpopped bubble, pausing briefly between hops.
  He has his own milestone lines and clears the sheet
  after the last pop. An active round can finish even if the normal toy timer runs out.
- **Plant** — Grow a flower together. Pip spaces out his pours, and you need to tap the
  pot to water it at least once before it can bloom. Fast taps still get a warning. A finished bloom gets a shimmering nameplate beneath
  its pot and a ladybug visit. Open **Toys → Plant Collection** to see all ten
  regular varieties and how many times each has reached full bloom. First blooms get a
  special color and animation. Once all ten are discovered, each new pot has a 1 in 10
  chance of growing the rare **Golden Bloom**, which only then appears in the collection.
  Existing bloom counts carry over.

## Pip’s voice

In **Pip settings → Voice**, **Kokoro** is the default for new voice setups, with the
male **Adam** preset. Tap **Download Kokoro** (about 110 MB from GitHub), then **Preview
Pip’s voice**. Turn on **Read Pip’s replies aloud** if you want spoken replies; it’s off
by default. **Choose Pip’s voice** also offers Michael and Bella. Kokoro is English-only:
it runs on your phone after setup, with no speech API key, server, or per-use cost. You
can remove the download from Voice settings. An existing explicit voice choice stays selected.

[Hosted VoxCPM2 on Wiro](https://wiro.ai/models/openbmb/voxcpm2) is still optional and
requires a Wiro project key and balance, including for previews. If you run a [VoxCPM2
server](voice-server/README.md), select **Custom VoxCPM2 server** and enter its HTTPS
`/v1/audio/speech` URL and token. Both VoxCPM2 choices support voice style instructions.
**Offline Android voice** still uses an installed system voice and is never a fallback.
Remote speech providers receive ordinary reply text only, not private phone results.

The floating bubble reveals words during playback without resizing. Android engines may
report word ranges; Kokoro and VoxCPM2 use estimated timing. If audio is unavailable,
the full reply is still shown. Kokoro runs in a separate app process so a voice-engine
failure doesn’t restart the overlay. If that process stops, Pip pauses automatic Kokoro
speech for the session; a successful **Preview Pip’s voice** resumes it. The last voice
issue appears in **Debug options → Diagnostics**, without recording any spoken text.
Kokoro’s runtime is bundled for arm64-v8a, armeabi-v7a and
x86_64. Its weights are not bundled with the APK or committed to Git. See
[third-party notices](app/src/main/assets/voice-licenses/README.txt) for source and licenses.

## Personality

He is a jokester, but an understated one: dry, specific, quietly pleased with himself — a
small joke rather than a big one, and never loud. Cheeky rather than rebellious, never
genuinely grumpy. He teases because he is fond of you, says something wry when he wins,
takes losing fine, and takes criticism of his painting in good humour. He still helps when
you actually need something, plainly and properly.

Replies are short by design: a sentence most of the time, two at the very most, with the
request capped at 150 tokens so a chatty model cannot sprawl. The default prompt is long on
purpose and is fully rewritable from the **Personality** screen.

A prompt you write yourself replaces the built-in one outright rather than adding to it, so
personality changes shipped in a new version do not reach it. The **Personality** screen
says so when it is happening, and **use new built-in** swaps in the current default while
keeping your text — **bring my text back** puts your own wording straight back. Saving the
built-in text unchanged counts as choosing the built-in prompt, so nobody gets stuck on an
old copy of the default by accident.

Reactions to what you do with his toys are local canned lines, so they cost no API calls
and never repeat the one before: tap the same toy twice and he says two different things.
They are also the fallback: while your message is being answered — and for a few seconds
after the reply lands — those lines stay quiet, so his own answer always has the last word
instead of the two talking over each other.
Criticism and requests in chat go through the model with a note about what just happened,
so his reply matches: tell him the lemonade was bad and he takes it in good humour.
Every reply also carries an exact list of what is on the screen, and he is told that the
list is the only truth — if a toy is not on it, it is not there and he must not talk as if
it were.

## Setup on the phone

To sideload without building, download the latest signed APK:
[`phone-assistant-0.173-debug.apk`](dist/phone-assistant-0.173-debug.apk).
The `dist/` directory also contains APKs from earlier releases.

Tagged builds are also published as GitHub Releases by
`.github/workflows/release.yml`, which is what the in-app update check looks at.

1. Build and install: `./gradlew installDebug` (or open in Android Studio and Run).
   The first build fetches the pinned, checksum-verified Kokoro runtime from npm;
   the phone downloads the voice model separately only if requested.
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
   **Accessibility** (needed for phone jobs), **Usage access**, and **Notifications** are
   optional. Without accessibility you can still chat and use on-screen toys, but Pip can’t
   control other apps (including Doomscroll or in-app playback) or detect their keyboards.
   You can change those grants later in **Pip settings → Permissions**. **Pip settings → Debug options → Diagnostics** shows whether
   accessibility is connected and records the last phone job’s steps.
4. Press **Start assistant**. Open **Pip settings** below that button to change what Pip
   does. Changes are saved and apply without restarting the overlay.

   - **Movement & startup:** Turn off wandering to keep Pip in place between tasks.
     You can still drag him, and he can move for his chat box, keyboard, and toys.
     **Start Pip when the phone starts** is off by default. With **Display over other
     apps** permission, it starts the overlay after the first unlock, not old jobs.
   - **Phone access:** **Let Pip use accessibility** controls phone actions and other apps’
     keyboard tracking without changing Android’s grant. Chat and explicit links don’t need it.
   - **Toys:** Turn off **Allow toys** for no toys, or select individual toys. Turning off
     **Bring out toys on his own** leaves enabled toys available by request only. Open the
     **Painting gallery** directly below the toy switches.
   - **Personality & data:** Edit Pip’s personality, review his memory, or open his login vault.
   - **Permissions:** Manage overlay, Android accessibility, usage access, and notifications.
     To turn off notifications while Pip keeps running, open **Manage notifications** and
     turn them off in Android. Android may still list Pip among active apps.
   - **API & model:** Configure the API key, base URL, and model in their own section.
   - **Debug options:** Open **Diagnostics** or choose **Run setup again** to revisit the
     welcome, API verification, and permissions screens. Your data stays in place; changes
     to the API connection are saved only if you finish the walkthrough.

### Questions about your phone

- **“What song am I listening to right now?”** checks Android’s active media controls.
  Enable **Now playing access** under **Pip settings → Permissions** (Android calls it
  **Notification access**). Pip reads the active player’s title and artist on-device;
  he doesn’t read notification messages, send the media metadata to the API, or change
  playback. Paused tracks aren’t reported as playing. Some players don’t share a title.
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

## Signing

`signing/phone-assistant.jks` is committed and public, and signs **every** build — debug
and release alike. One signature means every build installs straight over the top, so
updates never require uninstalling and no chat history is lost.

This is a personal-use key: ideal for sideloading on your own phone, and never acceptable
for Play Store distribution or anywhere impersonation would matter.

If you ever decide to share the app, generate a private keystore, feed it to the release
workflow through repository secrets, and expect the first private-key build to need one
uninstall — Android treats a changed signature as a different app.

### Publishing a release

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, then tag:

```bash
git tag v0.3
git push origin v0.3
```

`.github/workflows/release.yml` builds the APK and publishes it on a GitHub Release, so
binaries never enter git history.

## Logins

Saved entries stay encrypted in the **Logins** screen under a key from the Android
Keystore. Pip no longer inserts these entries into other apps: an accessibility label
or a site name inferred by an AI is not proof that a form belongs to the intended site.
When a job needs a sign-in, complete it yourself or use Android Autofill, then ask Pip
to continue. Existing entries aren't removed by the update.

To retrieve a saved password, tap **Reveal** and confirm your device PIN, pattern, or
password. The reveal is not copied to the clipboard, blocks screenshots of the vault,
and closes when you leave or after 30 seconds. The job model sees a generic password
field marker, never the contents. The API key and vault are excluded from Android backup
and device transfer. A vault that cannot be decrypted reads as empty and is recorded in
Diagnostics.

## Memory

Say **“Refer to me as Nova”** or **“Call me Nova”** in either chat. Pip saves one
**Preferred name** entry in **Pip settings → Personality & data → Memory** and confirms
it without an API call. Asking for a new name replaces that entry.

With **Learn from chat** on, Pip’s normal chat reply can also suggest one useful, lasting
note, such as a preference, hobby, or goal. The app checks that the suggestion uses your
words from the latest message and skips obvious sensitive details and requests not to save.
It does not make a second API request. Turning **Learn from chat** off leaves saved notes
and direct “remember” requests available. Use **Memory** to review or delete anything that
came out wrong. Chat history is separate and is still capped at the last 24 messages.

## Changing the character

- **Personality**: the **Personality** button under **Pip settings → Personality & data** opens
  the prompt in an editor and
  saves it on the device. The built-in version lives in `Persona.kt`; **use new built-in**
  goes back to it, stashing your text in `custom_backup` first so **bring my text back**
  can restore it. The note about which app you are in, the list of
  toys on screen, and the remembered facts are all appended automatically and are not part
  of the editable text.
- Look and animation: `app/src/main/java/com/phoneassistant/PetView.kt`
- Walk speed, idle timing, window size: `OverlayService.kt`
- Toys: `Prop.kt` is the base class (one overlay window, drag, tap, `onFrame` for
  animation and physics). Each toy lives in its own file —
  `Lemonade.kt`, `Painting.kt`, `Soccer.kt`, `BubbleWrap.kt`, `Plant.kt`. `Toys.kt` is the
  only way a toy arrives: it reads chat messages and buttons, spawns through one `summon`
  path, and returns a note so Pip's reply matches. `Quiet.kt` remembers how long he has
  been told to stay out of the toy box.

The API key is stored only in this app's private preferences on the device.
