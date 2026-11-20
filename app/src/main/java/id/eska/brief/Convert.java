package id.eska.brief;

import android.content.*;
import android.media.*;
import android.net.Uri;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

final class Convert {
    interface Output {
        Encoder open() throws Exception;
        void progress(long us,long total) throws Exception;
    }
    static final class Parts {
        final Profile profile;
        final Output output;
        final ArrayDeque<short[]> tail=new ArrayDeque<>();
        Encoder encoder;
        long kept;
        boolean partQuiet;
        Parts(Profile profile,Output output) { this.profile=profile; this.output=output; }
        void write(short[] frame,boolean quiet) throws Exception {
            if (encoder==null) encoder=output.open();
            long limit=(long)profile.seconds*Signal.RATE;
            // Leave headroom for container overhead, encoder delay and the last packet.
            boolean size=encoder.bytesWritten()>=profile.bytes*.95;
            boolean hard=limit>0 && encoder.frames+frame.length>limit;
            boolean gap=limit>0 && encoder.frames>=(limit-3L*Signal.RATE) && quiet && partQuiet;
            if (hard || size || gap) {
                end(); encoder=output.open();
                if (!gap && !(quiet && partQuiet)) for (short[] previous:tail) encoder.write(previous);
                tail.clear();
            }
            encoder.write(frame); kept+=frame.length; partQuiet=quiet;
            tail.addLast(frame); if (tail.size()>50) tail.removeFirst();
        }
        void end() throws Exception {
            if (encoder!=null) { try { encoder.finish(); } finally { encoder.close(); encoder=null; } }
        }
    }

    // Decouples MediaCodec encoding + Ogg/M4A I/O onto its own thread so decoding and DSP/AI overlap.
    static final class AsyncSink implements Signal.Sink, AutoCloseable {
        private static final class Item {
            final short[] frame;
            final boolean quiet;
            Item(short[] frame, boolean quiet) { this.frame = frame; this.quiet = quiet; }
        }
        private static final Item EOF = new Item(null, false);
        private final ArrayBlockingQueue<Item> queue = new ArrayBlockingQueue<>(256);
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final Thread worker;
        private volatile boolean aborted;
        private boolean closed;

        AsyncSink(Signal.Sink target) {
            worker = new Thread(() -> {
                try {
                    try { android.os.Process.setThreadPriority(-6); } catch (Throwable ignored) {}
                    while (!aborted && !Thread.currentThread().isInterrupted()) {
                        Item item = queue.take();
                        if (item == EOF || aborted) break;
                        target.put(item.frame, item.quiet);
                    }
                } catch (Throwable t) {
                    if (!aborted) error.compareAndSet(null, t);
                    queue.clear();
                }
            }, "audio-encode");
            worker.start();
        }

        private void checkError() throws Exception {
            Throwable t = error.get();
            if (t == null) return;
            if (t instanceof Exception) throw (Exception) t;
            throw new IOException(t);
        }

        public void put(short[] frame, boolean quiet) throws Exception {
            checkError();
            Item item = new Item(frame, quiet);
            while (!aborted && !queue.offer(item, 50, TimeUnit.MILLISECONDS)) {
                checkError();
            }
        }

        void finish() throws Exception {
            if (closed) return;
            closed = true;
            while (!aborted && error.get() == null && !queue.offer(EOF, 50, TimeUnit.MILLISECONDS)) {}
            try { worker.join(); } catch (InterruptedException e) {
                aborted = true;
                queue.clear();
                worker.interrupt();
                awaitWorkerExit();
                Thread.currentThread().interrupt();
                throw new IOException("Conversion cancelled.", e);
            }
            checkError();
        }

        public void close() {
            if (closed) return;
            closed = true;
            aborted = true;
            queue.clear();
            worker.interrupt();
            awaitWorkerExit();
        }

        private void awaitWorkerExit() {
            boolean interrupted = false;
            while (worker.isAlive()) {
                try { worker.join(); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static long run(Context context, Uri uri, FrameSink signal, Output output) throws Exception {
        MediaExtractor x=new MediaExtractor();
        MediaCodec decoder=null;
        try {
            x.setDataSource(context,uri,null);
            MediaFormat f=null;
            for (int i=0;i<x.getTrackCount();i++) {
                MediaFormat candidate=x.getTrackFormat(i);
                if (candidate.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                    f=candidate; x.selectTrack(i); break;
                }
            }
            if (f==null) throw new IOException(context.getString(R.string.err_no_audio));
            long total=f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
            String mime=f.getString(MediaFormat.KEY_MIME);
            decoder=MediaCodec.createDecoderByType(mime);
            f.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
            decoder.configure(f,null,null,0); decoder.start();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            Signal.Resampler resampler=null;
            boolean fed=false, end=false;
            long last=0, duration=0;
            while (!end) {
                boolean progressed=false;
                if (!fed) {
                    int i=decoder.dequeueInputBuffer(0);
                    if (i>=0) {
                        progressed=true;
                        ByteBuffer b=decoder.getInputBuffer(i);
                        int n=x.readSampleData(b,0);
                        if (n<0) { decoder.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); fed=true; }
                        else { decoder.queueInputBuffer(i,0,n,x.getSampleTime(),0); x.advance(); }
                    }
                }
                int i=decoder.dequeueOutputBuffer(info,progressed ? 0 : 2000);
                while (i!=MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat fmt=decoder.getOutputFormat();
                        if (fmt.getInteger(MediaFormat.KEY_PCM_ENCODING,2)!=2) throw new IOException(context.getString(R.string.err_pcm_unsupported));
                        if (resampler!=null) { resampler.finish(); duration+=resampler.inputFrames*1000/resampler.rate; }
                        resampler=new Signal.Resampler(signal,fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    } else if (i>=0) {
                        if (info.size>0) {
                            ByteBuffer b=decoder.getOutputBuffer(i);
                            b.position(info.offset); b.limit(info.offset+info.size);
                            resampler.read(b.slice());
                        }
                        end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                        decoder.releaseOutputBuffer(i,false);
                        if (end) break;
                    } else {
                        break;
                    }
                    long now=android.os.SystemClock.elapsedRealtime();
                    if (now-last>=500) { output.progress(resampler==null?0:(duration*1000+resampler.inputFrames*1000000/resampler.rate),total); last=now; }
                    i=decoder.dequeueOutputBuffer(info,0);
                }
                long now=android.os.SystemClock.elapsedRealtime();
                if (now-last>=500) { output.progress(resampler==null?0:(duration*1000+resampler.inputFrames*1000000/resampler.rate),total); last=now; }
            }
            if (resampler==null || resampler.inputFrames==0) throw new IOException(context.getString(R.string.err_empty_audio));
            resampler.finish();
            return duration+resampler.inputFrames*1000/resampler.rate;
        } finally { if (decoder!=null) decoder.release(); x.release(); }
    }
}
