package in.abragroup.flowx;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/**
 * "Test Notifications" support for every phone brand.
 * Exposed to the CRM pages as window.AbraNative (test-notifications.php):
 *   status()            → JSON: brand, permission, channel, battery restriction…
 *   requestPermission() → Android 13+ "Allow notifications?" dialog
 *   testLocal()         → shows a notification straight from the phone (no server)
 *   openNotificationSettings(), openBatterySettings(), openAutostart()
 * Vivo / Oppo / Realme / Xiaomi / Huawei / OnePlus / Samsung stop background apps
 * from showing notifications unless "Auto-start" / "Background" / battery
 * "No restrictions" is allowed — openAutostart() opens the right page per brand.
 */
public class NotifyHelper {
    public static final String CHANNEL_ID = "abra_alerts";
    private final Activity act;

    public NotifyHelper(Activity act) { this.act = act; }

    /** High-priority channel with sound + vibration (used by Firebase too, see manifest). */
    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Abra alerts (tickets & updates)", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("New tickets assigned to you, ticket status changes and other important updates");
        ch.enableVibration(true);
        ch.enableLights(true);
        ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        nm.createNotificationChannel(ch);
    }

    private boolean permissionGranted() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        return NotificationManagerCompat.from(act).areNotificationsEnabled();
    }

    @JavascriptInterface
    public String status() {
        try {
            ensureChannel(act);
            JSONObject o = new JSONObject();
            o.put("brand", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
            o.put("model", Build.MODEL == null ? "" : Build.MODEL);
            o.put("android", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("permission", permissionGranted());
            boolean channelOn = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = ((NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE)).getNotificationChannel(CHANNEL_ID);
                channelOn = ch != null && ch.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT;
            }
            o.put("channel", channelOn);
            boolean batteryFree = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) act.getSystemService(Context.POWER_SERVICE);
                batteryFree = pm.isIgnoringBatteryOptimizations(act.getPackageName());
            }
            o.put("batteryUnrestricted", batteryFree);
            o.put("autostartSupported", autostartIntent() != null);
            o.put("version", act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName);
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @JavascriptInterface
    public void requestPermission() {
        act.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(act, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4711);
            } else {
                openNotificationSettings();
            }
        });
    }

    @JavascriptInterface
    public boolean testLocal() {
        ensureChannel(act);
        if (!permissionGranted()) return false;
        Intent open = new Intent(act, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(act, 1, open, flags);
        NotificationCompat.Builder b = new NotificationCompat.Builder(act, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("✅ Abra FlowX — phone test")
                .setContentText("This phone can show Abra FlowX notifications.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(act).notify((int) (System.currentTimeMillis() % 100000), b.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    @JavascriptInterface
    public void openNotificationSettings() {
        act.runOnUiThread(() -> {
            Intent i;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.getPackageName()));
            }
            start(i, appDetails());
        });
    }

    @JavascriptInterface
    public void openBatterySettings() {
        act.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + act.getPackageName()));
                start(i, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } else {
                start(appDetails(), null);
            }
        });
    }

    @JavascriptInterface
    public void openAutostart() {
        act.runOnUiThread(() -> {
            Intent i = autostartIntent();
            start(i != null ? i : appDetails(), appDetails());
        });
    }

    @JavascriptInterface
    public void openAppInfo() { act.runOnUiThread(() -> start(appDetails(), null)); }

    private Intent appDetails() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.getPackageName()));
    }

    private void start(Intent i, Intent fallback) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            if (fallback != null) {
                try { fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(fallback); } catch (Exception ignored) { }
            }
        }
    }

    /** Brand-specific "auto-start / background" screens; first one that exists on this phone. */
    private Intent autostartIntent() {
        String[][] pages = {
            // Vivo / iQOO
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
            {"com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"},
            // Xiaomi / Redmi / Poco
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            // Oppo / Realme / OnePlus (ColorOS)
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
            // Huawei / Honor
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            // Samsung
            {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
            // Asus
            {"com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"},
            // Letv
            {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
        };
        PackageManager pm = act.getPackageManager();
        for (String[] p : pages) {
            Intent i = new Intent().setComponent(new ComponentName(p[0], p[1]));
            if (pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null) return i;
        }
        return null;
    }
}
