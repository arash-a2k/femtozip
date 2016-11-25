/**
 *   Copyright 2011 Garrick Toubassi
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.toubassi.femtozip.substring;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayOutputStream;

public class SubstringUnpacker implements SubstringPacker.Consumer {
    private ByteBuf dictionary;
    private ByteBuf bytesOut;
    private ByteBuf unpackedBytes;
    
    public SubstringUnpacker(ByteBuf dictionary) {
        this.dictionary = dictionary == null ? Unpooled.buffer(0) : dictionary;
        bytesOut = Unpooled.buffer(1024);
    }
    
    public void encodeLiteral(int aByte, Object context) {
        bytesOut.writeByte(aByte);
    }
    
    public ByteBuf getUnpackedBytes() {
        if (unpackedBytes == null) {
            unpackedBytes = bytesOut;
            unpackedBytes.resetReaderIndex();
            bytesOut = Unpooled.buffer(1024);
        }
        return unpackedBytes;
    }

    public void encodeSubstring(int offset, int length, Object context) {
        int dictLength = dictionary.readableBytes();

        int currentIndex = bytesOut.readableBytes();
        if (currentIndex + offset < 0) {
            int startDict = currentIndex + offset + dictLength;
            int endDict = startDict + length;
            int end = 0;
            
            if (endDict > dictLength) {
                end = endDict - dictLength;
                endDict = dictLength;
            }
            for (int i = startDict; i < endDict; i++) {
                bytesOut.writeByte(dictionary.getByte(i));
            }
            
            if (end > 0) {
                for (int i = 0; i < end; i++) {
                    bytesOut.writeByte(bytesOut.getByte(i));
                }
            }
        }
        else {
            for (int i = currentIndex + offset, count = currentIndex + offset + length; i < count; i++) {
                bytesOut.writeByte(bytesOut.getByte(i));
            }
        }
    }
    
    public void endEncoding(Object context) {
    }

    private static class ByteOutput extends ByteArrayOutputStream {
        public byte get(int i) {
            return buf[i];
        }
    }
}
