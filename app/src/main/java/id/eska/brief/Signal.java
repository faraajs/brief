package id.eska.brief;

import java.nio.*;
import java.util.*;

final class Signal implements FrameSink {
    static final int RATE = 16000, FRAME = 320;
    interface Sink { void put(short[] frame, boolean quiet) throws Exception; }
    private final Sink sink;
    interface SpeechGuard {
        boolean hasSpeech(short[][] frames, int count) throws Exception;
        void reset();
    }
    private final SpeechGuard guard;
    // Eight 20 ms frames are exactly five 512-sample Silero windows.
    private final short[][] candidates = new short[8][];
    private int candidateCount;
    private boolean guardActive;
    private final ArrayDeque<short[]> pending = new ArrayDeque<>();
    private int quietCount;
    private double floor = 16;
    Signal(Sink sink, SpeechGuard guard) { this.sink = sink; this.guard = guard; }

    public void accept(short[] frame) throws Exception {
        if (guard == null) {
            double sum = 0; int peak = 0;
            for (short sample : frame) {
                sum += sample * (double) sample;
                peak = Math.max(peak, Math.abs((int) sample));
            }
            double rms = Math.sqrt(sum / frame.length);
            floor += (rms < floor ? .05 : .0003) * (Math.min(rms, 100) - floor);
            boolean quiet = rms < Math.min(65, Math.max(18, floor * 1.45)) && peak < 230;
            sink.put(frame, quiet);
            return;
        }
        // Full-stream Silero VAD: evaluates every 160 ms batch so fan/AC pauses are trimmed cleanly.
        candidates[candidateCount++] = frame;
        if (candidateCount == candidates.length) checkCandidates();
    }
    private void checkCandidates() throws Exception {
        if (candidateCount == 0) return;
        boolean speech = guard.hasSpeech(candidates, candidateCount);
        guardActive = true;
        if (speech) releasePending();
        for (int i=0; i<candidateCount; i++) {
            if (speech) sink.put(candidates[i], false);
            else keepQuietEdges(candidates[i]);
            candidates[i] = null;
        }
        candidateCount = 0;
    }
    private void keepQuietEdges(short[] frame) throws Exception {
        quietCount++;
        pending.addLast(frame);
        if (quietCount == 100) {
            for (int i=0; i<15; i++) sink.put(pending.removeFirst(), true);
        }
        if (quietCount >= 100) while (pending.size() > 15) pending.removeFirst();
    }
    public void finish() throws Exception { flushQuiet(); }
    void flushQuiet() throws Exception {
        checkCandidates();
        if (guardActive) { guard.reset(); guardActive = false; }
        releasePending();
    }
    private void releasePending() throws Exception {
        while (!pending.isEmpty()) sink.put(pending.removeFirst(), true);
        quietCount = 0;
    }

    // Integrate input samples over each output interval, carrying phase between codec buffers.
    static final class Resampler {
        final FrameSink signal;
        final int rate, channels, ratio;
        int phase, used;
        long intSum;
        double area;
        short[] frame = new short[FRAME];
        long inputFrames;
        Resampler(FrameSink signal, int rate, int channels) {
            this.signal=signal; this.rate=rate; this.channels=channels;
            this.ratio=(rate % RATE == 0 && rate >= RATE) ? rate / RATE : 0;
        }
        void read(ByteBuffer bytes) throws Exception {
            ShortBuffer pcm = bytes.order(ByteOrder.nativeOrder()).asShortBuffer();
            if (ratio == 1 && channels == 1) {
                int avail = pcm.remaining();
                inputFrames += avail;
                while (avail > 0) {
                    int take = Math.min(avail, FRAME - used);
                    pcm.get(frame, used, take);
                    used += take; avail -= take;
                    if (used == FRAME) { signal.accept(frame); frame = new short[FRAME]; used = 0; }
                }
                return;
            }
            if (ratio == 1 && channels == 2) {
                int count = pcm.remaining() / 2;
                inputFrames += count;
                for (int k = 0; k < count; k++) {
                    int sum = pcm.get() + pcm.get();
                    emit((short) ((sum >= 0 ? sum + 1 : sum) >> 1));
                }
                return;
            }
            if (ratio > 1) {
                int count = pcm.remaining() / channels;
                inputFrames += count;
                int denom = ratio * channels;
                int half = denom / 2;
                for (int k = 0; k < count; k++) {
                    for (int c = 0; c < channels; c++) intSum += pcm.get();
                    if (++phase == ratio) {
                        long v = intSum >= 0 ? (intSum + half) / denom : (intSum - half) / denom;
                        emit((short) v);
                        phase = 0; intSum = 0;
                    }
                }
                return;
            }
            while (pcm.remaining() >= channels) {
                int sum=0;
                for (int i=0;i<channels;i++) sum += pcm.get();
                double mono = (double)sum/channels;
                int left=RATE;
                while (left>0) {
                    int take=Math.min(left,rate-phase);
                    area+=mono*take; phase+=take; left-=take;
                    if (phase==rate) { emit((short)Math.round(area/rate)); phase=0; area=0; }
                }
                inputFrames++;
            }
        }
        void emit(short sample) throws Exception {
            frame[used++]=sample;
            if (used==FRAME) { signal.accept(frame); frame=new short[FRAME]; used=0; }
        }
        void finish() throws Exception {
            if (ratio > 1 && phase > 0) {
                int denom = phase * channels;
                long v = intSum >= 0 ? (intSum + denom / 2) / denom : (intSum - denom / 2) / denom;
                emit((short) v);
                phase = 0; intSum = 0;
            } else if (ratio == 0 && phase > 0) {
                emit((short)Math.round(area/phase));
                phase = 0; area = 0;
            }
            if (used>0) signal.accept(Arrays.copyOf(frame,used));
            signal.finish();
        }
    }
}
