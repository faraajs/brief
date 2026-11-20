package id.eska.brief;

import android.content.Context;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.util.*;

// Silero v6.2, 16 kHz ONNX graph: 64 context samples + 512 new samples per inference.
// Runs full-stream with zero-copy native state chaining and adaptive peak-safe gain (<=32x)
// so fan/AC pauses are trimmed cleanly while whispers remain preserved.
final class SileroGuard implements Signal.SpeechGuard, AutoCloseable {
    private static final int CONTEXT = 64, WINDOW = 512;
    private static final float THRESHOLD = 0.25f;
    private static final long[] INPUT_SHAPE = {1, CONTEXT + WINDOW};
    private static final long[] STATE_SHAPE = {2, 1, 128};

    private final OrtEnvironment env;
    private final OrtSession session;
    private final OnnxTensor sr, zeroState;
    private final FloatBuffer inputBuf = ByteBuffer.allocateDirect((CONTEXT + WINDOW) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] input = new float[CONTEXT + WINDOW];
    private final Map<String, OnnxTensor> inputs = new HashMap<>(3);
    private OrtSession.Result prev;

    private SileroGuard(OrtEnvironment env, OrtSession session, OnnxTensor sr, OnnxTensor zeroState) {
        this.env = env;
        this.session = session;
        this.sr = sr;
        this.zeroState = zeroState;
        inputs.put("sr", sr);
    }

    static SileroGuard open(Context c) throws IOException {
        try (InputStream asset = c.getAssets().open("silero_vad_16k.onnx");
             OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            ByteArrayOutputStream model = new ByteArrayOutputStream();
            byte[] block = new byte[16384];
            int n;
            while ((n = asset.read(block)) != -1) model.write(block, 0, n);
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession session = env.createSession(model.toByteArray(), options);
            OnnxTensor rate = null, zState = null;
            try {
                rate = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{Signal.RATE}), new long[]{});
                FloatBuffer zb = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                zState = OnnxTensor.createTensor(env, zb, STATE_SHAPE);
                return new SileroGuard(env, session, rate, zState);
            } catch (OrtException | RuntimeException e) {
                if (zState != null) zState.close();
                if (rate != null) rate.close();
                session.close();
                throw e;
            }
        } catch (OrtException | LinkageError e) {
            throw new IOException(c.getString(R.string.err_silero_load), e);
        }
    }

    public boolean hasSpeech(short[][] frames, int count) throws OrtException {
        int peak = 0;
        for (int i = 0; i < count; i++) {
            for (short sample : frames[i]) {
                int a = Math.abs((int) sample);
                if (a > peak) peak = a;
            }
        }
        if (peak == 0) { reset(); return false; }

        // Natural 1x scale for normal audio; peak-safe lift (<=32x) only for very quiet audio (< -27 dBFS).
        float scale = (peak < 1500 ? Math.min(32f, 1500f / peak) : 1f) / 32768f;
        boolean speech = false;
        int filled = 0;
        for (int i = 0; i < count; i++) {
            for (short sample : frames[i]) {
                input[CONTEXT + filled++] = sample * scale;
                if (filled == WINDOW) { speech |= detect(); filled = 0; }
            }
        }
        if (filled > 0) {
            Arrays.fill(input, CONTEXT + filled, input.length, 0f);
            speech |= detect();
        }
        return speech;
    }

    private boolean detect() throws OrtException {
        inputBuf.clear();
        inputBuf.put(input);
        inputBuf.flip();
        OrtSession.Result next;
        try (OnnxTensor audio = OnnxTensor.createTensor(env, inputBuf, INPUT_SHAPE)) {
            inputs.put("input", audio);
            inputs.put("state", prev == null ? zeroState : (OnnxTensor) prev.get(1));
            next = session.run(inputs);
        }
        if (prev != null) prev.close();
        prev = next;
        float probability = ((OnnxTensor) prev.get(0)).getFloatBuffer().get();
        System.arraycopy(input, WINDOW, input, 0, CONTEXT);
        // Preserve NaN/invalid scores as speech so audio is never silently dropped.
        return !(probability < THRESHOLD);
    }

    public void reset() {
        if (prev != null) { prev.close(); prev = null; }
        Arrays.fill(input, 0, CONTEXT, 0f);
    }

    public void close() throws OrtException {
        if (prev != null) { prev.close(); prev = null; }
        try { zeroState.close(); }
        finally {
            try { sr.close(); }
            finally { session.close(); }
        }
    }
}
