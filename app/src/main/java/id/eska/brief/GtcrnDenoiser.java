package id.eska.brief;

import android.content.Context;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

// GTCRN (16 kHz streaming STFT speech enhancement, MIT license).
// Uses 512-point sqrt-Hann STFT with 256-sample hop, zero-copy native cache tensor chaining,
// 70/30 Observation Adding wet/dry blend, and parallel chunk workers with 1.024s RNN warmup.
final class GtcrnDenoiser implements AutoCloseable {
    static final int NFFT = 512, HOP = 256, BINS = 257;
    private static final float WET = 0.70f, DRY = 0.30f;
    // 19.2 s payload + 1.024 s warmup overlap keeps RNN/TCN state converged across chunk boundaries.
    static final int CHUNK_SAMPLES = 1200 * HOP;
    static final int WARMUP_SAMPLES = 64 * HOP;

    private static final long[] MIX_SHAPE = {1, BINS, 1, 2};
    private static final long[] CONV_SHAPE = {2, 1, 16, 16, 33};
    private static final long[] TRA_SHAPE = {2, 3, 1, 1, 16};
    private static final long[] INTER_SHAPE = {2, 1, 33, 16};

    private static final float[] WIN = new float[NFFT];
    private static final float[] COS = new float[NFFT / 2], SIN = new float[NFFT / 2];
    private static final int[] BITREV = new int[NFFT];
    static {
        for (int i = 0; i < NFFT; i++) {
            WIN[i] = (float) Math.sqrt(0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / NFFT)));
            int r = 0, x = i;
            for (int b = 0; b < 9; b++) { r = (r << 1) | (x & 1); x >>= 1; }
            BITREV[i] = r;
        }
        for (int i = 0; i < NFFT / 2; i++) {
            double a = -2.0 * Math.PI * i / NFFT;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    private final OrtEnvironment env;
    private final byte[] modelBytes;
    private final int workerCount;
    private final List<Worker> workers = Collections.synchronizedList(new ArrayList<>());
    private ExecutorService activePool;

    private GtcrnDenoiser(OrtEnvironment env, byte[] modelBytes, int workerCount) throws OrtException {
        this.env = env;
        this.modelBytes = modelBytes;
        this.workerCount = workerCount;
        // Validate model & native runtime eagerly so open() fails fast if unavailable.
        Worker first = new Worker(env, modelBytes);
        workers.add(first);
    }

    static GtcrnDenoiser open(Context c) throws IOException {
        try (InputStream asset = c.getAssets().open("gtcrn_simple.onnx")) {
            ByteArrayOutputStream model = new ByteArrayOutputStream();
            byte[] block = new byte[16384];
            int n;
            while ((n = asset.read(block)) != -1) model.write(block, 0, n);
            int cores = Runtime.getRuntime().availableProcessors();
            int count = Math.max(1, Math.min(4, cores / 2));
            return new GtcrnDenoiser(OrtEnvironment.getEnvironment(), model.toByteArray(), count);
        } catch (OrtException | LinkageError e) {
            throw new IOException(c.getString(R.string.err_gtcrn_load), e);
        }
    }

    static void fft(float[] re, float[] im, boolean inverse) {
        for (int i = 0; i < NFFT; i++) {
            int j = BITREV[i];
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= NFFT; len <<= 1) {
            int half = len >> 1, step = NFFT / len;
            for (int i = 0; i < NFFT; i += len) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    float wr = COS[k], wi = inverse ? -SIN[k] : SIN[k];
                    int u = i + j, v = u + half;
                    float tr = re[v] * wr - im[v] * wi;
                    float ti = re[v] * wi + im[v] * wr;
                    re[v] = re[u] - tr; im[v] = im[u] - ti;
                    re[u] += tr; im[u] += ti;
                }
            }
        }
        if (inverse) {
            float inv = 1f / NFFT;
            for (int i = 0; i < NFFT; i++) { re[i] *= inv; im[i] *= inv; }
        }
    }

    // Per-thread GTCRN engine using direct FloatBuffer + zero-copy native cache tensor chaining.
    private static final class Worker implements AutoCloseable {
        private final OrtEnvironment env;
        private final OrtSession session;
        private final FloatBuffer mixBuf = ByteBuffer.allocateDirect(BINS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer zeroConv = ByteBuffer.allocateDirect(2 * 1 * 16 * 16 * 33 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer zeroTra = ByteBuffer.allocateDirect(2 * 3 * 1 * 1 * 16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer zeroInter = ByteBuffer.allocateDirect(2 * 1 * 33 * 16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final float[] re = new float[NFFT], im = new float[NFFT];
        private final float[] inBuf = new float[NFFT], ola = new float[HOP];
        private final float[] enhArr = new float[BINS * 2];
        private final Map<String, OnnxTensor> inputs = new HashMap<>(4);

        Worker(OrtEnvironment env, byte[] modelBytes) throws OrtException {
            this.env = env;
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(1);
                options.setInterOpNumThreads(1);
                this.session = env.createSession(modelBytes, options);
            }
        }

        short[] processChunk(short[] audio, int warmupLen, int payloadLen) throws OrtException {
            int total = warmupLen + payloadLen;
            short[] out = new short[payloadLen];
            Arrays.fill(inBuf, 0f);
            Arrays.fill(ola, 0f);
            int totalHops = (total + HOP - 1) / HOP + 1;
            int outIndex = -warmupLen;

            OrtSession.Result prev = null;
            OnnxTensor initConv = null, initTra = null, initInter = null;
            try {
                zeroConv.rewind(); zeroTra.rewind(); zeroInter.rewind();
                initConv = OnnxTensor.createTensor(env, zeroConv, CONV_SHAPE);
                initTra = OnnxTensor.createTensor(env, zeroTra, TRA_SHAPE);
                initInter = OnnxTensor.createTensor(env, zeroInter, INTER_SHAPE);

                for (int h = 0; h < totalHops; h++) {
                    System.arraycopy(inBuf, HOP, inBuf, 0, HOP);
                    int base = h * HOP;
                    for (int i = 0; i < HOP; i++) {
                        int idx = base + i;
                        inBuf[HOP + i] = idx < total ? audio[idx] / 32768f : 0f;
                    }
                    for (int i = 0; i < NFFT; i++) { re[i] = inBuf[i] * WIN[i]; im[i] = 0f; }
                    fft(re, im, false);
                    mixBuf.clear();
                    for (int k = 0; k < BINS; k++) { mixBuf.put(re[k]); mixBuf.put(im[k]); }
                    mixBuf.flip();

                    OrtSession.Result next;
                    try (OnnxTensor tMix = OnnxTensor.createTensor(env, mixBuf, MIX_SHAPE)) {
                        inputs.put("mix", tMix);
                        if (prev == null) {
                            inputs.put("conv_cache", initConv);
                            inputs.put("tra_cache", initTra);
                            inputs.put("inter_cache", initInter);
                        } else {
                            inputs.put("conv_cache", (OnnxTensor) prev.get(1));
                            inputs.put("tra_cache", (OnnxTensor) prev.get(2));
                            inputs.put("inter_cache", (OnnxTensor) prev.get(3));
                        }
                        next = session.run(inputs);
                    }
                    if (prev != null) prev.close();
                    prev = next;

                    if (outIndex + HOP >= 0) {
                        FloatBuffer eb = ((OnnxTensor) prev.get(0)).getFloatBuffer();
                        eb.get(enhArr);
                        re[0] = enhArr[0]; im[0] = 0f;
                        re[NFFT / 2] = enhArr[NFFT]; im[NFFT / 2] = 0f;
                        for (int k = 1; k < NFFT / 2; k++) {
                            re[k] = enhArr[2 * k]; im[k] = enhArr[2 * k + 1];
                            re[NFFT - k] = re[k]; im[NFFT - k] = -im[k];
                        }
                        fft(re, im, true);
                        if (h > 0) {
                            for (int i = 0; i < HOP; i++, outIndex++) {
                                if (outIndex >= 0 && outIndex < payloadLen) {
                                    float wet = ola[i] + re[i] * WIN[i];
                                    float blended = (WET * wet + DRY * inBuf[i]) * 32768f;
                                    int v = Math.round(blended);
                                    out[outIndex] = (short) Math.max(-32768, Math.min(32767, v));
                                }
                            }
                        }
                        for (int i = 0; i < HOP; i++) ola[i] = re[HOP + i] * WIN[HOP + i];
                    } else if (h > 0) {
                        outIndex += HOP;
                    }
                }
            } finally {
                if (prev != null) prev.close();
                if (initConv != null) initConv.close();
                if (initTra != null) initTra.close();
                if (initInter != null) initInter.close();
            }
            return out;
        }

        public void close() throws OrtException { session.close(); }
    }

    FrameSink wrap(FrameSink next) {
        final ExecutorService pool = workerCount > 1
            ? Executors.newFixedThreadPool(workerCount, r -> {
                Thread t = new Thread(() -> {
                    try { android.os.Process.setThreadPriority(-6); } catch (Throwable ignored) {}
                    r.run();
                }, "gtcrn-worker");
                t.setDaemon(true);
                return t;
            }) : null;
        activePool = pool;
        return new FrameSink() {
            private final ArrayBlockingQueue<Worker> idle = new ArrayBlockingQueue<>(Math.max(1, workerCount));
            private final ArrayDeque<Future<short[]>> queue = new ArrayDeque<>();
            private final short[] warmupTail = new short[WARMUP_SAMPLES];
            private int warmupFilled;
            private short[] chunkBuf = new short[WARMUP_SAMPLES + CHUNK_SAMPLES];
            private int chunkWarmup, chunkPayload;
            private short[] outFrame = new short[Signal.FRAME];
            private int outUsed;
            private int createdWorkers = 1;
            { idle.add(workers.get(0)); }

            private Worker borrowWorker() throws Exception {
                Worker w = idle.poll();
                if (w != null) return w;
                synchronized (GtcrnDenoiser.this) {
                    if (createdWorkers < workerCount) {
                        Worker extra = new Worker(env, modelBytes);
                        workers.add(extra);
                        createdWorkers++;
                        return extra;
                    }
                }
                return idle.take();
            }

            public void accept(short[] frame) throws Exception {
                int off = 0;
                while (off < frame.length) {
                    if (chunkPayload == 0) {
                        chunkWarmup = warmupFilled;
                        if (chunkWarmup > 0) System.arraycopy(warmupTail, WARMUP_SAMPLES - chunkWarmup, chunkBuf, 0, chunkWarmup);
                    }
                    int take = Math.min(CHUNK_SAMPLES - chunkPayload, frame.length - off);
                    System.arraycopy(frame, off, chunkBuf, chunkWarmup + chunkPayload, take);
                    chunkPayload += take;
                    off += take;
                    if (chunkPayload == CHUNK_SAMPLES) submitChunk();
                }
            }

            private void submitChunk() throws Exception {
                final short[] audio = chunkBuf;
                final int wLen = chunkWarmup;
                final int pLen = chunkPayload;
                int total = wLen + pLen;
                int keep = Math.min(WARMUP_SAMPLES, total);
                System.arraycopy(audio, total - keep, warmupTail, WARMUP_SAMPLES - keep, keep);
                warmupFilled = keep;
                chunkBuf = new short[WARMUP_SAMPLES + CHUNK_SAMPLES];
                chunkPayload = 0;
                chunkWarmup = 0;

                if (pool == null) {
                    Worker w = idle.take();
                    try { emitChunk(w.processChunk(audio, wLen, pLen)); }
                    finally { idle.put(w); }
                    return;
                }
                queue.addLast(pool.submit(() -> {
                    Worker w = borrowWorker();
                    try { return w.processChunk(audio, wLen, pLen); }
                    finally { idle.put(w); }
                }));
                if (queue.size() >= workerCount + 2) drainOne();
            }

            private void drainOne() throws Exception {
                try {
                    emitChunk(queue.removeFirst().get());
                } catch (ExecutionException e) {
                    cancelPool();
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new IOException(cause);
                } catch (InterruptedException e) {
                    cancelPool();
                    Thread.currentThread().interrupt();
                    throw new IOException("Conversion cancelled.", e);
                }
            }

            private void emitChunk(short[] samples) throws Exception {
                int off = 0;
                while (off < samples.length) {
                    int take = Math.min(Signal.FRAME - outUsed, samples.length - off);
                    System.arraycopy(samples, off, outFrame, outUsed, take);
                    outUsed += take;
                    off += take;
                    if (outUsed == Signal.FRAME) {
                        next.accept(outFrame);
                        outFrame = new short[Signal.FRAME];
                        outUsed = 0;
                    }
                }
            }

            private void cancelPool() {
                for (Future<short[]> f : queue) f.cancel(true);
                queue.clear();
                if (pool != null) pool.shutdownNow();
            }

            public void finish() throws Exception {
                try {
                    if (chunkPayload > 0) submitChunk();
                    while (!queue.isEmpty()) drainOne();
                    if (outUsed > 0) {
                        next.accept(Arrays.copyOf(outFrame, outUsed));
                        outUsed = 0;
                    }
                    next.finish();
                } finally {
                    if (pool != null) pool.shutdownNow();
                }
            }
        };
    }

    // Wraps a Signal.Sink so GTCRN runs AFTER Silero pause-trimming (skipping deleted pauses)
    // while preserving exact per-frame quiet flags for VoiceClarity.Leveler and Convert.Parts.
    static final class SinkChain implements Signal.Sink {
        private final FrameSink inner;
        private final ArrayDeque<Boolean> quietQueue = new ArrayDeque<>();
        private final ArrayDeque<Integer> lenQueue = new ArrayDeque<>();
        private boolean finished;

        SinkChain(GtcrnDenoiser denoiser, Signal.Sink downstream) {
            this.inner = denoiser.wrap(new FrameSink() {
                public void accept(short[] frame) throws Exception {
                    int off = 0;
                    while (off < frame.length) {
                        int want = lenQueue.isEmpty() ? (frame.length - off) : lenQueue.removeFirst();
                        boolean q = quietQueue.isEmpty() ? false : quietQueue.removeFirst();
                        if (off == 0 && want == frame.length) {
                            downstream.put(frame, q);
                            return;
                        }
                        int take = Math.min(want, frame.length - off);
                        if (take < want) {
                            lenQueue.addFirst(want - take);
                            quietQueue.addFirst(q);
                        }
                        downstream.put(Arrays.copyOfRange(frame, off, off + take), q);
                        off += take;
                    }
                }
                public void finish() {}
            });
        }

        public void put(short[] frame, boolean quiet) throws Exception {
            lenQueue.addLast(frame.length);
            quietQueue.addLast(quiet);
            inner.accept(frame);
        }

        void finish() throws Exception {
            if (!finished) {
                finished = true;
                inner.finish();
            }
        }
    }

    public void close() throws OrtException {
        ExecutorService pool = activePool;
        activePool = null;
        if (pool != null) {
            pool.shutdownNow();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        OrtException err = null;
        synchronized (workers) {
            for (Worker w : workers) {
                try { w.close(); } catch (OrtException e) { if (err == null) err = e; }
            }
            workers.clear();
        }
        if (err != null) throw err;
    }
}
