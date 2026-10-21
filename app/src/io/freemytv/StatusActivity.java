package io.freemytv;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Full status text has a native scroll container: long errors and notices cannot fall below a canvas. */
public final class StatusActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView body;
    private boolean resumed;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            String value = status();
            if (!value.contentEquals(body.getText())) body.setText(value);
            handler.postDelayed(this, 2000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xFF0B1429));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFocusable(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = dp(52), top = dp(24);
        content.setPadding(side, top, side, top);
        TextView title = new TextView(this);
        title.setText(Ui.tr("Estado e avisos", "Status and notices"));
        title.setTypeface(Ui.displaySemibold);
        title.setTextColor(0xFFF2F5FC);
        title.setTextSize(28);
        title.setAccessibilityHeading(true);
        content.addView(title);
        TextView hint = new TextView(this);
        hint.setText(Ui.tr("↑ ↓ para rolar · Voltar para os ajustes", "↑ ↓ to scroll · Back to settings"));
        hint.setTypeface(Ui.regular);
        hint.setTextColor(0xFFB3BED2);
        hint.setTextSize(15);
        content.addView(hint);
        body = new TextView(this);
        body.setTypeface(Ui.regular);
        body.setTextColor(0xFFF2F5FC);
        body.setTextSize(18);
        body.setLineSpacing(dp(4), 1);
        body.setPadding(0, dp(24), 0, 0);
        content.addView(body, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        scroll.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.post(poll);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private String status() {
        JSONObject state = HomeMode.state(this), recs = Recs.status(this);
        StringBuilder text = new StringBuilder();
        long now = SystemClock.elapsedRealtime(), busyAt = state.optLong("busyAt");
        String busy = now >= busyAt && now - busyAt <= 10 * 60_000L ? state.optString("busy") : "";
        if (!busy.isEmpty()) text.append(localize(busy)).append("\n\n");
        String error = state.optString("error");
        if (!error.isEmpty()) text.append(localize(error)).append("\n\n");

        text.append(Ui.tr("COMPARAÇÃO", "COMPARISON")).append("\n");
        JSONObject metrics = state.optJSONObject("metrics");
        JSONObject original = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
        JSONObject free = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
        String[][] rows = {{Ui.tr("Botão Início", "Home button"), "homeMs"},
                {Ui.tr("Memória livre", "Free memory"), "memAvailableMB"},
                {Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"}};
        for (String[] row : rows) {
            text.append(row[0]).append("\n  ").append(Ui.tr("Original", "Original")).append(": ")
                    .append(metric(original, row[1])).append("  ·  Free My TV: ").append(metric(free, row[1])).append("\n");
        }
        text.append("\n").append(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS")).append("\n");
        String recError = recs.optString("error");
        int count = recs.optInt("count");
        if (!Recs.hasKey(this)) text.append(Ui.tr("Sem chave do TMDB: veja o README (config.properties).",
                "No TMDB key: see the README (config.properties)."));
        else if (!recError.isEmpty()) text.append(Ui.tr("Erro: ", "Error: ")).append(localize(recError));
        else if (count > 0) text.append(Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                "On · " + count + " titles from the TV's apps, 3 new ones every hour."));
        else text.append(Ui.tr("Buscando os títulos…", "Fetching titles…"));
        text.append("\n").append(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                "This product uses the TMDB API but is not endorsed or certified by TMDB."));
        text.append("\n\n").append(Ui.tr("AVISOS", "NOTICES")).append("\n");
        JSONArray notices = state.optJSONArray("notices");
        boolean any = false;
        for (int i = 0; notices != null && i < notices.length(); i++) {
            JSONObject notice = notices.optJSONObject(i);
            if (notice == null) continue;
            any = true;
            text.append(ago(notice.optLong("at"))).append(" · ").append(localize(notice.optString("text"))).append("\n\n");
        }
        if (!any) text.append(Ui.tr("Nenhum ainda.", "None yet."));
        return text.toString();
    }

    private static String localize(String value) { return Ui.ru ? Russian.text(value) : value; }

    private static String metric(JSONObject metrics, String key) {
        if (metrics == null || !metrics.has(key)) return "—";
        long value = metrics.optLong(key);
        if (key.equals("homeMs")) return value + (Ui.ru ? " мс" : " ms");
        if (key.equals("memAvailableMB") && value >= 1024)
            return String.format(Ui.locale(), Ui.ru ? "%.2f ГБ" : "%.2f GB", value / 1024.0);
        return value + (Ui.ru ? " МБ" : " MB");
    }

    private static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (minutes < 1) return Ui.tr("agora", "now");
        if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
        if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
        return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
    }
}
