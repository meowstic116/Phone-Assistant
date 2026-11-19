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
- A **Logins** screen holds the logins he is allowed to use: a website, a username, a
  password. They are encrypted on the phone under a key from the Android Keystore and never
  leave it — not in a prompt, not in the chat, not in any request. Ask him to sign in
  somewhere he has a login for and he fills the form himself; ask him somewhere he does not
  and he says so and opens the form with the site already in it.
- Ask him to **stand still** and he stops where he is: "stand still for two minutes" for
  that long, or just "stand still" for as long as you like — until you tell him he can move
  again. He is still there to talk to, and the hold is kept on the phone like a toy break, so
  a restart does not lose it.
- A **Diagnostics** screen shows, live, exactly what Android tells the app rather than what
  it should: the keyboard height the accessibility service reports, whether that service is
  switched on,   whether a keyboard is up, where he is sitting against it, how many frames a second the
  character is really drawing (and whether he is drawing at all — the screen-off pause and
  the idle rate are both visible there, measured in `PetView.onDraw` rather than assumed),
  what is on the stage, the last phone job step by step — what was asked for, every action it tried,
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
  again — at most eight steps, so a job cannot run away with your API quota, and the work is
  visible as it happens because the app in front is changing. If the request names an app he
  opens it before his first step — unless you are already in it, in which case he leaves your
  screen alone — so the job happens in front of you rather than somewhere behind   it, and he moves into the bottom corner so he is not covering it. He is given the list of apps really
  installed on the phone, so "open WhatsApp" reaches that WhatsApp and not a guess.
  Looking something up is one action, not a browser driven by hand: the query goes to
  whichever browser Android has been told to use — yours, wherever you set it — as a search
  URL, and "take me to the images" is the pictures page of it. A browser's search box
  cannot be typed into usefully, because submitting it needs an enter key the accessibility
  service has no way to press: the field fills, the page never changes, and the job ends
  having spent four steps to get nowhere. A `wait` step really waits, rather than re-reading
  the same loading screen and asking to wait again. While a
  job is running there is a small **Stop** chip in the bottom left corner, over whatever app
  he is working in — a corner rather than the middle of the bottom edge, which is where a job
  is most likely to need to press: one tap ends the job where it stands, and the record on
  Diagnostics says it was cancelled. Drag the chip anywhere you like and it stays there, for
  this job and the next one; it lifts above the keyboard when one comes up, so it is never
  behind the app. For the moment a step is actually performing, everything of his that can
  take a finger goes inert — the chip dims, and he, the toys and the floating diagnostics
  panel stop accepting touches — because a press aimed at a row that sits exactly where one
  of them is would otherwise land on Pip instead of on the app. He waits until they are
  really out of the way before he presses anything, and if they cannot be moved in time (a
  stuck main thread) he presses nothing and the Diagnostics record marks that step **SKIP**
  rather than blaming the app. Only a toy or panel you are actually dragging keeps its touch,
  so the drag is never dropped out of your hand — it steps aside the instant you let go.
  Stop is back the moment the step finishes.
  An action already under
  way is not un-pressed, but nothing new happens
  after the Stop — a step the model was still deciding is thrown away instead of carried out,
  which includes cancelling by sending him a new message. He says what he is starting,
  then reports in his bubble. His hands go through the accessibility service: a password
  field can only ever be filled from a login saved on the phone, never from anything the
  model suggests, and he refuses payments, purchases and deletions he was not asked for —
  those refusals are in the code and not only in the instructions.
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

The current build is committed at `dist/phone-assistant-0.88-debug.apk` if you just want to
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

The **Logins** screen is where you give Pip a login he may use: a website, a username and a
password. There is exactly one place they exist, and it is worth being precise about what
that means:

- They are encrypted at rest with AES-GCM under a key that lives in the Android Keystore and
  cannot be read out of the device. The ciphertext is all that is written to storage, and no
  screen ever shows a saved password again — not even masked.
- They are never put in a prompt. The model learns that a login exists only when it asks for
  one for a site it named; it never sees a username, a password, or the list of sites you
  have saved.
- Nothing on the sign-in path can reach the network. `Vault.kt` has no network code and
  nothing that reads it does either: the agent's `sign_in` step reads the value, types it
  into the field, and records only the site.
- The chat is not a place to type a password and he will not ask for one there. If a job
  needs a login he does not have, he says so in one line and opens the form with the site
  filled in. Android redacts what you type into a site, which is exactly why he has to ask
  rather than watch you do it.
- A login page in a browser is a web page, not a form of native fields, and the fields
  inside a web view are not editable views the accessibility service can type into. So a
  saved login cannot be filled into one. Before he says that, he looks for the form himself:
  he taps a visible **log in** / **sign in** link and scrolls down a couple of screens,
  reading the screen again after each, and fills the login if it turns up — on the phone,
  inside the same step, at no extra API cost. Only when that finds nothing does he say what
  he actually saw: that he looked and there is no password box here, and that the login is
  still saved. The **Logins** form then opens with the site filled in, instead of the step
  ending on a bare error with nothing to act on — and saving a login there picks the job back
  up as soon as you are out of that screen (within a minute of leaving it), starting the
  request again from the top. Diagnostics records that stop as its own reason, "stopped to ask
  for a login", rather than as a cancellation. Sign-in works in apps, and in a browser only
  where the site puts real editable fields on screen.
- Deleting an entry deletes it; uninstalling the app takes the whole vault with it. A vault
  that cannot be decrypted — a restore onto a new phone, since the key stays behind — reads as
  empty and says so in Diagnostics rather than failing silently.

`sign_in` is the only thing that ever fills a password field: `Screen.type` refuses one
unless its caller passes `secret = true`, and that call is in `Agent.signIn` alone. What a
text field contains is never part of the screen listing that goes to the model at all — only
whether it has something in it.

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
