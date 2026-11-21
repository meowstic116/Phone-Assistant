package com.phoneassistant

/** These are safe for a public notification: no audio, transcripts, or reply text. */
internal enum class WakeFeedback(val message: String, val canSpeak: Boolean = false) {
    OFF("Microphone is off."),
    STARTING("Loading offline speech recognition…"),
    OPENING_MIC("Opening the microphone…"),
    LISTENING("Listening. Say ‘Hey Pip, tell me a joke.’", true),
    NAME_HEARD("Name heard. Keep speaking."),
    SPEAK_NOW("Speak now. You don’t need to say Pip’s name."),
    NO_NAME("Speech didn’t sound addressed to Pip. Say his name or try Speak now.", true),
    NO_MESSAGE("No message heard. Try again or tap Speak now.", true),
    MIC_SILENT("No microphone signal. Check Android’s Mic access and close other apps using the mic."),
    SENDING("Message heard. Waiting for Pip’s reply…"),
    PLAYING("Microphone paused while Pip speaks."),
    REPLIED("Pip replied. Check the bubble or chat history.", true),
    REVIEW("Phone action heard. Unlock and review it before tapping Send.", true),
    DRAFT("Pip heard you. Send or close your unfinished text draft first.", true),
    BUSY("Pip heard you, but he’s busy. Try again in a moment.", true),
    CHAT_FAILED("Message heard, but Pip couldn’t reply. Check the chat API settings or try a typed message.", true),
    ENGINE_FAILED("Couldn’t start offline recognition. Stop and retry in Pip settings."),
    MIC_FAILED("Couldn’t open or read the microphone. Check permission and try again."),
    START_FAILED("Android didn’t start listening. Check microphone permission and try again with Pip settings open.");

    val isOutcome: Boolean get() = this in setOf(REPLIED, REVIEW, DRAFT, BUSY, CHAT_FAILED)
}
