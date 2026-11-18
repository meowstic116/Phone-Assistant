# Phone-Assistant

A little phone-shaped character named Pip that walks around your Android screen, over
your other apps, and talks back. He also puts things on the screen and plays with them
with you — a lemonade stand, a canvas, a soccer pitch, bubble wrap, a plant.

## What it does

- A floating character drawn entirely in code (no sprite sheets) that wanders the whole
  screen, blinks, sits down, and reacts.
- Tap it to open a small input box under his feet. He stops to listen, shows animated
  thinking dots, then answers in a speech bubble that follows him around and flips below
  him near the top of the screen. The box leaves as soon as you send, or when you press
  back. He steps straight up clear of the keyboard using its measured height, not a guess.
  Replies come from any OpenAI-compatible API.
- A **Chat history** screen shows the whole conversation and lets you keep talking from
  there; replies still appear in his overlay bubble, which floats above the chat.
- A **Personality** screen shows the full prompt that defines him, and lets you rewrite it.
- A **Memory** screen lists the facts he keeps between conversations, and lets you add or
  delete them.
- It knows which app you are in (via Usage Access) and can reference it. It never reads
  screen contents.
- Drag it anywhere. It lives in a foreground service with a persistent notification.

## Toys

Everything below is real, movable screen furniture. Drag any of it wherever you want and
tap it to use it. Each piece is its own small overlay window, so it never blocks the app
underneath, and anything that ends up under the chat box is nudged clear of it. Bring them
out from the **Toys** buttons in the app, ask him for one in chat ("open your lemonade
stand", "paint something", "let's play soccer"), or just leave the screen alone — he gets
bored every few minutes and starts something himself.

He is the only one who puts things away, and only when asked: "put the plant away", "pack
the lemonade away", "tidy up".

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
  again to hand it back. Ask him for "a lemonade" in chat and he pours one — walking round
  behind the counter first and only pouring once he is there, so the glass never appears
  while he is still on the other side of the screen. Interrupt him on the way — tap him,
  open a chat — and the pour waits: he goes back and pours as soon as he is free. One
  request makes exactly one glass: the debt is cleared before each pour and capped at the
  stand's two glasses, so an interrupted-then-resumed walk never doubles it. Ask twice
  quickly and the second glass arrives about a second after the first, not on top of it. When he opens
  the stand he walks round behind the counter and serves from there. The counter is drawn
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
- **Soccer** — goals at the top and bottom of the screen, a ball in the middle, and a score
  pill. Tap the ball to boot it at Pip's goal (tap left or right of the middle to angle it);
  he walks over and kicks it back at yours. First to three or ninety seconds, and he
  announces the result either way. The goals are draggable, which he notices: he calls it
  cheating (cheerfully), and three goalpost moves and he calls the game off.
- **Bubble wrap** — a sheet of 35 bubbles. Pop them; he counts and gets competitive. Pop
  all of them and he clears it away, impressed against his will.
- **Plant** — tap to water it, three waterings per stage and it grows through four stages
  to a flower. Water it too fast and he accuses you of drowning it.

## Personality

He is a jokester: daft, playful, cheerful, always up for a bit — cheeky rather than
rebellious, and never genuinely grumpy. He teases because he is fond of you, gloats in a
silly way when he wins, laughs off losing, and takes criticism of his painting as an excuse
for an even better joke. He still helps when you actually need something — usually with a
joke first, then properly. The default prompt is long on purpose and is fully rewritable
from the **Personality** screen.

A prompt you write yourself replaces the built-in one outright rather than adding to it, so
personality changes shipped in a new version do not reach it. The **Personality** screen
says so when it is happening, and **use new built-in** swaps in the current default while
keeping your text — **bring my text back** puts your own wording straight back. Saving the
built-in text unchanged counts as choosing the built-in prompt, so nobody gets stuck on an
old copy of the default by accident.

Reactions to what you do with his toys are local canned lines, so they cost no API calls.
Criticism and requests in chat go through the model with a note about what just happened,
so his reply matches: tell him the lemonade was bad and he gets mock-indignant about it.
Every reply also carries an exact list of what is on the screen, and he is told that the
list is the only truth — if a toy is not on it, it is not there and he must not talk as if
it were.

## Setup on the phone

The current build is committed at `dist/phone-assistant-0.32-debug.apk` if you just want to
sideload it without building. Each new build replaces the file in `dist/`, so there is
always exactly one APK to grab — easiest thing to download from a phone.

Tagged builds are also published as GitHub Releases by
`.github/workflows/release.yml`, which is what the in-app update check looks at.

1. Build and install: `./gradlew installDebug` (or open in Android Studio and Run).
2. On the setup screen, grant **Display over other apps**, **Usage access**, and
   **Notifications**.
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
