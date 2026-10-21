package io.freemytv;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Live TV power settings, with drafts kept here until the user explicitly applies them. */
public final class PowerSettingsActivity extends Activity {
    private static final int BACKGROUND = 0xFF0B1429, PANEL = 0xFF172540;
    private static final int INK = 0xFFF2F5FC, DIM = 0xFFB3BED2, ERROR = 0xFFFFC266;
    private static final long[][] MINUTES = {
            {1, 2, 5, 10, 15, 30, 60, 120, 240},
            {5, 10, 15, 30, 60, 120, 240},
            {30, 60, 120, 240, 480}
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TimerRow[] rows = new TimerRow[3];
    private PowerTimers draft;
    private PowerSettings.Snapshot actual;
    private Button apply, refresh;
    private TextView message;
    private Dialog picker;
    private boolean busy, alive = true;
    private int generation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(BACKGROUND));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(52), dp(24), dp(52), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        content.addView(text(Ui.tr("Tela de descanso e suspensão", "Screensaver and sleep"), 28, Ui.displaySemibold, INK));
        TextView explanation = text(Ui.tr(
                "Os dois tempos de inatividade contam desde a última interação, não um após o outro. A suspensão deve ocorrer depois da tela de descanso.",
                "Both idle timers start at your last interaction, not one after the other. Sleep must be later than the screensaver."),
                16, Ui.regular, DIM);
        add(content, explanation, 8);
        rows[0] = new TimerRow(0, Ui.tr("Tela de descanso", "Screensaver"),
                Ui.tr("Após inatividade", "After inactivity"));
        rows[1] = new TimerRow(1, Ui.tr("Suspensão por inatividade", "Sleep after inactivity"),
                Ui.tr("Tempo total desde a última interação", "Total time since your last interaction"));
        rows[2] = new TimerRow(2, Ui.tr("Suspender mesmo durante a reprodução", "Sleep even while playing"),
                Ui.tr("Sem interação com o controle", "Without remote interaction"));
        for (TimerRow row : rows) add(content, row, 10);
        rows[2].setVisibility(View.GONE);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        apply = button(Ui.tr("Aplicar", "Apply"));
        refresh = button(Ui.tr("Atualizar", "Refresh"));
        actions.addView(apply, new LinearLayout.LayoutParams(dp(180), dp(46)));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(180), dp(46));
        refreshParams.leftMargin = dp(12);
        actions.addView(refresh, refreshParams);
        add(content, actions, 16);
        message = text("", 15, Ui.regular, DIM);
        message.setMinLines(2);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, message, 12);
        add(content, text(Ui.tr("OK — escolher · Voltar — descartar alterações", "OK to choose · Back discards unapplied changes"),
                14, Ui.regular, DIM), 4);
        apply.setOnClickListener(v -> applyDraft());
        refresh.setOnClickListener(v -> load());
        setContentView(scroll);
        load();
    }

    private TextView text(String value, int size, Typeface face, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTypeface(face);
        view.setTextColor(color);
        return view;
    }

    private void add(LinearLayout parent, View view, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(marginTop);
        parent.addView(view, params);
    }

    private Button button(String title) {
        Button button = new Button(this);
        button.setId(View.generateViewId());
        button.setText(title);
        button.setTextSize(16);
        button.setTypeface(Ui.semibold);
        button.setAllCaps(false);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setOnFocusChangeListener((v, focused) -> styleButton(button));
        styleButton(button);
        return button;
    }

    private void styleButton(Button button) {
        button.setBackground(shape(button.hasFocus() ? INK : PANEL));
        button.setTextColor(button.hasFocus() ? BACKGROUND : INK);
        button.setAlpha(button.isEnabled() ? 1 : 0.45f);
    }

    private GradientDrawable shape(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(10));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class TimerRow extends LinearLayout {
        final PowerTimers.Kind kind;
        final TextView title, hint, value;

        TimerRow(int index, String label, String detail) {
            super(PowerSettingsActivity.this);
            this.kind = PowerTimers.Kind.values()[index];
            setId(View.generateViewId());
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(18), dp(12), dp(18), dp(12));
            setMinimumHeight(dp(68));
            setFocusable(true);
            setClickable(true);
            LinearLayout labels = new LinearLayout(PowerSettingsActivity.this);
            labels.setOrientation(VERTICAL);
            title = text(label, 18, Ui.medium, INK);
            hint = text(detail, 14, Ui.regular, DIM);
            labels.addView(title);
            labels.addView(hint);
            addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            value = text("…", 18, Ui.semibold, INK);
            value.setGravity(Gravity.RIGHT);
            value.setPadding(dp(20), 0, 0, 0);
            addView(value, new LinearLayout.LayoutParams(-2, -2));
            setOnClickListener(v -> choose(index));
            setOnFocusChangeListener((v, focused) -> updateStyle());
            updateStyle();
        }

        void updateStyle() {
            boolean focused = hasFocus();
            setBackground(shape(focused ? INK : PANEL));
            title.setTextColor(focused ? BACKGROUND : INK);
            hint.setTextColor(focused ? 0xFF48546C : DIM);
            value.setTextColor(focused ? BACKGROUND : INK);
            setAlpha(isEnabled() ? 1 : 0.45f);
        }
    }

    private void choose(int row) {
        if (busy || actual == null) return;
        List<Long> values = new ArrayList<>();
        values.add(-1L);
        for (long minutes : MINUTES[row]) values.add(minutes * 60_000L);
        long original = original(row);
        if (!values.contains(original)) values.add(original);
        String[] labels = new String[values.size()];
        int selected = 0;
        for (int i = 0; i < values.size(); i++) {
            long value = values.get(i);
            labels[i] = format(value, row);
            if (value == original && value != -1 && !preset(row, value))
                labels[i] += Ui.tr(" (valor atual personalizado)", " (current custom value)");
            if (value == draft.get(rows[row].kind)) selected = i;
        }
        showPicker(row, values, labels, selected);
    }

    private void showPicker(int row, List<Long> values, String[] labels, int selected) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(20), dp(24), dp(20));
        TextView title = text(rows[row].title.getText().toString(), 22, Ui.displaySemibold, INK);
        title.setPadding(0, 0, 0, dp(12));
        title.setAccessibilityHeading(true);
        content.addView(title);

        // Some TV alert themes clip the button panel when the list fills the window.
        // Only the choices scroll; the title and Cancel keep their measured height.
        ScrollView scroll = new ScrollView(this);
        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(labels[i]);
            choice.setTextSize(18);
            choice.setTypeface(Ui.regular);
            choice.setMinHeight(dp(48));
            choice.setPadding(dp(12), dp(8), dp(12), dp(8));
            choice.setTextColor(INK);
            choice.setButtonTintList(ColorStateList.valueOf(INK));
            choice.setOnFocusChangeListener((v, focused) -> {
                choice.setBackground(shape(focused ? INK : PANEL));
                choice.setTextColor(focused ? BACKGROUND : INK);
                choice.setButtonTintList(ColorStateList.valueOf(focused ? BACKGROUND : INK));
            });
            choice.setOnClickListener(v -> {
                draft = draft.with(rows[row].kind, values.get(which));
                render();
                String error = validation();
                status(error.isEmpty() ? Ui.tr("Alterações ainda não aplicadas.",
                        "Changes have not been applied yet.") : error, !error.isEmpty());
                dialog.dismiss();
            });
            choices.addView(choice, new RadioGroup.LayoutParams(-1, -2));
            if (i == selected) choice.setChecked(true);
        }
        scroll.addView(choices, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button cancel = button(Ui.tr("Cancelar", "Cancel"));
        cancel.setMinHeight(dp(48));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, -2);
        cancelParams.topMargin = dp(12);
        content.addView(cancel, cancelParams);
        cancel.setOnClickListener(v -> dialog.dismiss());
        choices.getChildAt(labels.length - 1).setNextFocusDownId(cancel.getId());
        cancel.setNextFocusUpId(choices.getChildAt(labels.length - 1).getId());
        dialog.setContentView(content);
        dialog.setOnDismissListener(d -> picker = null);
        picker = dialog;
        dialog.show();
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(shape(PANEL));
        View screen = getWindow().getDecorView();
        window.setLayout(Math.min(dp(560), screen.getWidth() - dp(48)),
                Math.min(dp(144 + 48 * labels.length), screen.getHeight() - dp(48)));
        choices.getChildAt(selected).requestFocus();
    }

    private boolean preset(int row, long value) {
        for (long minutes : MINUTES[row]) if (value == minutes * 60_000L) return true;
        return false;
    }

    private String format(long value, int row) {
        if (value < 0) return row == 0 ? Ui.tr("Desligada", "Off") : Ui.tr("Nunca", "Never");
        long minutes = value / 60_000, seconds = value % 60_000 / 1000;
        String result = minutes + Ui.tr(" min", " min");
        if (seconds != 0) result += " " + seconds + Ui.tr(" s", " sec");
        if (value % 1000 != 0) result = "≈ " + result;
        return result;
    }

    private long original(int row) {
        return actual.timers().get(rows[row].kind);
    }

    private boolean changed() {
        return actual != null && !draft.sameAs(actual.timers());
    }

    private String validation() {
        String error = draft == null ? "Read the current settings first." : draft.validationError(actual == null ? null : actual.timers());
        switch (error) {
            case "Read the current settings first.":
                return Ui.tr("Leia os ajustes atuais primeiro.", error);
            case "Choose a delay of at least one minute, or Never.":
                return Ui.tr("Escolha um intervalo de pelo menos um minuto, ou Nunca.", error);
            case "Sleep must start later than the screensaver.":
                return Ui.tr("A suspensão deve ocorrer depois da tela de descanso.", error);
            default: return error;
        }
    }

    private void render() {
        for (TimerRow row : rows) {
            row.setEnabled(!busy && actual != null);
            if (actual != null) row.value.setText(format(draft.get(row.kind), row.kind.ordinal()) + "  ›");
            row.updateStyle();
        }
        rows[2].setVisibility(actual != null && actual.attentiveSupported ? View.VISIBLE : View.GONE);
        apply.setEnabled(!busy && changed());
        refresh.setEnabled(!busy);
        styleButton(apply);
        styleButton(refresh);
        int last = actual != null && actual.attentiveSupported ? 2 : 1;
        for (int i = 0; i <= last; i++) {
            rows[i].setNextFocusUpId(rows[Math.max(0, i - 1)].getId());
            rows[i].setNextFocusDownId(i < last ? rows[i + 1].getId() : (apply.isEnabled() ? apply : refresh).getId());
        }
        apply.setNextFocusUpId(rows[last].getId());
        refresh.setNextFocusUpId(rows[last].getId());
        apply.setNextFocusRightId(refresh.getId());
        refresh.setNextFocusLeftId(apply.isEnabled() ? apply.getId() : refresh.getId());
    }

    private void status(String value, boolean error) {
        message.setText(value);
        message.setTextColor(error ? ERROR : DIM);
    }

    private void accept(PowerSettings.Snapshot state) {
        actual = state;
        draft = state.timers();
    }

    private void load() {
        if (busy) return;
        busy = true;
        final int token = ++generation;
        status(Ui.tr("Lendo os ajustes da TV…", "Reading TV settings…"), false);
        render();
        Context app = getApplicationContext();
        worker.execute(() -> {
            try {
                PowerSettings.Snapshot state = PowerSettings.read(app);
                runOnUiThread(() -> {
                    if (!alive || token != generation) return;
                    accept(state);
                    busy = false;
                    render();
                    status(Ui.tr("Valores atuais da TV. As alterações só são salvas ao aplicar.",
                            "Current TV values. Changes are saved only when you apply them."), false);
                    rows[0].requestFocus();
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    if (!alive || token != generation) return;
                    actual = null;
                    busy = false;
                    render();
                    status(Ui.tr("Não foi possível ler os ajustes: ", "Couldn't read settings: ") + detail(e), true);
                    refresh.requestFocus();
                });
            }
        });
    }

    private void applyDraft() {
        if (busy || actual == null || !changed()) return;
        String error = validation();
        if (!error.isEmpty()) { status(error, true); return; }
        busy = true;
        final int token = ++generation;
        PowerSettings.Snapshot expected = actual;
        long saver = draft.saverMs, sleep = draft.sleepMs, attentive = draft.attentiveMs;
        status(Ui.tr("Aplicando…", "Applying…"), false);
        render();
        Context app = getApplicationContext();
        worker.execute(() -> {
            PowerSettings.Snapshot state = null;
            String failure = null;
            try {
                state = PowerSettings.apply(app, expected, saver, sleep, attentive);
            } catch (IOException | RuntimeException e) {
                failure = Ui.tr("Não foi possível aplicar: ", "Couldn't apply settings: ") + detail(e);
                try {
                    state = PowerSettings.read(app);
                    failure += "\n" + Ui.tr("Os valores reais da TV foram recarregados.", "The TV's actual values have been reloaded.");
                } catch (IOException | RuntimeException reread) {
                    failure += "\n" + Ui.tr("Não foi possível reler os ajustes. Use Atualizar.", "Couldn't reread settings. Use Refresh.");
                }
            }
            final PowerSettings.Snapshot result = state;
            final String problem = failure;
            runOnUiThread(() -> {
                if (!alive || token != generation) return;
                if (result != null) accept(result); else actual = null;
                busy = false;
                render();
                status(problem == null ? Ui.tr("Ajustes aplicados e verificados.", "Settings applied and verified.") : problem,
                        problem != null);
                if (actual == null) refresh.requestFocus(); else rows[0].requestFocus();
            });
        });
    }

    private String detail(Exception error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : Ui.ru ? Russian.text(value) : value;
    }

    @Override protected void onDestroy() {
        alive = false;
        generation++;
        if (picker != null) picker.dismiss();
        // A confirmed write must finish its verification/rollback even if Back closes this screen.
        worker.shutdown();
        super.onDestroy();
    }
}
