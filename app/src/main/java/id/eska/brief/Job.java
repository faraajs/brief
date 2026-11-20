package id.eska.brief;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import org.json.*;
import java.io.*;
import java.util.*;

public final class Job extends Service {
    static final String CHANNEL="conversions", PREF="export";
    static volatile boolean active;
    private volatile boolean cancel;

    static String time(long ms) {
        long s=ms/1000;
        return String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60);
    }

    static String name(Context c, Uri uri) {
        try (Cursor q=c.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if (q!=null && q.moveToFirst()) return q.getString(0);
        } catch(Exception ignored) {}
        return c.getString(R.string.default_recording);
    }

    static JSONArray array(SharedPreferences p,String key) {
        try { return new JSONArray(p.getString(key,"[]")); } catch(JSONException e) { return new JSONArray(); }
    }

    static void recover(Context c) {
        SharedPreferences p=c.getSharedPreferences(PREF,0);
        if (!active && "run".equals(p.getString("state",""))) {
            JSONArray interrupted=array(p,"pending");
            for (int i=0;i<interrupted.length();i++) {
                try { c.getContentResolver().delete(Uri.parse(interrupted.getString(i)),null,null); } catch(Exception ignored) {}
            }
            Context loc=LocaleHelper.wrap(c);
            p.edit().putString("state","error").putString("message",loc.getString(R.string.err_interrupted)).remove("pending").commit();
        }
    }

    public IBinder onBind(Intent i) { return null; }

    public void onCreate() {
        super.onCreate();
        Context loc=LocaleHelper.wrap(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL,loc.getString(R.string.channel_name),NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(Context loc,String msg,int pct) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,Main.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_audio)
            .setContentTitle(loc.getString(R.string.app_name)).setContentText(msg).setOnlyAlertOnce(true)
            .setOngoing(true).setContentIntent(open).setProgress(100,Math.max(0,pct),pct<0).build();
    }

    public int onStartCommand(Intent intent,int flags,int id) {
        if (intent==null) { stopSelf(); return START_NOT_STICKY; }
        if ("cancel".equals(intent.getAction())) { cancel=true; if (!active) stopSelf(); return START_NOT_STICKY; }
        if (active) return START_NOT_STICKY;
        Context loc=LocaleHelper.wrap(this);
        recover(loc); active=true; cancel=false;
        SharedPreferences p=getSharedPreferences(PREF,0);
        String reading=loc.getString(R.string.state_reading);
        p.edit().putString("state","run").putString("message",reading).putInt("pct",-1).putString("pending","[]").commit();
        startForeground(7,notification(loc,reading,-1));
        new Thread(()->{
            try {
                try { android.os.Process.setThreadPriority(-6); } catch (Throwable ignored) {}
                execute(loc,intent,p);
            }
            finally { new Handler(Looper.getMainLooper()).post(()->{ active=false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }); }
        },"audio-export").start();
        return START_NOT_STICKY;
    }

    private void execute(Context loc,Intent intent,SharedPreferences prefs) {
        PowerManager.WakeLock wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Brief:export");
        JSONArray files=new JSONArray();
        Convert.Parts parts=null;
        try {
            wake.acquire();
            Uri src=intent.getData(); boolean aac=intent.getBooleanExtra("aac",false);
            int kbps=intent.getIntExtra("kbps",24);
            String name=name(loc,src).replaceFirst("\\.[^.]+$","").replaceAll("[^\\p{L}\\p{N} _.-]","_");
            String stem=name.substring(0,Math.min(65,name.length()));
            String folder=Environment.DIRECTORY_DOWNLOADS+"/Brief/"+stem+"-"+Long.toString(System.currentTimeMillis(),36)+"/";
            ContentResolver cr=getContentResolver();
            Convert.Output dest=new Convert.Output() {
                public Encoder open() throws Exception {
                    ContentValues cv=new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME,String.format(Locale.ROOT,"%s_%03d.%s",stem,files.length()+1,aac?"m4a":"ogg"));
                    cv.put(MediaStore.MediaColumns.MIME_TYPE,aac?"audio/mp4":"audio/ogg");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,folder);
                    cv.put(MediaStore.MediaColumns.IS_PENDING,1);
                    Uri u=cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
                    if (u==null) throw new IOException(loc.getString(R.string.err_create_file));
                    files.put(u.toString());
                    prefs.edit().putString("pending",files.toString()).commit();
                    return new Encoder(cr.openFileDescriptor(u,"rw"),aac,kbps);
                }
                public void progress(long us,long total) throws Exception {
                    if (cancel) throw new IOException(loc.getString(R.string.err_cancelled));
                    int pct=total>0 ? (int)Math.min(99,us*100/total) : -1;
                    String msg=total>0
                        ? loc.getString(R.string.state_processing_total,time(us/1000),time(total/1000))
                        : loc.getString(R.string.state_processing,time(us/1000));
                    prefs.edit().putInt("pct",pct).putString("message",msg).apply();
                    getSystemService(NotificationManager.class).notify(7,notification(loc,msg,pct));
                }
            };
            Profile profile=Profile.values()[intent.getIntExtra("profile",0)];
            boolean trim=intent.getBooleanExtra("trim",true);
            boolean clarity=intent.getBooleanExtra("clarity",true);
            boolean denoise=intent.getBooleanExtra("denoise",false);
            parts=new Convert.Parts(profile,dest);
            long inputMs;
            try (SileroGuard vad=trim ? SileroGuard.open(loc) : null;
                 GtcrnDenoiser gtcrn=denoise ? GtcrnDenoiser.open(loc) : null;
                 Convert.AsyncSink async=new Convert.AsyncSink(parts::write)) {
                Signal.Sink sink=clarity ? new VoiceClarity.Leveler(async) : async;
                GtcrnDenoiser.SinkChain denoiseSink=gtcrn!=null ? new GtcrnDenoiser.SinkChain(gtcrn,sink) : null;
                FrameSink chain=new Signal(denoiseSink!=null ? denoiseSink : sink,vad);
                if (clarity || denoise || trim) chain=new VoiceClarity.HighPass(chain);
                inputMs=Convert.run(loc,src,chain,dest);
                if (denoiseSink!=null) denoiseSink.finish();
                async.finish();
            }
            parts.end();
            if (cancel) throw new IOException(loc.getString(R.string.err_cancelled));
            long size=0;
            for (int i=0;i<files.length();i++) {
                Uri u=Uri.parse(files.getString(i));
                try (ParcelFileDescriptor fd=cr.openFileDescriptor(u,"r")) {
                    long n=fd.getStatSize();
                    if (n<=0 || n>profile.bytes) throw new IOException(loc.getString(R.string.err_size_limit));
                    size+=n;
                }
                ContentValues cv=new ContentValues(); cv.put(MediaStore.MediaColumns.IS_PENDING,0);
                if (cr.update(u,cv,null,null)!=1) throw new IOException(loc.getString(R.string.err_save_part));
            }
            String msg=loc.getString(files.length()==1 ? R.string.state_done_one : R.string.state_done_many,
                time(inputMs),time(parts.kept*1000/Signal.RATE),size/1e6,files.length(),folder);
            prefs.edit().putString("state","done").putString("message",msg).putString("files",files.toString()).putString("mime",aac?"audio/mp4":"audio/ogg").remove("pending").commit();
        } catch(Exception e) {
            if (parts!=null && parts.encoder!=null) parts.encoder.close();
            for (int i=0;i<files.length();i++) {
                try { getContentResolver().delete(Uri.parse(files.getString(i)),null,null); } catch(Exception ignored) {}
            }
            android.util.Log.e("Brief","Conversion failed",e);
            prefs.edit().putString("state","error").putString("message",Objects.toString(e.getMessage(),loc.getString(R.string.err_read_media))).remove("pending").commit();
        } finally { if (wake.isHeld()) wake.release(); }
    }
}
