package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.function.Consumer;

/** App management from All Apps. Uninstalling always goes through Android's confirmation screen. */
final class AppActionsDialog {
    private final Activity activity;
    private final Consumer<String> report;
    private final Runnable leaving, refresh;
    private AlertDialog dialog;

    AppActionsDialog(Activity activity, Consumer<String> report, Runnable leaving, Runnable refresh) {
        this.activity = activity;
        this.report = report;
        this.leaving = leaving;
        this.refresh = refresh;
    }

    void show(HomeApps.Item item, long openingDownTime) {
        cancel();
        if (item.kind != HomeApps.APP || item.pkg == null || item.pkg.equals(activity.getPackageName())) return;
        ApplicationInfo info = installed(item.pkg);
        if (info == null) return;
        boolean removable = removable(info);
        String details = Ui.tr("Informações do aplicativo", "App info");
        String[] actions = removable
                ? new String[] {Ui.tr("Desinstalar", "Uninstall"), details} : new String[] {details};
        dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(item.label)
                .setItems(actions, (d, index) -> {
                    if (removable && index == 0) uninstall(item.pkg);
                    else details(item.pkg);
                })
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                .create();
        // The press that opened this window belongs to the grid, including all repeats and its release.
        dialog.setOnKeyListener((d, code, event) -> event.getDownTime() == openingDownTime
                && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER));
        dialog.setOnDismissListener(d -> { if (dialog == d) dialog = null; });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private static boolean removable(ApplicationInfo info) {
        return (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
    }

    private ApplicationInfo installed(String pkg) {
        try {
            return activity.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            report.accept(Ui.tr("Este aplicativo não está mais instalado.", "This app is no longer installed."));
            refresh.run();
            return null;
        }
    }

    private void uninstall(String pkg) {
        ApplicationInfo info = installed(pkg);
        if (info == null) return;
        if (!removable(info)) {
            details(pkg);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", pkg, null));
        if (!open(intent)) details(pkg);
    }

    private void details(String pkg) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null));
        if (!open(intent)) report.accept(Ui.tr("Não foi possível abrir as configurações do aplicativo.",
                "Couldn't open app settings."));
    }

    private boolean open(Intent intent) {
        try {
            activity.startActivity(intent);
            leaving.run();
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("app actions: " + e.getMessage());
            return false;
        }
    }

    void cancel() {
        AlertDialog old = dialog;
        dialog = null;
        if (old != null) old.dismiss();
    }
}
