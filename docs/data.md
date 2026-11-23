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
and device transfer. Chat history, saved memory, and custom personality are also
excluded. If the vault can’t be read, Pip reports it in Diagnostics without logging
credential contents and won’t overwrite the stored vault.

## Memory

Say **“Refer to me as Nova”** or **“Call me Nova”** in either chat. Pip saves one
**Preferred name** entry in **Pip settings → Personality & data → Memory** and confirms
it without an API call. Asking for a new name replaces that entry.

With **Learn from chat** on, Pip’s normal chat reply can also suggest one useful, lasting
note, such as a preference, hobby, or goal. The app checks that the suggestion uses your
words from the latest message and skips obvious sensitive details and requests not to save.
It does not make a second API request. Turning **Learn from chat** off leaves saved notes
and direct “remember” requests available. Use **Memory** to review or delete anything that
came out wrong. Chat history is separate and is capped at the last 24 messages. Individual messages are
limited to 4,096 characters.

## Changing the character

- **Personality**: the **Personality** button under **Pip settings → Personality & data** opens
  the prompt in an editor and
  saves it on the device. The built-in version lives in `Persona.kt`; **use new built-in**
  goes back to it, stashing your text in `custom_backup` first so **bring my text back**
  can restore it. The list of toys on screen and remembered facts are appended automatically
  and are not part of the editable text. With Usage access, Pip answers typed questions like
  “Which app am I in?” on-device using the most recent foreground app Android reports.
  The app name from that lookup is not sent to the chat provider or saved in chat history.
- Look and animation: `app/src/main/java/com/phoneassistant/PetView.kt`
- Walk speed, idle timing, window size: `OverlayService.kt`
- Toys: `Prop.kt` is the base class (one overlay window, drag, tap, `onFrame` for
  animation and physics). Each toy lives in its own file —
  `Lemonade.kt`, `Painting.kt`, `Soccer.kt`, `BubbleWrap.kt`, `Plant.kt`. `Toys.kt` is the
  only way a toy arrives: it reads chat messages and buttons, spawns through one `summon`
  path, and returns a note so Pip's reply matches. `Quiet.kt` remembers how long he has
  been told to stay out of the toy box. `ToySounds.kt` synthesises every toy sound from
  scratch and `ToyAudio.kt` plays them: pitch variation and cache, the silent-mode and
  speaking checks, and the volume setting. There are no audio assets in the APK.
- Locking the screen: `ScreenLock.kt` decides whether a message is a lock request, and
  `ScreenLocker.kt` performs it — the accessibility global action on Android 9+, the Quick
  Settings tile below that — and reports honestly when it cannot.

The API key is stored only in this app's private preferences on the device, sealed
under a key from the Android Keystore (like the logins vault), and excluded from backup.
