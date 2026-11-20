package id.eska.brief;

interface FrameSink {
    void accept(short[] frame) throws Exception;
    void finish() throws Exception;
}
