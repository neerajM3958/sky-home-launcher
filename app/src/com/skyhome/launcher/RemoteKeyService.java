package com.skyhome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    /** A window of another app coming to the front closes the Quick Settings card. */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (QuickCard.isOpen() && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            QuickCard.closeFor(event.getPackageName().toString());
        }
    }

    @Override public boolean onUnbind(Intent intent) {
        QuickCard.close();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        QuickCard.close();
        super.onDestroy();
    }

    @Override public void onInterrupt() {}
}
