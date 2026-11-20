package id.eska.brief;

import android.media.MediaFormat;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;

// RFC 3533 page framing, RFC 7845 granules. Batch one second of packets per page.
final class OggOpus implements Closeable {
    private final OutputStream out;
    private final int serial=new Random().nextInt();
    private int sequence,segments,preSkip;
    private long granule,previousPage;
    long written;
    private final byte[] lacing=new byte[255];
    private final ByteArrayOutputStream body=new ByteArrayOutputStream();
    private static final int[] CRC=new int[256];
    static {
        for (int i=0;i<256;i++) {
            int v=i<<24;
            for (int j=0;j<8;j++) v=(v<<1)^((v<0)?0x04c11db7:0);
            CRC[i]=v;
        }
    }
    OggOpus(ParcelFileDescriptor fd) throws IOException {
        out=new BufferedOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor())));
    }
    void start(MediaFormat f) throws IOException {
        ByteBuffer header=f.getByteBuffer("csd-0").duplicate();
        byte[] csd=new byte[header.remaining()]; header.get(csd);
        int head=indexOf(csd,"OpusHead");
        if (head<0 || csd.length-head<19) throw new IOException("Incomplete Opus header.");
        byte[] bytes=java.util.Arrays.copyOfRange(csd,head,head+19);
        ByteBuffer delay=f.getByteBuffer("csd-1");
        int block=indexOf(csd,"AOPUSDLY");
        long ns=delay!=null && delay.remaining()>=8
            ? delay.duplicate().order(ByteOrder.nativeOrder()).getLong()
            : block>=0 && csd.length>=block+24
                ? ByteBuffer.wrap(csd).order(ByteOrder.LITTLE_ENDIAN).getLong(block+16) : -1;
        preSkip=ns>=0 ? (int)Math.round(ns*48000.0/1e9) : (bytes[10]&255)|((bytes[11]&255)<<8);
        bytes[10]=(byte)preSkip; bytes[11]=(byte)(preSkip>>>8);
        append(bytes); page(2,0);
        ByteBuffer tags=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        tags.put("OpusTags".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(0);
        append(tags.array()); page(0,0);
    }
    private static int indexOf(byte[] data,String signature) {
        outer: for (int i=0;i<=data.length-signature.length();i++) {
            for (int j=0;j<signature.length();j++) if (data[i+j]!=signature.charAt(j)) continue outer;
            return i;
        }
        return -1;
    }
    void packet(ByteBuffer data,int offset,int size) throws IOException {
        ByteBuffer b=data.duplicate(); b.position(offset); b.limit(offset+size);
        byte[] packet=new byte[size]; b.get(packet);
        if (granule-previousPage>=48000 || segments+size/255+1>255) page(0,granule);
        int toc=packet[0]&255, config=toc>>>3;
        int per=config>=16 ? 120<<(config&3) : config>=12 ? 480<<(config&1) : (config&3)==3 ? 2880 : 480<<(config&3);
        int code=toc&3, count=code==0?1:code==3?(packet[1]&63):2;
        granule+=(long)per*count; append(packet);
    }
    private void append(byte[] p) {
        int n=p.length;
        while (n>=255) { lacing[segments++]=(byte)255; n-=255; }
        lacing[segments++]=(byte)n; body.write(p,0,p.length);
    }
    private void page(int flags,long position) throws IOException {
        ByteBuffer b=ByteBuffer.allocate(27+segments+body.size()).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x5367674f).put((byte)0).put((byte)flags).putLong(position).putInt(serial).putInt(sequence++).putInt(0);
        b.put((byte)segments).put(lacing,0,segments).put(body.toByteArray());
        byte[] page=b.array(); int crc=0;
        for (byte v:page) crc=(crc<<8)^CRC[((crc>>>24)^(v&255))&255];
        b.putInt(22,crc); out.write(page); written+=page.length; previousPage=position; segments=0; body.reset();
    }
    void finish(long samples) throws IOException {
        page(4,Math.min(granule,samples*48000/Signal.RATE+preSkip)); out.flush();
    }
    public void close() throws IOException { out.close(); }
}
