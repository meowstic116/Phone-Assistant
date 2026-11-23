## Pip’s voice

In **Pip settings → Voice**, **Kokoro** is the default for new voice setups, with the
male **Adam** preset. Tap **Download Kokoro** (about 110 MB from GitHub), then **Preview
Pip’s voice**. Turn on **Read Pip’s replies aloud** if you want spoken replies; it’s off
by default. **Choose Pip’s voice** also offers Michael and Bella. Kokoro is English-only
and needs Android 8.1 or newer. It runs on your phone after setup, with no speech API
key, server, or per-use cost. You can remove the download from Voice settings. An
existing explicit voice choice stays selected.

**Offline Android voice** uses an installed system voice and is never a fallback.
Pip doesn't send reply text to a speech provider. The separate chat API still generates
chat replies when needed, whether or not speech is enabled.

Pip’s own overlay shows the animated dots, captions, and text without opening a second
window. **Pip settings → Voice → Preview reply bubble** tests the display without voice
or the chat API.
The dots stay animated while Pip prepares a spoken reply. When playback
begins, the bubble reveals words without resizing. Android engines may report word ranges;
Kokoro estimates word timing from the playback position. If audio is unavailable or times
out, Pip shows the reply instead. Voice-off replies appear immediately. The bubble only
counts visible time, then fades without shrinking or moving. Long replies fit the screen;
the full text remains in chat history. Kokoro runs in a separate app process so a voice-engine failure
doesn’t restart the overlay. If that process stops, Pip pauses automatic Kokoro
speech for the session; a successful **Preview Pip’s voice** resumes it. Preview
reports whether a failure happens during voice generation or playback. The last voice
issue also appears in **Debug options → Diagnostics**, without recording any spoken text.
Kokoro’s runtime is bundled for arm64-v8a, armeabi-v7a and
x86_64. Its weights are not bundled with the APK or committed to Git. See
[third-party notices](../app/src/main/assets/voice-licenses/README.txt) for source and licenses.

## Talk to Pip hands-free

Start the assistant, then open **Pip settings → Talk to Pip**. Download offline recognition
(about 57 MB), grant microphone permission, and tap **Start listening for Pip**. You can
leave Pip’s Android notifications off.
Address him in English: **“Hey Pip, tell me a joke”**, **“Good one, Pip”**, or
**“Could you Pip turn the music down?”** Any mention of his name while you are speaking makes
the rest of that sentence his message, with or without “hey” or commas. You can also say his
name, pause briefly, then speak. If the recognizer hears the name in a live transcript but
drops it from the final one, he still answers that same sentence. Turn on **Read Pip’s replies
aloud** if you want to hear answers with the screen locked.

If he doesn't respond, check the live status under **Talk to Pip** or, if enabled, in the
listening notification. **Speak now (no wake word)** opens a short window to say one message without
the name, useful for checking that the microphone and chat work independently of wake detection.
Wait until the status says **Speak now** after tapping it. **Diagnostics → Hands-free voice**
can distinguish a missed name from an interrupted or Android-muted microphone. Pip can retry
a lost audio stream during a listening session, but won’t start a new session on its own.

Listening is off by default. To skip the extra tap each session, turn on **Start listening when
Pip starts** under **Talk to Pip**. The next time you tap **Start assistant** in the app,
Pip will start listening if the offline model and microphone permission are ready. If they
aren’t, Pip still appears with the mic off. If **Start Pip when the phone starts** is also on,
Pip can start listening when the overlay starts after your first unlock on older Android
versions. On Android 11 or newer, listening resumes when Pip’s app screen opens after
unlocking. To have Pip try to open that screen automatically, turn on **Open the Pip app after
restart** under **Movement & startup**. This is off by default and opens the regular app screen
once after unlock. If Android or the phone maker blocks the launch, open Pip yourself. No
notification permission is needed, and automatic service recovery never restarts the mic.

Listening can continue after locking and uses battery; Android
or the phone maker may suspend it. To stop listening, use **Stop listening for Pip** under
**Talk to Pip**, or stop the assistant. If notifications are on, the listening notification
also has a **Stop listening** action. Android can still show its own microphone indicator
or list Pip under active apps when notifications are off. The microphone is paused while Pip replies so
he won’t normally wake himself. Microphone audio is processed in memory on the phone, not
saved or sent to a server. Only recognized messages addressed to Pip enter the regular chat
flow and use your configured chat API. Pip checks locally whether speech sounds addressed to him. For an unlocked follow-up, he can use
the previous spoken exchange for up to 90 seconds; he doesn’t read typed chat history or saved
facts. This temporary context clears when you lock the phone or stop listening.

Spoken phone requests run straight away, the same as a typed message: “open Instagram and
doomscroll”, “text Alex I’m running late”, “what did Maya text me?”. Only spending money
stops for you — asking him to buy, pay, check out, subscribe, or order puts the request in
the composer for you to handle; he never pays and never confirms a purchase. Work that needs
the phone unlocked still waits for you to unlock it.

Offline recognition uses the English [Kroko community model](https://github.com/kroko-ai/kroko-onnx)
[distributed by sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models),
under the model’s CC BY-SA terms. The pinned weights are downloaded separately, not bundled.
