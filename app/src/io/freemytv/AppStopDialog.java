package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** A fresh, visible list is confirmed before stopping apps. Nothing is scheduled for a later background run. */
final class AppStopDialog {
    private final Activity activity;
    private final Consumer<String> report;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AppStopper operation;
    private AlertDialog dialog;
    private boolean stopping;

    AppStopDialog(Activity activity, Consumer<String> report) {
        this.activity = activity;
        this.report = report;
    }

    void show() {
        if (operation != null || dialog != null && dialog.isShowing()) return;
        AppStopper run = new AppStopper(activity.getApplicationContext());
        operation = run;
        busy(Ui.tr("Verificando os aplicativos abertos…", "Checking open apps…"));
        new Thread(() -> {
            try {
                List<AppStopper.App> apps = run.prepare();
                main.post(() -> {
                    if (!current(run)) return;
                    dismissDialog();
                    if (apps.isEmpty()) {
                        cancel();
                        report.accept(Ui.tr("Nenhum aplicativo aberto para fechar.", "No open apps to close."));
                    } else confirm(run, apps);
                });
            } catch (IOException | RuntimeException e) {
                failed(run, e);
            }
        }, "free-my-tv-open-apps").start();
    }

    private void confirm(AppStopper run, List<AppStopper.App> apps) {
        StringBuilder message = new StringBuilder(Ui.tr(
                "Estes aplicativos serão encerrados e iniciarão novamente quando abertos:",
                "These apps will stop and start fresh the next time you open them:"));
        boolean vpn = false;
        for (AppStopper.App app : apps) {
            message.append("\n• ").append(app.label);
            vpn |= app.vpn;
        }
        if (vpn) message.append("\n\n").append(Ui.tr("A conexão VPN também será desligada.",
                "The VPN connection will also stop."));
        message.append("\n\n").append(Ui.tr("Os dados e as contas serão mantidos.", "App data and accounts will be kept."));
        dialog = builder().setMessage(message.toString())
                .setPositiveButton(Ui.tr("Fechar todos", "Close all"), (d, which) -> stop(run, apps))
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), (d, which) -> cancel())
                .setOnCancelListener(d -> cancel()).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private void stop(AppStopper run, List<AppStopper.App> apps) {
        if (!current(run)) return;
        stopping = true;
        dismissDialog();
        busy(Ui.tr("Fechando aplicativos…", "Closing apps…"));
        new Thread(() -> {
            try {
                AppStopper.Result result = run.stop(apps);
                main.post(() -> {
                    if (!current(run)) return;
                    cancel();
                    String summary = Ui.tr("Aplicativos fechados: ", "Apps closed: ") + result.stopped.size() + ".";
                    if (result.failed.isEmpty()) {
                        report.accept(summary);
                    } else {
                        StringBuilder message = new StringBuilder(summary).append("\n\n")
                                .append(Ui.tr("Não foi possível fechar:", "Couldn't close:"));
                        for (AppStopper.App app : result.failed) message.append("\n• ").append(app.label);
                        notice(message.toString());
                    }
                });
            } catch (IOException | RuntimeException e) {
                failed(run, e);
            }
        }, "free-my-tv-close-apps").start();
    }

    private void failed(AppStopper run, Exception error) {
        AppLog.w("close apps: " + error.getMessage());
        main.post(() -> {
            if (!current(run)) return;
            boolean partial = stopping;
            cancel();
            String message = Ui.tr("Não foi possível concluir. Tente novamente e verifique a permissão de depuração USB do Free My TV.",
                    "Couldn't finish. Try again and check Free My TV's USB debugging permission.");
            if (partial) message += "\n\n" + Ui.tr("Alguns aplicativos podem já ter sido fechados.",
                    "Some apps may already have closed.");
            notice(message);
        });
    }

    private void busy(String message) {
        dialog = builder().setMessage(message)
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), (d, which) -> cancelByUser())
                .setOnCancelListener(d -> cancelByUser()).create();
        dialog.show();
    }

    private void notice(String message) {
        dialog = builder().setMessage(message).setPositiveButton("OK", (d, which) -> dismissDialog()).create();
        dialog.show();
    }

    private AlertDialog.Builder builder() {
        return new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Fechar aplicativos", "Close apps"));
    }

    private boolean current(AppStopper run) {
        return operation == run && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void cancelByUser() {
        boolean partial = stopping;
        cancel();
        if (partial) report.accept(Ui.tr("Operação cancelada. Alguns aplicativos podem já ter sido fechados.",
                "Cancelled. Some apps may already have closed."));
    }

    /** Called when Home is pressed, or when the activity leaves the foreground or is destroyed. */
    void cancel() {
        AppStopper run = operation;
        operation = null;
        stopping = false;
        if (run != null) run.cancel();
        dismissDialog();
    }

    private void dismissDialog() {
        AlertDialog old = dialog;
        dialog = null;
        if (old != null) old.dismiss();
    }
}
