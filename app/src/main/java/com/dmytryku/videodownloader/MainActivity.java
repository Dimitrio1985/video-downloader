package com.dmytryku.videodownloader;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "video_downloader_prefs";
    private static final String IDS = "download_ids";
    private static final String DARK = "dark_mode";
    private LinearLayout root, content, historyBox;
    private EditText urlInput;
    private TextView statusText, themeLabel;
    private Switch themeSwitch;
    private boolean dark;
    private final Handler handler = new Handler();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = getPreferences(0).getBoolean(DARK, true);
        buildUi();
        refreshHistory();
    }

    private int bg() { return dark ? Color.rgb(16,19,24) : Color.rgb(247,248,250); }
    private int panel() { return dark ? Color.rgb(32,37,45) : Color.WHITE; }
    private int fg() { return dark ? Color.WHITE : Color.rgb(28,32,38); }
    private int muted() { return dark ? Color.rgb(169,180,194) : Color.rgb(95,104,116); }
    private int blue() { return Color.rgb(71,121,212); }
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(bg());
        root.setPadding(dp(18), dp(14), dp(18), dp(8));
        setContentView(root);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setOrientation(LinearLayout.HORIZONTAL);
        TextView logo = text("▶", 24, blue(), true); logo.setGravity(Gravity.CENTER); logo.setBackground(roundBg(blue(), 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48)); lp.rightMargin = dp(12); header.addView(logo, lp);
        LinearLayout titleCol = new LinearLayout(this); titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.addView(text("Video Downloader", 20, fg(), true));
        titleCol.addView(text("Завантаження відео", 13, muted(), false));
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1); slp.topMargin = dp(20); root.addView(scroll, slp);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);

        TextView linkTitle = text("Посилання на відео", 16, fg(), true); addGap(content, 0); content.addView(linkTitle);
        urlInput = new EditText(this); urlInput.setSingleLine(true); urlInput.setTextSize(14); urlInput.setTextColor(fg()); urlInput.setHintTextColor(muted());
        urlInput.setHint("https://example.com/video.mp4"); urlInput.setPadding(dp(14), dp(13), dp(14), dp(13)); urlInput.setBackground(roundBg(panel(), 12));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, -2); ulp.topMargin = dp(12); content.addView(urlInput, ulp);
        Button download = button("↓   Завантажити відео"); LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(52)); dlp.topMargin = dp(12); content.addView(download, dlp);
        download.setOnClickListener(v -> beginDownload());
        statusText = text("Підтримуються прямі посилання на відеофайли або URL, що повертають video/*.", 12, muted(), false);
        statusText.setPadding(dp(2), dp(10), dp(2), dp(10)); content.addView(statusText);

        LinearLayout prefs = new LinearLayout(this); prefs.setGravity(Gravity.CENTER_VERTICAL); prefs.setBackground(roundBg(panel(), 12)); prefs.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView themeName = text("Темна тема", 14, fg(), true); prefs.addView(themeName, new LinearLayout.LayoutParams(0, -2, 1));
        themeSwitch = new Switch(this); themeSwitch.setChecked(dark); prefs.addView(themeSwitch); content.addView(prefs);
        themeSwitch.setOnCheckedChangeListener((b, checked) -> { dark = checked; getPreferences(0).edit().putBoolean(DARK, dark).apply(); buildUi(); refreshHistory(); });

        TextView histTitle = text("Завантаження", 19, fg(), true); LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(-1, -2); htlp.topMargin = dp(24); htlp.bottomMargin = dp(12); content.addView(histTitle, htlp);
        historyBox = new LinearLayout(this); historyBox.setOrientation(LinearLayout.VERTICAL); content.addView(historyBox);
        TextView footer = text("Файли зберігаються у папці Download. Працюйте лише з відео, які маєте право завантажувати.", 11, muted(), false);
        footer.setPadding(dp(2), dp(20), dp(2), dp(18)); content.addView(footer);
    }

    private void beginDownload() {
        String value = urlInput.getText().toString().trim();
        if (value.isEmpty()) { toast("Вставте посилання на відео"); return; }
        final Uri uri;
        try {
            uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")) || uri.getHost() == null) throw new IllegalArgumentException();
        } catch (Exception e) { toast("Введіть коректне HTTP або HTTPS-посилання"); return; }
        statusText.setText("Перевіряємо, чи це пряме посилання на відео…");
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        executor.execute(() -> {
            String error = null;
            try {
                HttpURLConnection c = (HttpURLConnection)new URL(value).openConnection();
                c.setRequestMethod("HEAD"); c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "VideoDownloader/1.0 (Android)");
                int code = c.getResponseCode(); String type = c.getContentType(); c.disconnect();
                boolean ext = looksLikeVideo(uri.getLastPathSegment());
                boolean mime = type != null && type.toLowerCase().startsWith("video/");
                if (code < 200 || code >= 400) error = "Сервер повернув HTTP " + code + ".";
                else if (!ext && !mime) error = "Це не схоже на пряме посилання на відеофайл. Вставте URL файлу, а не сторінки фільму.";
            } catch (Exception e) { error = "Не вдалося перевірити посилання. Сервер може не підтримувати перевірку HEAD."; }
            final String err = error;
            runOnUiThread(() -> {
                if (err != null) { statusText.setText(err); return; }
                enqueue(value, uri);
            });
        });
    }

    private boolean looksLikeVideo(String path) {
        if (path == null) return false;
        String p = path.toLowerCase();
        for (String ext : new String[]{".mp4", ".m4v", ".webm", ".mkv", ".mov", ".avi", ".mpeg", ".mpg", ".3gp", ".ts", ".ogv"}) if (p.endsWith(ext)) return true;
        return false;
    }

    private void enqueue(String url, Uri uri) {
        try {
            String name = uri.getLastPathSegment();
            if (name == null || name.trim().isEmpty() || !looksLikeVideo(name)) name = "video_" + System.currentTimeMillis() + ".mp4";
            name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(name); req.setDescription("Video Downloader"); req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setAllowedOverMetered(true); req.setAllowedOverRoaming(false);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            long id = ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
            Set<String> ids = getIds(); ids.add(Long.toString(id)); saveIds(ids);
            statusText.setText("Завантаження додано: " + name);
            urlInput.setText(""); refreshHistory(); toast("Завантаження розпочато");
        } catch (Exception e) { statusText.setText("Не вдалося почати завантаження. Перевірте доступність посилання."); }
    }

    private void refreshHistory() {
        if (historyBox == null) return;
        historyBox.removeAllViews();
        ArrayList<String> ids = new ArrayList<>(getIds());
        if (ids.isEmpty()) { TextView empty = text("Поки що немає завантажень", 14, muted(), false); empty.setPadding(dp(4), dp(14), dp(4), dp(14)); historyBox.addView(empty); return; }
        DownloadManager dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        for (int i = ids.size() - 1; i >= 0; i--) {
            long id; try { id = Long.parseLong(ids.get(i)); } catch (Exception e) { continue; }
            DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
            Cursor c = null;
            String title = "Відеофайл", state = "Очікування", detail = ""; int progress = 0; boolean complete = false;
            try {
                c = dm.query(q);
                if (c != null && c.moveToFirst()) {
                    int ti = c.getColumnIndex(DownloadManager.COLUMN_TITLE); if (ti >= 0) title = c.getString(ti);
                    int si = c.getColumnIndex(DownloadManager.COLUMN_STATUS); int st = si >= 0 ? c.getInt(si) : 0;
                    int bytes = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), total = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    long got = bytes >= 0 ? c.getLong(bytes) : 0, all = total >= 0 ? c.getLong(total) : -1;
                    if (all > 0) progress = (int)Math.min(100, got * 100 / all);
                    if (st == DownloadManager.STATUS_SUCCESSFUL) { state = "Завершено"; complete = true; progress = 100; detail = all > 0 ? human(all) : "Готово"; }
                    else if (st == DownloadManager.STATUS_FAILED) state = "Помилка завантаження";
                    else if (st == DownloadManager.STATUS_RUNNING) { state = "Завантажується · " + progress + "%"; detail = all > 0 ? human(got) + " / " + human(all) : human(got); }
                    else if (st == DownloadManager.STATUS_PAUSED) state = "Призупинено";
                    else state = "Очікує в черзі";
                } else { state = "Запис недоступний"; }
            } catch (Exception ignored) { state = "Не вдалося прочитати статус"; }
            finally { if (c != null) c.close(); }
            final long itemId = id; final boolean isComplete = complete;
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(12), dp(12), dp(12), dp(12)); card.setBackground(roundBg(panel(), 12));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2); clp.bottomMargin = dp(8); historyBox.addView(card, clp);
            TextView fileIcon = text("▶", 18, blue(), true); fileIcon.setGravity(Gravity.CENTER); card.addView(fileIcon, new LinearLayout.LayoutParams(dp(38), dp(42)));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(8), 0, dp(8), 0); card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView nm = text(title, 14, fg(), true); nm.setMaxLines(2); col.addView(nm);
            col.addView(text(state, 12, muted(), false));
            if (!detail.isEmpty()) col.addView(text(detail, 11, muted(), false));
            Button action = smallButton(isComplete ? "Відкрити" : "Статус"); card.addView(action);
            action.setOnClickListener(v -> { if (isComplete) openDownload(itemId); else { refreshHistory(); toast("Статус оновлено"); } });
        }
    }

    private void openDownload(long id) {
        try {
            Uri uri = ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).getUriForDownloadedFile(id);
            if (uri == null) { toast("Файл ще не готовий"); return; }
            Intent intent = new Intent(Intent.ACTION_VIEW); intent.setDataAndType(uri, "video/*"); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(intent);
        } catch (ActivityNotFoundException e) { toast("Не знайдено відеопрогравач"); }
        catch (Exception e) { toast("Не вдалося відкрити файл"); }
    }

    private Set<String> getIds() { return new LinkedHashSet<>(getPreferences(0).getStringSet(IDS, new LinkedHashSet<>())); }
    private void saveIds(Set<String> ids) { getPreferences(0).edit().putStringSet(IDS, new LinkedHashSet<>(ids)).apply(); }
    private String human(long b) { if (b < 1024) return b + " B"; if (b < 1048576) return String.format(java.util.Locale.US, "%.1f KB", b/1024.0); if (b < 1073741824) return String.format(java.util.Locale.US, "%.1f MB", b/1048576.0); return String.format(java.util.Locale.US, "%.2f GB", b/1073741824.0); }
    private TextView text(String s, int size, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setBackground(roundBg(blue(), 12)); return b; }
    private Button smallButton(String s) { Button b = button(s); b.setTextSize(11); b.setPadding(dp(8), 0, dp(8), 0); b.setMinWidth(0); b.setMinimumWidth(0); return b; }
    private android.graphics.drawable.GradientDrawable roundBg(int color, int radius) { android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void addGap(LinearLayout box, int height) { View v = new View(this); box.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    @Override protected void onResume() { super.onResume(); if (historyBox != null) refreshHistory(); }
    @Override protected void onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null); executor.shutdown(); }
                      }
