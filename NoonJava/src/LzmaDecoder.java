import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Minimal public-domain-style LZMA decoder compatible with the 5-byte
 * properties written by the 7-Zip LzmaCompress API used by content.dat. */
public final class LzmaDecoder {
    private LzmaDecoder() {}

    public static void decode(byte[] packed, byte[] props, long outSize, OutputStream out) throws IOException {
        if (props == null || props.length != 5) throw new IOException("Invalid LZMA properties");
        Decoder dec = new Decoder();
        if (!dec.setDecoderProperties(props)) throw new IOException("Unsupported LZMA properties");
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(packed);
        if (!dec.code(in, out, outSize)) throw new IOException("LZMA decode failed");
    }

    private static final class Base {
        static final int kNumRepDistances = 4;
        static final int kNumStates = 12;
        static final int kNumPosSlotBits = 6;
        static final int kDicLogSizeMin = 0;
        static final int kNumLenToPosStatesBits = 2;
        static final int kNumLenToPosStates = 1 << kNumLenToPosStatesBits;
        static final int kMatchMinLen = 2;
        static final int kNumAlignBits = 4;
        static final int kAlignTableSize = 1 << kNumAlignBits;
        static final int kAlignMask = kAlignTableSize - 1;
        static final int kStartPosModelIndex = 4;
        static final int kEndPosModelIndex = 14;
        static final int kNumPosModels = kEndPosModelIndex - kStartPosModelIndex;
        static final int kNumFullDistances = 1 << (kEndPosModelIndex / 2);
        static final int kNumLitPosStatesBitsEncodingMax = 4;
        static final int kNumLitContextBitsMax = 8;
        static final int kNumPosStatesBitsMax = 4;
        static final int kNumPosStatesMax = 1 << kNumPosStatesBitsMax;
        static final int kNumPosStatesBitsEncodingMax = 4;
        static final int kNumLowLenBits = 3;
        static final int kNumMidLenBits = 3;
        static final int kNumHighLenBits = 8;
        static final int kNumLowLenSymbols = 1 << kNumLowLenBits;
        static final int kNumMidLenSymbols = 1 << kNumMidLenBits;

        static int stateInit() { return 0; }
        static int stateUpdateChar(int index) { if (index < 4) return 0; if (index < 10) return index - 3; return index - 6; }
        static int stateUpdateMatch(int index) { return index < 7 ? 7 : 10; }
        static int stateUpdateRep(int index) { return index < 7 ? 8 : 11; }
        static int stateUpdateShortRep(int index) { return index < 7 ? 9 : 11; }
        static boolean stateIsCharState(int index) { return index < 7; }
        static int getLenToPosState(int len) { len -= kMatchMinLen; if (len < kNumLenToPosStates) return len; return kNumLenToPosStates - 1; }
    }

    private static final class OutWindow {
        private byte[] buffer;
        private int pos;
        private int windowSize;
        private int streamPos;
        private OutputStream stream;

        void create(int windowSize) {
            if (buffer == null || this.windowSize != windowSize) buffer = new byte[windowSize];
            this.windowSize = windowSize; pos = 0; streamPos = 0;
        }
        void setStream(OutputStream stream) throws IOException { releaseStream(); this.stream = stream; }
        void releaseStream() throws IOException { flush(); stream = null; }
        void init(boolean solid) { if (!solid) { streamPos = 0; pos = 0; } }
        void flush() throws IOException {
            int size = pos - streamPos;
            if (size == 0) return;
            stream.write(buffer, streamPos, size);
            if (pos >= windowSize) pos = 0;
            streamPos = pos;
        }
        void copyBlock(int distance, int len) throws IOException {
            int p = pos - distance - 1;
            if (p < 0) p += windowSize;
            while (len-- > 0) {
                if (p >= windowSize) p = 0;
                buffer[pos++] = buffer[p++];
                if (pos >= windowSize) flush();
            }
        }
        void putByte(byte b) throws IOException { buffer[pos++] = b; if (pos >= windowSize) flush(); }
        byte getByte(int distance) { int p = pos - distance - 1; if (p < 0) p += windowSize; return buffer[p]; }
    }

    private static final class RangeDecoder {
        static final int kTopMask = ~((1 << 24) - 1);
        static final int kNumBitModelTotalBits = 11;
        static final int kBitModelTotal = 1 << kNumBitModelTotalBits;
        static final int kNumMoveBits = 5;
        private InputStream stream;
        int range;
        int code;
        void setStream(InputStream stream) { this.stream = stream; }
        void releaseStream() { stream = null; }
        void init() throws IOException { code = 0; range = -1; for (int i=0;i<5;i++) code = (code << 8) | readByte(); }
        private int readByte() throws IOException { int b=stream.read(); if (b<0) throw new IOException("Unexpected end of LZMA stream"); return b; }
        int decodeDirectBits(int numTotalBits) throws IOException {
            int result=0;
            for (int i=numTotalBits;i>0;i--) {
                range >>>= 1;
                int t = (code - range) >>> 31;
                code -= range & (t - 1);
                result = (result << 1) | (1 - t);
                if ((range & kTopMask) == 0) { code = (code << 8) | readByte(); range <<= 8; }
            }
            return result;
        }
        int decodeBit(short[] probs, int index) throws IOException {
            int prob = probs[index] & 0xFFFF;
            int newBound = (range >>> kNumBitModelTotalBits) * prob;
            if ((code ^ 0x80000000) < (newBound ^ 0x80000000)) {
                range = newBound;
                probs[index] = (short)(prob + ((kBitModelTotal - prob) >>> kNumMoveBits));
                if ((range & kTopMask)==0) { code=(code<<8)|readByte(); range<<=8; }
                return 0;
            } else {
                range -= newBound; code -= newBound;
                probs[index] = (short)(prob - (prob >>> kNumMoveBits));
                if ((range & kTopMask)==0) { code=(code<<8)|readByte(); range<<=8; }
                return 1;
            }
        }
        static void initBitModels(short[] probs) { for(int i=0;i<probs.length;i++) probs[i]=(short)(kBitModelTotal>>>1); }
    }

    private static final class BitTreeDecoder {
        final short[] models; final int numBitLevels;
        BitTreeDecoder(int numBitLevels) { this.numBitLevels=numBitLevels; models=new short[1<<numBitLevels]; }
        void init(){ RangeDecoder.initBitModels(models); }
        int decode(RangeDecoder r) throws IOException { int m=1; for(int i=numBitLevels;i>0;i--) m=(m<<1)+r.decodeBit(models,m); return m-(1<<numBitLevels); }
        int reverseDecode(RangeDecoder r) throws IOException { int m=1,symbol=0; for(int bitIndex=0;bitIndex<numBitLevels;bitIndex++){int bit=r.decodeBit(models,m);m<<=1;m+=bit;symbol|=bit<<bitIndex;}return symbol; }
        static int reverseDecode(short[] models,int startIndex,RangeDecoder r,int numBitLevels) throws IOException { int m=1,symbol=0; for(int bitIndex=0;bitIndex<numBitLevels;bitIndex++){int bit=r.decodeBit(models,startIndex+m);m<<=1;m+=bit;symbol|=bit<<bitIndex;}return symbol; }
    }

    private static final class LenDecoder {
        final short[] choice = new short[2];
        final BitTreeDecoder[] lowCoder = new BitTreeDecoder[Base.kNumPosStatesMax];
        final BitTreeDecoder[] midCoder = new BitTreeDecoder[Base.kNumPosStatesMax];
        final BitTreeDecoder highCoder = new BitTreeDecoder(Base.kNumHighLenBits);
        int numPosStates=0;
        void create(int numPosStates){ for(;this.numPosStates<numPosStates;this.numPosStates++){lowCoder[this.numPosStates]=new BitTreeDecoder(Base.kNumLowLenBits);midCoder[this.numPosStates]=new BitTreeDecoder(Base.kNumMidLenBits);} }
        void init(){ RangeDecoder.initBitModels(choice); for(int posState=0;posState<numPosStates;posState++){lowCoder[posState].init();midCoder[posState].init();} highCoder.init(); }
        int decode(RangeDecoder r,int posState)throws IOException{ if(r.decodeBit(choice,0)==0)return lowCoder[posState].decode(r); int symbol=Base.kNumLowLenSymbols; if(r.decodeBit(choice,1)==0)symbol+=midCoder[posState].decode(r);else symbol+=Base.kNumMidLenSymbols+highCoder.decode(r); return symbol; }
    }

    private static final class LiteralDecoder {
        private Decoder2[] coders; private int numPrevBits; private int numPosBits; private int posMask;
        void create(int numPosBits,int numPrevBits){ if(coders!=null&&this.numPrevBits==numPrevBits&&this.numPosBits==numPosBits)return; this.numPosBits=numPosBits;posMask=(1<<numPosBits)-1;this.numPrevBits=numPrevBits;int numStates=1<<(this.numPrevBits+this.numPosBits);coders=new Decoder2[numStates];for(int i=0;i<numStates;i++)coders[i]=new Decoder2(); }
        void init(){ int numStates=1<<(numPrevBits+numPosBits);for(int i=0;i<numStates;i++)coders[i].init(); }
        Decoder2 getDecoder(int pos,byte prevByte){ return coders[((pos&posMask)<<numPrevBits)+((prevByte&0xFF) >>> (8-numPrevBits))]; }
        private static final class Decoder2 {
            short[] decoders=new short[0x300]; void init(){RangeDecoder.initBitModels(decoders);} byte decodeNormal(RangeDecoder r)throws IOException{int symbol=1;do{symbol=(symbol<<1)|r.decodeBit(decoders,symbol);}while(symbol<0x100);return(byte)symbol;} byte decodeWithMatchByte(RangeDecoder r,byte matchByte)throws IOException{int symbol=1;do{int matchBit=(matchByte>>7)&1;matchByte<<=1;int bit=r.decodeBit(decoders,((1+matchBit)<<8)+symbol);symbol=(symbol<<1)|bit;if(matchBit!=bit){while(symbol<0x100)symbol=(symbol<<1)|r.decodeBit(decoders,symbol);break;}}while(symbol<0x100);return(byte)symbol;}
        }
    }

    private static final class Decoder {
        final OutWindow outWindow=new OutWindow(); final RangeDecoder rangeDecoder=new RangeDecoder();
        final short[] isMatchDecoders=new short[Base.kNumStates<<Base.kNumPosStatesBitsMax];
        final short[] isRepDecoders=new short[Base.kNumStates]; final short[] isRepG0Decoders=new short[Base.kNumStates]; final short[] isRepG1Decoders=new short[Base.kNumStates]; final short[] isRepG2Decoders=new short[Base.kNumStates];
        final short[] isRep0LongDecoders=new short[Base.kNumStates<<Base.kNumPosStatesBitsMax];
        final BitTreeDecoder[] posSlotDecoder=new BitTreeDecoder[Base.kNumLenToPosStates];
        final short[] posDecoders=new short[Base.kNumFullDistances-Base.kEndPosModelIndex];
        final BitTreeDecoder posAlignDecoder=new BitTreeDecoder(Base.kNumAlignBits);
        final LenDecoder lenDecoder=new LenDecoder(); final LenDecoder repLenDecoder=new LenDecoder(); final LiteralDecoder literalDecoder=new LiteralDecoder();
        int dictionarySize=-1; int dictionarySizeCheck=-1; int posStateMask;
        Decoder(){ for(int i=0;i<Base.kNumLenToPosStates;i++)posSlotDecoder[i]=new BitTreeDecoder(Base.kNumPosSlotBits); }
        boolean setDictionarySize(int dictionarySize){ if(dictionarySize<0)return false; if(this.dictionarySize!=dictionarySize){this.dictionarySize=dictionarySize;dictionarySizeCheck=Math.max(this.dictionarySize,1);outWindow.create(Math.max(dictionarySizeCheck,1<<12));}return true; }
        boolean setLcLpPb(int lc,int lp,int pb){ if(lc>Base.kNumLitContextBitsMax||lp>Base.kNumLitPosStatesBitsEncodingMax||pb>Base.kNumPosStatesBitsEncodingMax)return false;literalDecoder.create(lp,lc);int numPosStates=1<<pb;lenDecoder.create(numPosStates);repLenDecoder.create(numPosStates);posStateMask=numPosStates-1;return true; }
        void init() throws IOException { outWindow.init(false); RangeDecoder.initBitModels(isMatchDecoders);RangeDecoder.initBitModels(isRep0LongDecoders);RangeDecoder.initBitModels(isRepDecoders);RangeDecoder.initBitModels(isRepG0Decoders);RangeDecoder.initBitModels(isRepG1Decoders);RangeDecoder.initBitModels(isRepG2Decoders);RangeDecoder.initBitModels(posDecoders);literalDecoder.init();for(int i=0;i<Base.kNumLenToPosStates;i++)posSlotDecoder[i].init();lenDecoder.init();repLenDecoder.init();posAlignDecoder.init();rangeDecoder.init(); }
        boolean code(InputStream in,OutputStream out,long outSize)throws IOException{
            rangeDecoder.setStream(in);outWindow.setStream(out);init();
            int state=Base.stateInit();int rep0=0,rep1=0,rep2=0,rep3=0;long nowPos64=0;byte prevByte=0;
            while(outSize<0 || nowPos64<outSize){
                int posState=((int)nowPos64)&posStateMask;
                if(rangeDecoder.decodeBit(isMatchDecoders,(state<<Base.kNumPosStatesBitsMax)+posState)==0){
                    LiteralDecoder.Decoder2 decoder2=literalDecoder.getDecoder((int)nowPos64,prevByte);
                    if(!Base.stateIsCharState(state)) prevByte=decoder2.decodeWithMatchByte(rangeDecoder,outWindow.getByte(rep0)); else prevByte=decoder2.decodeNormal(rangeDecoder);
                    outWindow.putByte(prevByte);state=Base.stateUpdateChar(state);nowPos64++;
                } else {
                    int len;
                    if(rangeDecoder.decodeBit(isRepDecoders,state)==1){
                        len=0;
                        if(rangeDecoder.decodeBit(isRepG0Decoders,state)==0){
                            if(rangeDecoder.decodeBit(isRep0LongDecoders,(state<<Base.kNumPosStatesBitsMax)+posState)==0){state=Base.stateUpdateShortRep(state);len=1;}
                        } else {
                            int distance;
                            if(rangeDecoder.decodeBit(isRepG1Decoders,state)==0)distance=rep1;else{if(rangeDecoder.decodeBit(isRepG2Decoders,state)==0)distance=rep2;else{distance=rep3;rep3=rep2;}rep2=rep1;}rep1=rep0;rep0=distance;
                        }
                        if(len==0){len=repLenDecoder.decode(rangeDecoder,posState)+Base.kMatchMinLen;state=Base.stateUpdateRep(state);}
                    } else {
                        rep3=rep2;rep2=rep1;rep1=rep0;len=Base.kMatchMinLen+lenDecoder.decode(rangeDecoder,posState);state=Base.stateUpdateMatch(state);
                        int posSlot=posSlotDecoder[Base.getLenToPosState(len)].decode(rangeDecoder);
                        if(posSlot>=Base.kStartPosModelIndex){int numDirectBits=(posSlot>>1)-1;rep0=(2|(posSlot&1))<<numDirectBits;if(posSlot<Base.kEndPosModelIndex)rep0+=BitTreeDecoder.reverseDecode(posDecoders,rep0-posSlot-1,rangeDecoder,numDirectBits);else{rep0+=(rangeDecoder.decodeDirectBits(numDirectBits-Base.kNumAlignBits)<<Base.kNumAlignBits);rep0+=posAlignDecoder.reverseDecode(rangeDecoder);if(rep0<0){if(rep0==-1)break;return false;}}}else rep0=posSlot;
                    }
                    if(rep0>=nowPos64 || rep0>=dictionarySizeCheck)return false;
                    outWindow.copyBlock(rep0,len);nowPos64+=len;prevByte=outWindow.getByte(0);
                }
            }
            outWindow.flush();outWindow.releaseStream();rangeDecoder.releaseStream();return true;
        }
        boolean setDecoderProperties(byte[] properties){ if(properties.length<5)return false;int val=properties[0]&0xFF;int lc=val%9;int remainder=val/9;int lp=remainder%5;int pb=remainder/5;if(pb>4)return false;int dictionarySize=0;for(int i=0;i<4;i++)dictionarySize|=(properties[1+i]&0xFF)<<(i*8);return setLcLpPb(lc,lp,pb)&&setDictionarySize(dictionarySize); }
    }
}
