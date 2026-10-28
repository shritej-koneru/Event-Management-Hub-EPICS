package com.epics.bitchat.mesh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Entry point for server-originated pushes — the internet backhaul into the
 * BLE mesh. The production bridge (FCM or a WebSocket client) lands here:
 * it receives the server event and hands it to
 * {@link MeshManager#ingestFromInternet}, which floods it to nearby phones
 * with a stable id for the 5-second rebroadcast window.
 *
 * <p>It is exported so the demo/test harness can inject a push directly:
 * <pre>
 *   adb shell am broadcast -a com.epics.bitchat.INGEST \
 *       -e event venue-change-4711 -e payload "ROUTE:Hall2"
 * </pre>
 * (A spoofed push can only inject an ordinary announce — the same thing a
 * phone physically in range could broadcast.)
 */
public class IngestReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.epics.bitchat.INGEST";
    private static final String TAG = "BitChatMesh";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        String event = intent.getStringExtra("event");
        String payload = intent.getStringExtra("payload");
        if (event == null || event.isEmpty() || payload == null) {
            Log.w(TAG, "ingest broadcast missing event/payload extras");
            return;
        }
        MeshManager manager = MeshManager.get(context);
        String wireId = manager.wireMsgIdForExternal(event);
        boolean knownBefore = manager.hasSeenExternal(event);
        boolean accepted = manager.ingestFromInternet(event, payload);
        if (accepted && knownBefore) {
            Log.i(TAG, "ingest " + event + " (" + wireId
                    + ") -> ignored: event id already seen (no re-flood)");
        } else if (accepted) {
            Log.i(TAG, "ingest " + event + " (" + wireId + ") -> flooding over BLE");
        } else {
            Log.i(TAG, "ingest " + event + " -> rejected (oversize or mesh not running)");
        }
    }
}
