/*
 * UpdateInstaller — second stage of the OTA self-update (double-commit
 * pattern).
 *
 * Flow (PackageInstaller, API 24–30 semantics used on these builds):
 *   1. MotionWakeService.apiUpdate() opens a MODE_FULL_INSTALL session,
 *      writes the APK bytes, fseal(), then
 *      session.commit(installIntentSender) with installIntent = THIS
 *      activity (started as a normal task) carrying the session id.
 *   2. PackageInstallerService launches this activity as the
 *      "installation confirmation" step (instead of its own UI, since the
 *      app supplied its own install intent).
 *   3. We immediately perform the REAL commit: session.commit() with the
 *      ACTION_COMMIT broadcast PendingIntent. From PIS's point of view the
 *      install is now user-confirmed — no UI is ever shown, which is what
 *      an unattended wall tablet needs.
 *   4. The install replaces our package; our process dies; the
 *      MY_PACKAGE_REPLACED broadcast (BootReceiver -> BootStarter) brings
 *      the service back up. UpdateDoneReceiver records the applied state
 *      for /update/status.
 *
 * The activity is invisible (translucent, excluded from recents) and
 * finishes itself right after issuing stage 2.
 */
package com.egraf.motionwake;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.util.Log;

public class UpdateInstaller extends Activity {

    private static final String TAG = "UpdateInstaller";

    public static final int STAGE2_REQUEST = 4001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            int sid = getIntent().getIntExtra("sid", -1);
            // The "package_installer" context service is not registered on
            // these builds; reach the session through the same reflective
            // bridge used by apiUpdate (ActivityThread.sPackageManager).
            Object pis = PisBridge.installer();
            Object session = (sid > 0 && pis != null)
                    ? PisBridge.openSession(pis, sid) : null;
            if (session == null) {
                Log.w(TAG, "session " + sid + " already gone before stage-2 commit");
                return;
            }
            Intent commitIntent = new Intent(PackageInstaller.ACTION_SESSION_COMMITTED);
            PendingIntent commitPI = PendingIntent.getBroadcast(this, STAGE2_REQUEST,
                    commitIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PisBridge.sessionCommit(session, commitPI.getIntentSender());
            Log.i(TAG, "stage-2 commit issued for session " + sid
                    + " — system will install now");
        } catch (Exception e) {
            Log.w(TAG, "stage-2 commit failed", e);
        } finally {
            finish();
        }
    }
}
