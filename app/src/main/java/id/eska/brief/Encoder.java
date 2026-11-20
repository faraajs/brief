package id.eska.brief;

import android.media.*;
import android.os.ParcelFileDescriptor;
import java.nio.*;
import java.io.*;

final class Encoder implements AutoCloseable {
    private MediaCodec codec;
    private MediaMuxer mux;
    private OggOpus ogg;
    private final ParcelFileDescriptor file;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final short[] batch = new short[8000];
    private int used, track=-1;
    long frames, payload;
    private long sent;
    Encoder(ParcelFileDescriptor file, boolean aac, int kbps) throws Exception {
        this.file=file;
        try {
            String mime=aac ? MediaFormat.MIMETYPE_AUDIO_AAC : MediaFormat.MIMETYPE_AUDIO_OPUS;
            MediaFormat format=MediaFormat.createAudioFormat(mime,Signal.RATE,1);
            format.setInteger(MediaFormat.KEY_BIT_RATE,kbps*1000);
            if (aac) format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            if (aac) mux=new MediaMuxer(file.getFileDescriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            else ogg=new OggOpus(file);
            codec=MediaCodec.createEncoderByType(mime);
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
        } catch(Exception e) { close(); throw e; }
    }
    void write(short[] pcm) throws Exception {
        int at=0;
        while (at<pcm.length) {
            int n=Math.min(batch.length-used,pcm.length-at);
            System.arraycopy(pcm,at,batch,used,n); used+=n; at+=n;
            if (used==batch.length) submit();
        }
        frames+=pcm.length;
    }
    private int input() throws Exception {
        for (int tries=0;tries<2500;tries++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Conversion cancelled.");
            int id=codec.dequeueInputBuffer(2000);
            if (id>=0) return id;
            drain(false);
        }
        throw new IOException("Encoder did not respond.");
    }
    private void submit() throws Exception {
        int at=0;
        while (at<used) {
            int i=input();
            ByteBuffer b=codec.getInputBuffer(i).order(ByteOrder.nativeOrder());
            b.clear(); int n=Math.min(used-at,b.remaining()/2);
            b.asShortBuffer().put(batch,at,n);
            codec.queueInputBuffer(i,0,n*2,sent*1000000/Signal.RATE,0);
            at+=n; sent+=n;
        }
        used=0; drain(false);
    }
    private void drain(boolean untilEnd) throws Exception {
        int idle=0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Conversion cancelled.");
            int id=codec.dequeueOutputBuffer(info,untilEnd ? 10000 : 0);
            if (id==MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd) return;
                if (++idle>500) throw new IOException("Encoder failed to finish audio.");
            } else if (id==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (ogg!=null) ogg.start(codec.getOutputFormat());
                else { track=mux.addTrack(codec.getOutputFormat()); mux.start(); }
            } else if (id>=0) {
                idle=0;
                if (info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                    if (ogg!=null) ogg.packet(codec.getOutputBuffer(id),info.offset,info.size);
                    else mux.writeSampleData(track,codec.getOutputBuffer(id),info);
                    payload+=info.size;
                }
                boolean end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                codec.releaseOutputBuffer(id,false);
                if (end) return;
            }
        }
    }
    long bytesWritten() { return ogg==null ? payload : Math.max(payload,ogg.written); }
    void finish() throws Exception {
        submit(); int i=input();
        codec.queueInputBuffer(i,0,0,sent*1000000/Signal.RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        drain(true);
        if (ogg!=null) ogg.finish(sent); else mux.stop();
    }
    public void close() {
        if (codec!=null) { try { codec.release(); } catch(Exception ignored) {} codec=null; }
        if (mux!=null) { try { mux.release(); } catch(Exception ignored) {} mux=null; }
        if (ogg!=null) { try { ogg.close(); } catch(IOException ignored) {} ogg=null; }
        try { file.close(); } catch(Exception ignored) {}
    }
}
