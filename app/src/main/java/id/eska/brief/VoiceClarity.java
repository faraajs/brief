package id.eska.brief;

// Lightweight speech clarity DSP for AI transcription:
// 1) 85 Hz 2nd-order Butterworth high-pass filter (removes AC/fan rumble & 50/60 Hz hum).
// 2) Peak-guarded, speech-aware leveler (lifts quiet speakers without saturating peaks or pumping silence).
final class VoiceClarity {
    static final class HighPass implements FrameSink {
        private static final double B0, B1, B2, A1, A2;
        static {
            double w0 = 2.0 * Math.PI * 85.0 / Signal.RATE;
            double alpha = Math.sin(w0) / Math.sqrt(2.0);
            double c = Math.cos(w0);
            double a0 = 1.0 + alpha;
            B0 = ((1.0 + c) / 2.0) / a0;
            B1 = (-(1.0 + c)) / a0;
            B2 = B0;
            A1 = (-2.0 * c) / a0;
            A2 = (1.0 - alpha) / a0;
        }
        private final FrameSink next;
        private double x1, x2, y1, y2;

        HighPass(FrameSink next) { this.next = next; }

        public void accept(short[] frame) throws Exception {
            short[] out = new short[frame.length];
            double lx1 = x1, lx2 = x2, ly1 = y1, ly2 = y2;
            for (int i = 0; i < frame.length; i++) {
                double x0 = frame[i];
                double y0 = B0 * x0 + B1 * lx1 + B2 * lx2 - A1 * ly1 - A2 * ly2;
                lx2 = lx1; lx1 = x0; ly2 = ly1; ly1 = y0;
                long v = Math.round(y0);
                out[i] = (short) Math.max(-32768, Math.min(32767, v));
            }
            x1 = lx1; x2 = lx2; y1 = ly1; y2 = ly2;
            next.accept(out);
        }

        public void finish() throws Exception { next.finish(); }
    }

    static final class Leveler implements Signal.Sink {
        private static final double TARGET_RMS = 2600.0;
        private static final double MAX_GAIN = 8.0, MIN_GAIN = 0.7;
        private static final double PEAK_TARGET = 22000.0, PEAK_CEIL = 26000.0;
        private final Signal.Sink next;
        private double gain = 1.0;
        private double floor = 25.0;

        Leveler(Signal.Sink next) { this.next = next; }

        public void put(short[] frame, boolean quiet) throws Exception {
            int n = frame.length;
            if (n == 0) { next.put(frame, quiet); return; }
            double sum = 0;
            int peak = 0;
            for (short s : frame) {
                sum += s * (double) s;
                int a = Math.abs((int) s);
                if (a > peak) peak = a;
            }
            double rms = Math.sqrt(sum / n);
            floor += (rms < floor ? 0.05 : 0.0005) * (Math.min(rms, 500.0) - floor);
            boolean active = !quiet || rms > Math.max(35.0, floor * 2.0);
            double nextGain = gain;
            if (active && rms > 20.0) {
                double desired = Math.min(MAX_GAIN, TARGET_RMS / rms);
                if (peak > 1) desired = Math.min(desired, PEAK_TARGET / peak);
                desired = Math.max(MIN_GAIN, desired);
                double alpha = desired < gain ? 0.30 : 0.03;
                nextGain = gain + alpha * (desired - gain);
            }
            if (peak > 1 && peak * nextGain > PEAK_CEIL) nextGain = PEAK_CEIL / peak;

            short[] out = new short[n];
            double step = (nextGain - gain) / n;
            double g = gain;
            for (int i = 0; i < n; i++) {
                double s = frame[i];
                double a = Math.abs(s);
                if (a * g > PEAK_CEIL) g = PEAK_CEIL / a;
                long v = Math.round(s * g);
                g += step;
                if (step < 0 && g < nextGain) g = nextGain;
                else if (step > 0 && g > nextGain) g = nextGain;
                out[i] = (short) Math.max(-32768, Math.min(32767, v));
            }
            gain = Math.min(nextGain, g);
            next.put(out, quiet);
        }
    }
}
