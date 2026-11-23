## Hands-free driving (experimental)

Set up and test **while parked**. Enable **Pip settings → Hands-free driving → Allow
hands-free driving commands**, turn on spoken replies, and start keyword listening.
Connect Android Auto and try:

- **“Pip, navigate to 123 Main Street.”** Google Maps receives a standard directions
  intent. Set a local home destination in Pip settings for **“Pip, navigate home.”**
  If Android has Location switched off, Pip says so instead of letting Maps open on a blank
  map. No app may flip that toggle itself, so Pip opens Android’s **Location** setting when
  the phone is in use, and simply tells you when it’s locked.
- **“Pip, cancel navigation”**, **“stop navigation”**, or **“end the route.”** Maps has no
  stop API, so Pip uses the Exit button on Maps’ own navigation notification — the same one
  you would tap — and never needs the phone unlocked. After sending it Pip waits about two
  seconds: it says **“Stopped navigation.”** only when the notification actually goes away,
  and otherwise says the stop was sent but Maps may still be running. If Maps doesn’t offer a
  button, Pip falls back to the documented `geo.action:?act=exit_navigation` intent used by
  Google Assistant. This needs **Notification access**; **Pip settings → Hands-free driving →
  Fix navigation access for Pip** opens the right page.
- **“Pip, are we still navigating?”** or **“how much longer?”** Pip reads Maps’ notification
  and answers without touching anything: **“Yes, navigation is still running.”** This one is
  read-only, so it also works by name at any time, locked or not.
- **“Pip, pause Spotify,” “resume Spotify,” “next song on Spotify,”** or **“previous
  track on Spotify.”** Start Spotify while parked and grant **Notification access** so
  Pip can control the existing local session. You can also say **“Pip, play Emo Route Mix”**
  or **“Pip, play the Emo Route Mix playlist on Spotify.”** For named playlists, Spotify’s
  session must offer playback search. After sending a command Pip watches the session for up
  to four seconds and only says it is playing when the state (and, for a named playlist, the
  title) actually changed; otherwise it says the request did not take effect. For a playlist
  the title has to change or match, so a song already playing isn’t mistaken for the playlist.
  If search is unavailable, use Android Auto’s own voice assistant instead. Pip doesn’t use
  accessibility or send the playlist request to its chat API.

If you don’t want to say his name while driving, turn on **Accept driving commands without
saying “Pip”**. With a hands-free Bluetooth link connected and driving commands enabled, an
exact command like **“pause Spotify”**, **“next song”**, **“take me home”**, or **“stop
navigation”** runs on its own. **Answer “are we still navigating?” without saying “Pip”** is a
second, separate toggle for the read-only question, on by default. Chat, phone tasks, and
purchases still need his name, so ordinary conversation in the car is never mistaken for a
command.

Pip checks the Android Auto projection connection and handles only these commands on the
phone, without the chat model or accessibility automation. Google Maps and Android decide
whether a navigation handoff can open or appear on the car display, especially while locked.
Spotify controls require an active session that offers the requested action. Pip does not
unlock the phone or control the car screen. Test your mic, audio routing, and each command
while parked before relying on them. If a command doesn’t work while driving, wait until
parked rather than reaching for the phone. In **Diagnostics → Hands-free voice**, check
whether Pip heard the wake phrase, handed off the request, and got audio playback. Those
status lines stay available for the current app session and contain no transcript or destination.
If Android reports that speech completed but you heard nothing, check where the phone routed
media audio while parked. Use **Preview Pip’s voice** to test the selected voice first.

Android offers no public way to simply “pick the car’s microphone”. Pip asks for the Bluetooth
communication route when a hands-free device is connected, driving commands are enabled, and
the phone is locked: on Android 12+ that is `setCommunicationDevice`, and on older versions it
is Bluetooth SCO. If Android confirms the route, the car supplies the microphone and the
notification says **Listening through the connected car microphone**. If it does not confirm
within a couple of seconds, or the route carries silence, Pip goes back to the phone
microphone and keeps listening — a failed handover never leaves the session deaf. Android 12+
may ask for the **Nearby devices / Bluetooth** permission when you start listening; it is only
used for this route.

**Diagnostics → Hands-free voice → car audio** shows the connected Bluetooth device and whether
it has a hands-free link (`media only, no mic` means only music audio is connected). **last car
connection** shows what Android reported about Android Auto for the last spoken request, which
separates “never heard you” from “heard you and declined to run it”.
