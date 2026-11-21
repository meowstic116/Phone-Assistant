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
    MIC_RESTARTING("Microphone capture was interrupted. Reopening it…"),
    MIC_MUTED("Android is silencing Pip’s microphone. Check microphone access or close the other app using it."),
    MIC_SILENT("No microphone signal. Check Android’s Mic access and close other apps using the mic."),
    SENDING("Message heard. Waiting for Pip’s reply…"),
    PLAYING("Microphone paused while Pip speaks."),
    REPLIED("Pip replied. Check the bubble or chat history.", true),
    CAR_COMMAND("Driving request handed off. Listen for Pip’s reply.", true),
    CAR_NOT_RUN("Driving request not run. Listen for Pip’s reply; troubleshoot only when parked.", true),
    REVIEW("Phone action heard. Unlock and review it before tapping Send.", true),
    DRAFT("Pip heard you. Send or close your unfinished text draft first.", true),
    BUSY("Pip heard you, but he’s busy. Try again in a moment.", true),
    CHAT_FAILED("Message heard, but Pip couldn’t reply. Check the chat API settings or try a typed message.", true),
    CHAT_TIMED_OUT("The chat reply took too long. Try again.", true),
    ENGINE_FAILED("Couldn’t start offline recognition. Stop and retry in Pip settings."),
    MIC_FAILED("Couldn’t open or read the microphone. Check permission and try again."),
    START_FAILED("Android didn’t start listening. Check microphone permission and try again with Pip settings open.");

    val isMicEvent: Boolean get() = when (this) {
        NAME_HEARD, SPEAK_NOW, NO_NAME, NO_MESSAGE, MIC_RESTARTING, MIC_MUTED, MIC_SILENT, SENDING, ENGINE_FAILED, MIC_FAILED, START_FAILED -> true
        else -> false
    }

    val isOutcome: Boolean get() = when (this) {
        REPLIED, CAR_COMMAND, CAR_NOT_RUN, REVIEW, DRAFT, BUSY, CHAT_FAILED, CHAT_TIMED_OUT -> true
        else -> false
    }
}
