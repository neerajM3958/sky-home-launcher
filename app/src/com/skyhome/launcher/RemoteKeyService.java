package com.skyhome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    /**
     * Shows or hides the Quick Settings card from ADB, for screenshots and tests (a press of the remote can't be sent from a
     * computer): `adb shell am broadcast -a com.skyhome.launcher.QUICK_SETTINGS --es do show` (or hide, toggle). Registered with
     * the DUMP permission, which the shell has and ordinary apps don't, so no other app can open the card.
     */
    private final BroadcastReceiver fromAdb = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String what = String.valueOf(i.getStringExtra("do"));
            if (what.equals("show")) QuickCard.show(RemoteKeyService.this);
            else if (what.equals("hide")) QuickCard.close();
            else QuickCard.toggle(RemoteKeyService.this);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        registerReceiver(fromAdb, new IntentFilter(QuickCard.ACTION), "android.permission.DUMP", null);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    /** A window of another app coming to the front closes the Quick Settings card. */
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (QuickCard.isOpen() && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            QuickCard.closeFor(event.getPackageName().toString());
        }
    }

    private void stopAdb() {
        try {
            unregisterReceiver(fromAdb);
        } catch (RuntimeException ignored) {      // never registered
        }
    }

    @Override public boolean onUnbind(Intent intent) {
        stopAdb();
        QuickCard.close();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        QuickCard.close();
        super.onDestroy();
    }

    @Override public void onInterrupt() {}
}
