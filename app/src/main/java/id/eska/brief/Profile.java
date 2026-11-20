package id.eska.brief;

import android.content.Context;

enum Profile {
    NOTEBOOK(R.string.profile_notebook, 0, 195_000_000),
    GEMINI(R.string.profile_gemini, 590, 98_000_000),
    CHATGPT(R.string.profile_chatgpt, 350, 98_000_000),
    GROK(R.string.profile_grok, 0, 145_000_000),
    GROK_SHORT(R.string.profile_grok_short, 600, 145_000_000);

    final int labelRes;
    final int seconds, bytes;

    Profile(int labelRes, int seconds, int bytes) {
        this.labelRes = labelRes;
        this.seconds = seconds;
        this.bytes = bytes;
    }

    static String[] labels(Context c) {
        Profile[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) out[i] = c.getString(all[i].labelRes);
        return out;
    }
}
