package dev.nanovoice

/**
 * What the chat shows for the snapshots of one reply, when the agent may say it is working (a web search, a call to
 * the calendar). The agent first says a line ("Cerco sul Web..."), which arrives as ordinary text, and then that it
 * is working. That line is a status, drawn with an animation in place of a message: so when "working" arrives, the
 * message the line had become is taken away and the line goes to the status.
 *
 * Once the answer itself is on screen, a second "working" (more work before the rest) leaves the answer where it is and
 * shows only the dots: the answer is never taken away.
 */
internal class ReplyPresenter {
    /** The line said before the work, which the answer's text starts with. */
    private var ack = ""

    /** The agent has said it is working at least once in this reply. */
    private var worked = false

    /** What to do with the chat for one snapshot. */
    data class Step(
        /** The line for the animated status, empty for none (the dots show while the agent works). */
        val status: String,
        /** Take away the message that was shown for this reply so far. */
        val removeBubble: Boolean,
        /** The text the reply's message shows now, or null to leave it as it is. */
        val bubbleText: String?,
    )

    /** [hasBubble]: a message for this reply is on screen. */
    fun next(snap: ReplySnapshot, hasBubble: Boolean): Step {
        if (!snap.working) {
            val shown = visibleReply(snap.text, ack)
            return Step(status = "", removeBubble = false, bubbleText = shown.ifEmpty { null })
        }
        // After the first "working" the line's message was taken away, so a message that is there now is the answer: it
        // stays, and the dots show for the work that follows.
        if (worked && hasBubble) return Step(status = "", removeBubble = false, bubbleText = null)
        worked = true
        ack = snap.text
        return Step(status = ack.trim(), removeBubble = hasBubble, bubbleText = null)
    }
}
