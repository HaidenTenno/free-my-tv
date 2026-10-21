package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

/** The home-screen shortcut and the remote use the same native antenna-TV route. */
final class LiveTv {
    static final String TARGET = "@live_tv";
    static final String READ_CHANNELS = "android.permission.READ_TV_LISTINGS";
    static final String[] PACKAGES = {
            "com.tcl.tv", "com.mediatek.wwtv.tvcenter", "com.google.android.tv", "com.sony.dtv.tvx"
    };

    private LiveTv() {}

    static Intent intent(Context c) {
        PackageManager pm = c.getPackageManager();
        try {
            String tuner = tunerInput(c);
            if (tuner != null) {
                // Explicit MIME type and native component keep web/video players from taking this request.
                // A tuner needs a channel-list URI; a passthrough URI is only for inputs such as HDMI.
                Intent channels = new Intent(Intent.ACTION_VIEW).setDataAndType(
                        TvContract.buildChannelsUriForInput(tuner), TvContract.Channels.CONTENT_TYPE);
                for (String pkg : PACKAGES) {
                    ResolveInfo match = pm.resolveActivity(new Intent(channels).setPackage(pkg),
                            PackageManager.MATCH_DEFAULT_ONLY);
                    if (launchable(match)) {
                        if (RemoteProfile.TCL_G08 && "com.tcl.tv".equals(pkg))
                            return tclIntent(c, tuner, match);
                        return explicit(channels, match);
                    }
                }
                // Other TV brands can expose a native handler. Require the channel MIME type itself,
                // not a generic video/content handler or a browser accepting */*.
                for (ResolveInfo match : pm.queryIntentActivities(channels,
                        PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER)) {
                    if (!launchable(match) || match.filter == null) continue;
                    for (int i = 0; i < match.filter.countDataTypes(); i++) {
                        if (TvContract.Channels.CONTENT_TYPE.equals(match.filter.getDataType(i)))
                            return explicit(channels, match);
                    }
                }
            }
            for (String pkg : PACKAGES) {
                Intent app = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (app == null) app = pm.getLaunchIntentForPackage(pkg);
                if (app != null) return app;
            }
        } catch (RuntimeException e) {
            AppLog.w("couldn't resolve antenna TV: " + e.getMessage());
        }
        return null;
    }

    /** TCL mixes the last internet channel into its tuner route, even when its provider is disabled. */
    private static Intent tclIntent(Context c, String tuner, ResolveInfo tv) {
        if (c.checkSelfPermission(READ_CHANNELS) == PackageManager.PERMISSION_GRANTED) {
            try (Cursor rows = c.getContentResolver().query(TvContract.buildChannelsUriForInput(tuner),
                    new String[] {TvContract.Channels._ID, TvContract.Channels.COLUMN_BROWSABLE,
                            TvContract.Channels.COLUMN_TYPE},
                    null, null, null)) {
                if (rows != null) {
                    long channel = -1;
                    while (rows.moveToNext()) {
                        if (rows.getInt(1) == 0) continue;
                        String type = rows.getString(2);
                        if (TvContract.Channels.TYPE_DVB_T.equals(type) || TvContract.Channels.TYPE_DVB_T2.equals(type)
                                || TvContract.Channels.TYPE_ATSC_T.equals(type) || TvContract.Channels.TYPE_ISDB_T.equals(type)
                                || TvContract.Channels.TYPE_DTMB.equals(type)) {
                            channel = rows.getLong(0);
                            break;
                        }
                        // Prefer digital terrestrial TV, with analogue RF as a fallback. HW2 also
                        // contains cable channels, so the input ID alone is not enough.
                        if (channel < 0 && (TvContract.Channels.TYPE_PAL.equals(type)
                                || TvContract.Channels.TYPE_SECAM.equals(type) || TvContract.Channels.TYPE_NTSC.equals(type)))
                            channel = rows.getLong(0);
                    }
                    if (channel >= 0) {
                        return explicit(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUri(channel),
                                TvContract.Channels.CONTENT_ITEM_TYPE), tv);
                    }
                }
            } catch (RuntimeException e) {
                AppLog.w("couldn't read antenna channels: " + e.getMessage());
            }
        }
        // No readable antenna channel: show options, never start a scan. READ_TV_LISTINGS
        // does not expose channels whose provider hides them from other apps.
        Intent setup = new Intent("com.tcl.tv.action.TV_AUTO_SCAN_FROM_TV")
                .setPackage("com.tcl.tv").putExtra("isToScanProgress", false)
                .putExtra("back_to_setting", false);
        ResolveInfo match = c.getPackageManager().resolveActivity(setup, PackageManager.MATCH_DEFAULT_ONLY);
        if (launchable(match)) return explicit(setup, match);
        // Firmware without the setup action can still accept the native tuner command.
        return explicit(new Intent(Intent.ACTION_MAIN).putExtra("inputId", tuner)
                .putExtra("is_auto_tune_iptv", false), tv);
    }

    private static boolean launchable(ResolveInfo match) {
        return match != null && match.activityInfo != null && match.activityInfo.exported
                && match.activityInfo.enabled;
    }

    private static Intent explicit(Intent source, ResolveInfo match) {
        return new Intent(source).setComponent(new ComponentName(
                match.activityInfo.packageName, match.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private static String tunerInput(Context c) {
        try {
            return findTunerInput(c);
        } catch (RuntimeException e) {
            AppLog.w("couldn't enumerate TV inputs: " + e.getMessage());
            return null; // Native TV apps can still be launched without a readable input list.
        }
    }

    private static String findTunerInput(Context c) {
        TvInputManager manager = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (manager == null) return null;
        String best = null;
        int bestScore = -1;
        for (TvInputInfo info : manager.getTvInputList()) {
            if (info.getType() != TvInputInfo.TYPE_TUNER || info.isPassthroughInput()) continue;
            String id = info.getId();
            boolean system = info.getServiceInfo() != null && info.getServiceInfo().applicationInfo != null
                    && (info.getServiceInfo().applicationInfo.flags
                    & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            int score = system ? 10 : 0;
            if (system && id.contains("/HW")) score += 20;
            // These TVs expose several tuner inputs. Select terrestrial digital TV, not the first
            // (which on Haier may be AnalogInputService, CableInputService or SatelliteInputService).
            if (id.equals("com.tcl.tvinput/.TunerInputService/HW2")
                    || id.equals("com.mediatek.tvinput/.tuner.TunerInputService/HW0")) score += 100;
            if (score > bestScore || (score == bestScore && (best == null || id.compareTo(best) < 0))) {
                best = id;
                bestScore = score;
            }
        }
        return best;
    }
}
