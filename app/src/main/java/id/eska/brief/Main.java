package id.eska.brief;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;

public final class Main extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private boolean dark;
    private int bg, cardBg, cardStroke, ink, muted, accent, accentDisabled, onAccentDisabled, btnBg, btnStroke;
    private SharedPreferences prefs;
    private Uri source;
    private TextView sourceName, status;
    private Button choose, start, stop, share;
    private Spinner target, format;
    private CheckBox trim, clarity, denoise;
    private ProgressBar progress;

    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private void initPalette() {
        dark = LocaleHelper.isDark(this);
        if (dark) {
            bg = 0xff0e1513;
            cardBg = 0xff16211d;
            cardStroke = 0xff263933;
            ink = 0xffe7f2ee;
            muted = 0xff9cb5ad;
            accent = 0xff1d8a6e;
            accentDisabled = 0xff2b3d38;
            onAccentDisabled = 0xff7b938c;
            btnBg = 0xff1e2d28;
            btnStroke = 0xff314841;
        } else {
            bg = 0xfff4f9f7;
            cardBg = 0xffffffff;
            cardStroke = 0xffd5e5de;
            ink = 0xff16322c;
            muted = 0xff526e68;
            accent = 0xff126451;
            accentDisabled = 0xff9fb4ad;
            onAccentDisabled = 0xffeef5f2;
            btnBg = 0xffeaf3ef;
            btnStroke = 0xffc6dbd2;
        }
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(cardBg, cardStroke, 14));
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        parent.addView(box, lp);
        return box;
    }

    private TextView label(ViewGroup parent, String s, int size, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(v);
        return v;
    }

    private void space(ViewGroup parent, int n) { parent.addView(new Space(this), 1, dp(n)); }

    private Button chipButton(String text, View.OnClickListener action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(ink);
        b.setBackground(rounded(btnBg, btnStroke, 18));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(34));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setStateListAnimator(null);
        b.setOnClickListener(action);
        return b;
    }

    private Button secondaryButton(ViewGroup parent, String s, View.OnClickListener action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(15);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}}, new int[]{ink, muted}));
        b.setBackground(rounded(btnBg, btnStroke, 10));
        b.setMinimumHeight(dp(44));
        b.setStateListAnimator(null);
        b.setOnClickListener(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    private Button primaryButton(ViewGroup parent, String s, View.OnClickListener action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}}, new int[]{Color.WHITE, onAccentDisabled}));
        b.setBackground(rounded(Color.WHITE, 0, 12));
        b.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{}}, new int[]{accent, accentDisabled}));
        b.setMinimumHeight(dp(48));
        b.setStateListAnimator(null);
        b.setOnClickListener(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        parent.addView(b, lp);
        return b;
    }

    private Spinner spinner(ViewGroup parent, String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            public View getView(int pos, View convert, ViewGroup p) {
                TextView v = (TextView) super.getView(pos, convert, p);
                v.setTextColor(ink);
                v.setTextSize(15);
                v.setPadding(dp(4), dp(8), dp(4), dp(8));
                return v;
            }
            public View getDropDownView(int pos, View convert, ViewGroup p) {
                TextView v = (TextView) super.getDropDownView(pos, convert, p);
                v.setTextColor(ink);
                v.setBackgroundColor(cardBg);
                v.setPadding(dp(16), dp(12), dp(16), dp(12));
                return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(8);
        parent.addView(s, lp);
        return s;
    }

    private CheckBox checkBox(ViewGroup parent, int textRes) {
        CheckBox c = new CheckBox(this);
        c.setText(textRes);
        c.setTextSize(15);
        c.setTextColor(ink);
        c.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{accent, muted}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2);
        parent.addView(c, lp);
        return c;
    }

    private void applySystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(dark ? bg : 0xffeaf3ef);
        w.setNavigationBarColor(bg);
        View decor = w.getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int lightFlags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : lightFlags, lightFlags);
            }
        } else {
            @SuppressWarnings("deprecation")
            int vis = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(vis);
        }
    }

    public void onCreate(Bundle saved) {
        initPalette();
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        Job.recover(this);
        prefs = getSharedPreferences(Job.PREF, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFitsSystemWindows(true);
        setContentView(scroll);
        applySystemBars();

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(24));
        scroll.addView(body);

        // Top header bar with title + Language and Theme pills.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);
        title.setTextColor(ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        String lang = LocaleHelper.language(this);
        String langChipText = "en".equals(lang) ? getString(R.string.lang_chip_en)
            : "id".equals(lang) ? getString(R.string.lang_chip_id)
            : getString(R.string.lang_chip_auto);
        Button langBtn = chipButton(langChipText, v -> showLanguageDialog());
        langBtn.setContentDescription(getString(R.string.dialog_language));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
        chipLp.rightMargin = dp(8);
        header.addView(langBtn, chipLp);

        int themeMode = LocaleHelper.theme(this);
        String themeChipText = themeMode == LocaleHelper.THEME_DARK ? getString(R.string.theme_chip_dark)
            : themeMode == LocaleHelper.THEME_LIGHT ? getString(R.string.theme_chip_light)
            : getString(R.string.theme_chip_auto);
        Button themeBtn = chipButton(themeChipText, v -> showThemeDialog());
        themeBtn.setContentDescription(getString(R.string.dialog_theme));
        header.addView(themeBtn);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-1, -2);
        headerLp.bottomMargin = dp(14);
        body.addView(header, headerLp);

        // Source file card
        LinearLayout sourceCard = card(body);
        sourceName = label(sourceCard, getString(R.string.subtitle_pick), 15, false, muted);
        choose = secondaryButton(sourceCard, getString(R.string.btn_pick), v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
            startActivityForResult(i, 1);
        });

        // Options card
        LinearLayout optCard = card(body);
        label(optCard, getString(R.string.label_destination), 13, true, muted);
        target = spinner(optCard, Profile.labels(this));
        label(optCard, getString(R.string.label_format), 13, true, muted);
        format = spinner(optCard, new String[]{
            getString(R.string.fmt_opus_16),
            getString(R.string.fmt_opus_12),
            getString(R.string.fmt_opus_24),
            getString(R.string.fmt_aac_32),
            getString(R.string.fmt_opus_32),
            getString(R.string.fmt_aac_48)
        });
        label(optCard, getString(R.string.label_processing), 13, true, muted);
        space(optCard, 4);
        trim = checkBox(optCard, R.string.opt_trim);
        clarity = checkBox(optCard, R.string.opt_clarity);
        denoise = checkBox(optCard, R.string.opt_denoise);

        // Action + status
        start = primaryButton(body, getString(R.string.btn_start), v -> begin());
        space(body, 12);
        status = label(body, "", 14, false, ink);
        status.setTextIsSelectable(true);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(accent));
        progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(-1, dp(8));
        progLp.topMargin = dp(6);
        body.addView(progress, progLp);
        stop = secondaryButton(body, getString(R.string.btn_cancel), v -> startService(new Intent(this, Job.class).setAction("cancel")));
        share = secondaryButton(body, getString(R.string.btn_share), v -> share());

        target.setSelection(saved != null ? saved.getInt("target") : prefs.getInt("target", 0));
        format.setSelection(saved != null ? saved.getInt("format") : prefs.getInt("format", 2));
        trim.setChecked(saved != null ? saved.getBoolean("trim") : prefs.getBoolean("trim", true));
        clarity.setChecked(saved != null ? saved.getBoolean("clarity") : prefs.getBoolean("clarity", true));
        denoise.setChecked(saved != null ? saved.getBoolean("denoise") : prefs.getBoolean("denoise", false));
        if (saved != null) source = saved.getParcelable("uri"); else accept(getIntent());
        show();
    }

    private void showLanguageDialog() {
        String current = LocaleHelper.language(this);
        int checked = "en".equals(current) ? 1 : "id".equals(current) ? 2 : 0;
        String[] options = {
            getString(R.string.lang_opt_auto),
            getString(R.string.lang_opt_en),
            getString(R.string.lang_opt_id)
        };
        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_language)
            .setSingleChoiceItems(options, checked, (d, which) -> {
                String val = which == 1 ? "en" : which == 2 ? "id" : "";
                prefs.edit().putString(LocaleHelper.PREF_LANG, val).commit();
                d.dismiss();
                recreate();
            })
            .show();
    }

    private void showThemeDialog() {
        int checked = LocaleHelper.theme(this);
        String[] options = {
            getString(R.string.theme_opt_auto),
            getString(R.string.theme_opt_light),
            getString(R.string.theme_opt_dark)
        };
        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_theme)
            .setSingleChoiceItems(options, checked, (d, which) -> {
                prefs.edit().putInt(LocaleHelper.PREF_THEME, which).commit();
                d.dismiss();
                recreate();
            })
            .show();
    }

    private void accept(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null && "content".equals(u.getScheme())) source = u;
        }
    }

    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); accept(i); show(); }

    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK && data != null) {
            source = data.getData(); show();
        }
    }

    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putParcelable("uri", source);
        b.putInt("target", target.getSelectedItemPosition());
        b.putInt("format", format.getSelectedItemPosition());
        b.putBoolean("trim", trim.isChecked());
        b.putBoolean("clarity", clarity.isChecked());
        b.putBoolean("denoise", denoise.isChecked());
    }

    protected void onStart() { super.onStart(); prefs.registerOnSharedPreferenceChangeListener(this); show(); }
    protected void onStop() { prefs.unregisterOnSharedPreferenceChangeListener(this); super.onStop(); }
    public void onSharedPreferenceChanged(SharedPreferences p, String k) { show(); }

    private void show() {
        String state = prefs.getString("state", "");
        boolean run = "run".equals(state);
        if (source != null && !source.equals(sourceName.getTag())) {
            sourceName.setText(Job.name(this, source));
            sourceName.setTextColor(ink);
            sourceName.setTag(source);
        }
        choose.setEnabled(!run);
        target.setEnabled(!run);
        format.setEnabled(!run);
        trim.setEnabled(!run);
        clarity.setEnabled(!run);
        denoise.setEnabled(!run);
        start.setEnabled(source != null && !run);
        stop.setVisibility(run ? View.VISIBLE : View.GONE);
        progress.setVisibility(run ? View.VISIBLE : View.GONE);
        int pct = prefs.getInt("pct", -1);
        progress.setIndeterminate(pct < 0);
        progress.setProgress(Math.max(0, pct));
        String msg = prefs.getString("message", "");
        status.setText(msg);
        status.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
        share.setVisibility("done".equals(state) ? View.VISIBLE : View.GONE);
    }

    private void begin() {
        if (source == null || Job.active) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()) && !prefs.getBoolean("asked_battery", false)) {
            prefs.edit().putBoolean("asked_battery", true).apply();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
        int f = format.getSelectedItemPosition(), p = target.getSelectedItemPosition();
        prefs.edit().putInt("target", p).putInt("format", f).putBoolean("trim", trim.isChecked())
            .putBoolean("clarity", clarity.isChecked()).putBoolean("denoise", denoise.isChecked()).apply();
        Intent i = new Intent(this, Job.class).setData(source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra("profile", p).putExtra("aac", f == 3 || f == 5).putExtra("kbps", new int[]{16, 12, 24, 32, 32, 48}[f])
            .putExtra("trim", trim.isChecked()).putExtra("clarity", clarity.isChecked()).putExtra("denoise", denoise.isChecked());
        start.setEnabled(false);
        try { startForegroundService(i); } catch (SecurityException e) {
            start.setEnabled(true);
            status.setText(R.string.err_no_access);
            status.setVisibility(View.VISIBLE);
        }
    }

    private void share() {
        JSONArray files = Job.array(prefs, "files");
        if (files.length() == 1) { send(Uri.parse(files.optString(0))); return; }
        String[] items = new String[files.length()];
        for (int i = 0; i < items.length; i++) items[i] = Job.name(this, Uri.parse(files.optString(i)));
        new AlertDialog.Builder(this).setTitle(R.string.dialog_share_parts).setItems(items, (d, i) -> send(Uri.parse(files.optString(i)))).show();
    }

    private void send(Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND).setType(prefs.getString("mime", "audio/*")).putExtra(Intent.EXTRA_STREAM, uri);
        i.setClipData(ClipData.newRawUri("Audio", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, getString(R.string.chooser_share)));
    }
}
