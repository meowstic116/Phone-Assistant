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

### Toy sounds

Every toy is audible. Bubbles pop, the lemonade stand squeaks and pours, the glass glugs,
the ball thumps and the goal nets a little fanfare and a referee's whistle, the brush
scratches on the easel, the watering can pours and the plant swells as it grows, and
picking a toy up or setting it down makes a small rubbery note. Nothing is downloaded or
recorded: each sound is synthesised on the phone at 44.1 kHz when it is first needed, in
about a millisecond, and then cached.

No two pops are identical. Every sound is rendered at a random pitch between 0.94× and 1.06×
of its written pitch — the same small band a human hand would vary by — and the noise
component is seeded from that exact pitch, so the slightly higher pop is also slightly
crisper. Cache keying follows the pitch, so a sound still repeats its earlier takes instead
of drifting into sounding like a different sound.

The toys hush while Pip is speaking. His own voice is the point of the conversation, so toy
sounds are dropped rather than ducked for the length of a reply, and they resume on their own
afterwards. They also respect the ringer: with the phone on silent or vibrate, the toys are
silent too. **Toy sound effects** and **Toy sound volume** in Toy settings switch them off
or take them down; **Try a toy sound** plays a sample so you can judge the volume without
waiting for a bubble. **Diagnostics** shows whether sounds are on, off, or being held back by
silent mode.
Every reply also carries an exact list of what is on the screen, and he is told that the
list is the only truth — if a toy is not on it, it is not there and he must not talk as if
it were.
