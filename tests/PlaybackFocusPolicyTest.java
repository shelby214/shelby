package com.samarth.ytcleanplayer;

/** Tests the service's native-controller policy; WebView delegates focus to Chromium itself. */
public final class PlaybackFocusPolicyTest {
    public static void main(String[] args) {
        PlaybackFocusPolicy policy = new PlaybackFocusPolicy();
        policy.temporarilyInterrupted();
        check(!policy.focusGained(), "idle playback must not start after a call");
        policy.playRequested();
        policy.temporarilyInterrupted();
        policy.temporarilyInterrupted();
        check(policy.focusGained(), "multiple transient losses must still resume once");
        check(!policy.focusGained(), "duplicate focus gain must not replay");
        policy.playRequested();
        policy.temporarilyInterrupted();
        policy.pausedByUser();
        check(!policy.focusGained(), "manual pause while interrupted must prevent resume");
        policy.playRequested();
        policy.temporarilyInterrupted();
        policy.permanentlyInterrupted();
        check(!policy.focusGained(), "another music app must not cause later auto-resume");
        policy.playRequested();
        check(!policy.isWaitingForFocus(), "explicit replay must clear delayed resume state");
        System.out.println("PlaybackFocusPolicy: 6 checks passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
