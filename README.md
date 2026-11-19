# Phone-Assistant

A little phone-shaped character named Pip that walks around your Android screen, over
your other apps, and talks back. He also puts things on the screen and plays with them
with you — a lemonade stand, a canvas, a soccer pitch, bubble wrap, a plant.

## What it does

- A floating character drawn entirely in code (no sprite sheets) that wanders the whole
  screen, blinks, sits down, and reacts.
- Everything moves rather than appears: a toy he puts down pops into place and shrinks away
  when it goes, a prop lifts slightly under your finger while you drag it, and the speech
  bubble eases in, then drifts out. A line stays up for as long as it takes to read.
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
  after confirming your device lock, or sign in through Android Autofill.
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
  activity that I haven't given kudos to yet”** or **“Open Instagram and like every post I
  haven't liked yet.”** Pip reads the reaction controls Android exposes, skips already-applied
  reactions, and counts a press only after verifying that the control changed. Three distinct
  screens in a row with already-applied reactions and none ready are enough to stop, not proof
  that the entire feed was checked. Screens with no recognizable reaction controls don't
  count toward completion; Pip keeps scanning while new screens are visible. A failed reaction isn't tapped again; Pip can try a
  different control, but stops after several unverified reactions or scrolls that don't
  reveal new content.
  For something more selective, try **“Doomscroll Instagram Reels and like any you want.”**
  Pip may pick one like per screen or skip it. He judges only the captions and labels Android
  exposes, not the video or audio. **“Doomscroll Instagram Reels for me”** browses without
  reacting. Say **“Doomscroll Instagram Reels for 2 minutes”** or **“Doomscroll 10 reels on
  Instagram”** to set a time or count. The timer starts when the feed opens. A count means
  distinct reel screens Android exposes, including the first one, not full videos played.
  With neither limit specified, Pip picks a random duration from 30 seconds to 5 minutes.
  He may stop sooner if the feed stalls, and **Stop** works at any time. If Pip can't find
  the requested tab, open it in the app and retry. If a request names an app, Pip opens it before his first step
  unless you're already in it, in which case he leaves your
  screen alone — so the job happens in front of you rather than somewhere behind   it, and he moves into the bottom corner so he is not covering it. He is given the list of apps really
  installed on the phone, so "open WhatsApp" reaches that WhatsApp and not a guess.
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
out from the **Toys** buttons in the app, ask him for one in chat ("open your lemonade
stand", "paint something", "let's play soccer"), or just leave the screen alone — he gets
bored every few minutes and starts something himself.

He puts things away when you ask ("put the plant away", "pack the lemonade away", "tidy
up"), and also on his own: once a toy has been out for about four minutes he has had
enough of it, says so briefly and tidies that one away. The oldest toy goes first, and a
lemonade stand leaves with its glasses and his place behind the counter.

He can be sent on a break too: "no toys for half an hour", "take a break for 20 minutes",
"not now" (which means 15 minutes), or "leave me alone for the evening" (8 hours). He puts
everything away, sulks about it, and gets nothing out until the time is up. The break is
saved on the device, so it survives restarting him, and he knows exactly how much of it is
left — ask him and he will tell you, with feeling. When the time runs out he says so in his
bubble, from a local list of lines, so it costs no API call and feels like he was actually
waiting. The timer is checked against the real deadline the moment the screen comes back on,
so a phone asleep through the end of a break still gets told about it immediately rather than
whenever the sleeping timer wakes up, and an announcement he never got to make is remembered
on disk — if Android kills the service mid-break he still says it when he comes back. The app can start one too — **Give Pip a break** offers 15 minutes, 30, an hour or two —
and while a break is on it shows a live countdown next to an **End break** button that cuts
it short and gets him talking again. "You can play again" does the same from chat. Phrases only count when they are aimed at him, so "I need a break
from work" leaves the lemonade stand alone.

- **Lemonade stand** — tap the stand and he pours you a glass, to a maximum of two because
  he has one jug. The glass is its own prop: tap it once to drink, tap the empty glass
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
- **Bubble wrap** — a sheet of 35 domed bubbles on a sheet of plastic, each with its own
  highlight, popped ones left as creases with a small burst as they go. No header and no
  counter: the sheet itself shows how far along you are. He joins in — walks over, presses
  three himself with a dry line about it, and leaves you the rest. Pop every one and he
  clears the sheet away.
- **Plant** — tap to water it, three waterings per stage and it grows through four stages
  to a flower. Water it too fast and he accuses you of drowning it.

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

The current build is committed at `dist/phone-assistant-0.111-debug.apk` if you just want to
sideload it without building. Each new build replaces the file in `dist/`, so there is
always exactly one APK to grab — easiest thing to download from a phone.

Tagged builds are also published as GitHub Releases by
`.github/workflows/release.yml`, which is what the in-app update check looks at.

1. Build and install: `./gradlew installDebug` (or open in Android Studio and Run).
2. On the setup screen, grant **Display over other apps**, **Usage access**, and
   **Notifications**. **Pip can work other apps** (an accessibility service) is needed for
   phone jobs, and is the only way he can see a keyboard opened in another app — a floating
   overlay is never told about a keyboard it did not raise itself. He reads the labels of the
   app in front only while a job is running — never what is inside a text field, and never a
   password — and that list of labels is the only thing that leaves the phone. Nothing he
   reads is stored. If the switch was already on
   from an older build, turn it off and on once so Android picks up the new capabilities:
   before 0.59 it could only see window bounds. **Diagnostics** on the main screen shows
   whether it is reporting at all, which is the quickest way to tell a working service from a
   switched-off one. It also keeps the last phone job's steps, so a job that stopped early
   says why there rather than leaving you to guess at a bubble that has already gone.
3. Paste an API key. The defaults point at Gemini's free tier
   (`https://generativelanguage.googleapis.com/v1beta/openai` with
   `gemini-3.5-flash-lite`), and a key from https://aistudio.google.com/apikey costs
   nothing and needs no card. Roughly 500 requests/day at 15/minute.
   `gemini-3.8-flash` is smarter but its free tier allows only about 20 requests/day.
   Any OpenAI-compatible endpoint works too — OpenAI, OpenRouter, Groq, or Ollama on
   your network.
4. Press **Start assistant**.

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

Pip keeps short notes about you between conversations. They are pulled out locally by
pattern matching — "my name is …", "I'm working on …", "I like …", "I live in …", and
anything said after "remember" — so remembering costs no extra API calls. The notes are
appended to the prompt, and the **Memory** screen shows every one of them so you can
delete a bad capture. The conversation itself is still capped at the last 24 messages.

## Changing the character

- **Personality**: the **Personality** button in the app opens the prompt in an editor and
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
