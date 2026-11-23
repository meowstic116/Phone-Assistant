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

  **Clearing a screenful of buttons** is its own bounded job, because asking for one press per
  button defeats the point: **“press remove on all of these”, “clear every category”, “delete
  all of these rows”**. Pip reads the screen, presses only controls whose own label is a bare
  dismissal (**Remove**, **Delete**, the × on a row), re-reads the list after every press and
  scrolls when the visible ones run out. He stops the moment the screen becomes something
  else — a confirmation dialog, a settings page, a different app — and says how many he
  pressed instead of claiming the list is now clean. Labels that are a specific action
  (**Unsubscribe**, **Delete account**, **Remove ads**, anything with a price) are never
  pressed in bulk, and a request that names content rather than the screen (**“remove every
  photo of the dog”**, **“delete all of my messages”**) is treated as a normal job so it can be
  asked about. Money still stops first.
- Drag it anywhere. It lives in a foreground service with a persistent notification.

## Documentation

- [Toys](docs/toys.md) — the lemonade stand, canvas, soccer, bubble wrap, and plant.
- [Pip’s voice](docs/voice.md) — Kokoro and Android voices, wake phrase, hands-free chat.
- [Hands-free driving](docs/driving.md) — the experimental car mode.
- [Personality](docs/personality.md) — the prompt that defines him, and toy sounds.
- [Setup on the phone](docs/setup.md) — install, permissions, questions about your phone,
  and testing phone jobs.
- [Logins, Memory, and changing the character](docs/data.md) — what he keeps and where.
- [Developing Pip](docs/development.md) — building, running the checks, and how the code
  is laid out.

## Signing and distribution

Pip Tester uses a separate Android app ID and must be signed with a private key held by
its distributor. The old public keystore is retired and must not be used for new builds.
See [DISTRIBUTION.md](DISTRIBUTION.md) to sign the APK locally and share it directly.
