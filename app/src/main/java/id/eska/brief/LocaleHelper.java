package id.eska.brief;

import android.content.*;
import android.content.res.Configuration;
import java.util.Locale;

final class LocaleHelper {
    static final String PREF_LANG = "lang";
    static final String PREF_THEME = "theme";
    static final int THEME_AUTO = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    static String language(Context c) {
        return c.getSharedPreferences(Job.PREF, 0).getString(PREF_LANG, "");
    }

    static int theme(Context c) {
        return c.getSharedPreferences(Job.PREF, 0).getInt(PREF_THEME, THEME_AUTO);
    }

    static boolean isDark(Context c) {
        int mode = theme(c);
        if (mode == THEME_DARK) return true;
        if (mode == THEME_LIGHT) return false;
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    static Context wrap(Context base) {
        String lang = language(base);
        if (lang.isEmpty()) return base;
        Locale locale = "id".equals(lang) ? new Locale("id", "ID") : Locale.ENGLISH;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        return base.createConfigurationContext(cfg);
    }
}
