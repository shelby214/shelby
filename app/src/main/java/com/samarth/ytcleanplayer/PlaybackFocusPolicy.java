package com.samarth.ytcleanplayer;

/** Records playback intent separately from the player's paused state during interruptions. */
final class PlaybackFocusPolicy {
    private boolean playbackRequested;
    private boolean resumeOnGain;

    void playRequested() {
        playbackRequested = true;
        resumeOnGain = false;
    }

    void pausedByUser() {
        playbackRequested = false;
        resumeOnGain = false;
    }

    void temporarilyInterrupted() {
        // Repeated transient losses must preserve the original resume intent.
        resumeOnGain = playbackRequested;
    }

    void permanentlyInterrupted() { pausedByUser(); }

    boolean isWaitingForFocus() { return resumeOnGain; }

    boolean focusGained() {
        boolean resume = playbackRequested && resumeOnGain;
        resumeOnGain = false;
        return resume;
    }
}
