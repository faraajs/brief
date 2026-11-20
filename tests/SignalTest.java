package id.eska.brief;

import java.nio.*;
import java.util.*;

public class SignalTest {
    static short[] frame(int level) {
        short[] f=new short[Signal.FRAME]; Arrays.fill(f,(short)level); return f;
    }
    static void check(boolean ok,String msg) { if (!ok) throw new AssertionError(msg); }
    static Signal.SpeechGuard thresholdGuard(int minSpeechLevel, int[] resets) {
        return new Signal.SpeechGuard() {
            public boolean hasSpeech(short[][] frames, int count) {
                for (int i=0;i<count;i++) if (Math.abs((int)frames[i][0])>=minSpeechLevel) return true;
                return false;
            }
            public void reset() { if (resets!=null) resets[0]++; }
        };
    }
    static void gap(int count,boolean trim,int expected) throws Exception {
        ArrayList<Short> levels=new ArrayList<>();
        int[] resets={0};
        Signal s=new Signal((f,q)->levels.add(f[0]),trim ? thresholdGuard(250,resets) : null);
        for (int i=0;i<8;i++) s.accept(frame(250));
        for (int i=0;i<count;i++) s.accept(frame(0));
        for (int i=0;i<8;i++) s.accept(frame(260));
        s.flushQuiet();
        check(levels.size()==expected+16 && levels.get(0)==250 && levels.get(levels.size()-1)==260,"gap "+count+" got "+levels.size());
        if (trim) check(resets[0]>=1,"reset expected");
    }
    static void fanNoiseTrimmed() throws Exception {
        ArrayList<Short> levels=new ArrayList<>();
        Signal s=new Signal((f,q)->levels.add(f[0]),thresholdGuard(600,null));
        for (int i=0;i<8;i++) s.accept(frame(1000));
        for (int i=0;i<304;i++) s.accept(frame(200));
        for (int i=0;i<8;i++) s.accept(frame(1000));
        s.flushQuiet();
        check(levels.size()==8+30+8,"fan noise not trimmed: "+levels.size());
    }
    static void quietSpeechPreserved() throws Exception {
        ArrayList<Short> levels=new ArrayList<>();
        int[] resets={0};
        Signal.SpeechGuard guard=new Signal.SpeechGuard() {
            public boolean hasSpeech(short[][] frames, int count) {
                for (int i=0;i<count;i++) if (frames[i][0]==15 || frames[i][0]>=300) return true;
                return false;
            }
            public void reset() { resets[0]++; }
        };
        Signal s=new Signal((f,q)->levels.add(f[0]),guard);
        for (int i=0;i<8;i++) s.accept(frame(300));
        for (int i=0;i<200;i++) s.accept(frame(0));
        for (int i=0;i<160;i++) s.accept(frame(15));
        for (int i=0;i<200;i++) s.accept(frame(0));
        for (int i=0;i<8;i++) s.accept(frame(300));
        s.flushQuiet();
        check(levels.size()==8+30+160+30+8,"quiet speech count "+levels.size());
        long speechFrames=levels.stream().filter(v->v==15).count();
        check(speechFrames==160,"lost quiet speech "+speechFrames);
        check(resets[0]>=1,"guard reset");
    }
    static void voiceClarity() throws Exception {
        double[] rms50 = {0}, rms1k = {0};
        for (int freq : new int[]{50, 1000}) {
            double[] sum = {0}; int[] cnt = {0};
            VoiceClarity.HighPass hp = new VoiceClarity.HighPass(new FrameSink() {
                public void accept(short[] f) {
                    if (cnt[0] >= 1600) for (short s : f) sum[0] += s * (double) s;
                    cnt[0] += f.length;
                }
                public void finish() {}
            });
            for (int f = 0; f < 20; f++) {
                short[] buf = new short[Signal.FRAME];
                for (int i = 0; i < buf.length; i++) {
                    int idx = f * Signal.FRAME + i;
                    buf[i] = (short) Math.round(10000 * Math.sin(2 * Math.PI * freq * idx / Signal.RATE));
                }
                hp.accept(buf);
            }
            hp.finish();
            double rms = Math.sqrt(sum[0] / (cnt[0] - 1600));
            if (freq == 50) rms50[0] = rms; else rms1k[0] = rms;
        }
        check(rms50[0] < 2500, "50Hz not attenuated: " + rms50[0]);
        check(rms1k[0] > 6800, "1kHz attenuated: " + rms1k[0]);

        double[] lastRms = {0}; int[] maxPeak = {0};
        VoiceClarity.Leveler lv = new VoiceClarity.Leveler((f, q) -> {
            double s = 0;
            for (short v : f) { s += v * (double) v; maxPeak[0] = Math.max(maxPeak[0], Math.abs((int) v)); }
            lastRms[0] = Math.sqrt(s / f.length);
        });
        for (int t = 0; t < 120; t++) lv.put(frame(400), false);
        check(lastRms[0] > 1800, "quiet speech not lifted: " + lastRms[0]);
        double afterQuiet = lastRms[0];
        for (int t = 0; t < 100; t++) lv.put(frame(10), true);
        lv.put(frame(400), false);
        check(Math.abs(lastRms[0] - afterQuiet) < 250, "silence pumped gain: " + lastRms[0] + " vs " + afterQuiet);
        maxPeak[0] = 0;
        for (int t = 0; t < 20; t++) lv.put(frame(21000), false);
        check(maxPeak[0] <= 26100, "loud peak clipped: " + maxPeak[0]);
    }
    static void fftRoundTrip() {
        float[] orig = new float[GtcrnDenoiser.NFFT];
        float[] re = new float[GtcrnDenoiser.NFFT], im = new float[GtcrnDenoiser.NFFT];
        Random rng = new Random(7);
        for (int i = 0; i < orig.length; i++) { orig[i] = rng.nextFloat() * 2 - 1; re[i] = orig[i]; }
        GtcrnDenoiser.fft(re, im, false);
        GtcrnDenoiser.fft(re, im, true);
        float maxErr = 0;
        for (int i = 0; i < orig.length; i++) maxErr = Math.max(maxErr, Math.abs(re[i] - orig[i]));
        check(maxErr < 1e-4f, "FFT roundtrip error: " + maxErr);
    }
    public static void main(String[] args) throws Exception {
        gap(96,true,96); gap(104,true,30); gap(400,true,30); gap(400,false,400);
        fanNoiseTrimmed();
        quietSpeechPreserved();
        voiceClarity();
        fftRoundTrip();
        for (int rate:new int[]{8000,16000,32000,44100,48000}) {
            for (int ch:new int[]{1,2}) {
                int[] samples={0}; long[] sum={0};
                Signal.Resampler r=new Signal.Resampler(new Signal((f,q)->{ samples[0]+=f.length; for (short v:f) sum[0]+=v; },null),rate,ch);
                for (int offset=0;offset<rate;) {
                    int n=Math.min(853,rate-offset); ByteBuffer b=ByteBuffer.allocate(n*ch*2).order(ByteOrder.nativeOrder());
                    for (int i=0;i<n;i++) { b.putShort((short)1000); if (ch==2) b.putShort((short)500); }
                    b.flip(); r.read(b); offset+=n;
                }
                r.finish();
                int expected = ch == 1 ? 1000 : 750;
                check(samples[0]==16000,"resample "+rate+" ch="+ch+" got "+samples[0]);
                check(Math.abs(sum[0]/16000.0 - expected) <= 1.0,"resample avg "+rate+" ch="+ch+" got "+(sum[0]/16000.0));
            }
        }
        System.out.println("SignalTest OK");
    }
}
