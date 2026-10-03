package org.agentos.app.ui

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

/**
 * Holds the message list and rate-limits its accessibility events while a reply streams.
 *
 * Every text change and scroll of the streaming bubble is an accessibility event (the platform merges
 * them to one per 100 ms). Accessibility clients that wait for the screen to settle — UI Automator's
 * `dump` waits for 1 s without events, test frameworks do the same — never see it settle during a long
 * reply, so they cannot find the Stop button. Merging updates to one per 50 ms does not change that.
 *
 * While [streaming] is true this frame lets through at most one content-change / scroll event per
 * [gapMs] from inside the list; the rest of the window (the Stop button, the status line) is untouched.
 * When streaming ends one content-change event announces the final text. A TalkBack user sees the
 * streaming text refreshed about every 1.5 s instead of ten times a second.
 */
class A11yThrottleFrame(context: Context) : FrameLayout(context) {
    var gapMs: Long = GAP_MS
    private var lastPassed = 0L
    private var dropped = false

    var streaming: Boolean = false
        set(value) {
            val ended = field && !value
            field = value
            if (ended && dropped) {
                dropped = false
                post { sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
            }
        }

    override fun onRequestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
        if (streaming && event.eventType and THROTTLED != 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastPassed < gapMs) {
                dropped = true
                return false
            }
            lastPassed = now
        }
        return super.onRequestSendAccessibilityEvent(child, event)
    }

    companion object {
        /** Longer than the 1 s of quiet UI Automator waits for. */
        const val GAP_MS = 1_500L

        private const val THROTTLED = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
    }
}
