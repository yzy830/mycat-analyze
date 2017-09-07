/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.backend.mysql;

import java.nio.ByteBuffer;

/**
 * @author mycat
 */
public class BufferUtil {

    public static final void writeUB2(ByteBuffer buffer, int i) {
        buffer.put((byte) (i & 0xff));
        buffer.put((byte) (i >>> 8));
    }

    public static final void writeUB3(ByteBuffer buffer, int i) {
        buffer.put((byte) (i & 0xff));
        buffer.put((byte) (i >>> 8));
        buffer.put((byte) (i >>> 16));
    }

    public static final void writeInt(ByteBuffer buffer, int i) {
        buffer.put((byte) (i & 0xff));
        buffer.put((byte) (i >>> 8));
        buffer.put((byte) (i >>> 16));
        buffer.put((byte) (i >>> 24));
    }

    public static final void writeFloat(ByteBuffer buffer, float f) {
        writeInt(buffer, Float.floatToIntBits(f));
    }

    public static final void writeUB4(ByteBuffer buffer, long l) {
        buffer.put((byte) (l & 0xff));
        buffer.put((byte) (l >>> 8));
        buffer.put((byte) (l >>> 16));
        buffer.put((byte) (l >>> 24));
    }

    public static final void writeLong(ByteBuffer buffer, long l) {
        buffer.put((byte) (l & 0xff));
        buffer.put((byte) (l >>> 8));
        buffer.put((byte) (l >>> 16));
        buffer.put((byte) (l >>> 24));
        buffer.put((byte) (l >>> 32));
        buffer.put((byte) (l >>> 40));
        buffer.put((byte) (l >>> 48));
        buffer.put((byte) (l >>> 56));
    }

    public static final void writeDouble(ByteBuffer buffer, double d) {
        writeLong(buffer, Double.doubleToLongBits(d));
    }

    /**
     * 根据mysql协议，将长度信息写入buffer中
     * 
     * @param buffer
     * @param l
     */
    public static final void writeLength(ByteBuffer buffer, long l) {
        if (l < 251) {
            buffer.put((byte) l);
        } else if (l < 0x10000L) {
            buffer.put((byte) 252);
            writeUB2(buffer, (int) l);
        } else if (l < 0x1000000L) {
            buffer.put((byte) 253);
            writeUB3(buffer, (int) l);
        } else {
            buffer.put((byte) 254);
            writeLong(buffer, l);
        }
    }

    public static final void writeWithNull(ByteBuffer buffer, byte[] src) {
        buffer.put(src);
        buffer.put((byte) 0);
    }

    public static final void writeWithLength(ByteBuffer buffer, byte[] src) {
        int length = src.length;
        if (length < 251) {
            buffer.put((byte) length);
        } else if (length < 0x10000L) {
            buffer.put((byte) 252);
            writeUB2(buffer, length);
        } else if (length < 0x1000000L) {
            buffer.put((byte) 253);
            writeUB3(buffer, length);
        } else {
            buffer.put((byte) 254);
            writeLong(buffer, length);
        }
        buffer.put(src);
    }

    public static final void writeWithLength(ByteBuffer buffer, byte[] src, byte nullValue) {
        if (src == null) {
            buffer.put(nullValue);
        } else {
            writeWithLength(buffer, src);
        }
    }

    /**
     * 这个length的计算是按照mysql协议计算的消息体长度值 = 二进制长度编码 + 数据长度
     * <p>
     * 二进制长度编码方式如下
     * <table>
     *  <tr>
     *      <th>第一个字节值</th>
     *      <th>后续字节数</th>
     *      <th>长度值说明</th>
     *  </tr>
     *  <tr>
     *      <td>0-255</td>
     *      <td>0</td>
     *      <td>第一个字节表示数据长度</td>
     *  </tr>
     *  <tr>
     *      <td>251</td>
     *      <td>0</td>
     *      <td>长度为0</td>
     *  </tr>
     *  <tr>
     *      <td>252</td>
     *      <td>2</td>
     *      <td>后续两个字节表示长度，长度小于2^16(0x10000)</td>
     *  </tr>
     *  <tr>
     *      <td>253</td>
     *      <td>3</td>
     *      <td>后续三个字节表示长度，长度小于2^24(0x1000000)</td>
     *  </tr>
     *  <tr>
     *      <td>254</td>
     *      <td>4</td>
     *      <td>后续四个字节表示长度，长度小于2^32(0x100000000)</td>
     *  </tr>
     * </table>
     * </p>
     * 
     * @param src
     * @return
     */
    public static final int getLength(long length) {
        if (length < 251) {
            return 1;
        } else if (length < 0x10000L) {
            return 3;
        } else if (length < 0x1000000L) {
            return 4;
        } else {
            return 9;
        }
    }

    /**
     * 这个length的计算是按照mysql协议计算的消息体长度值 = 二进制长度编码 + 数据长度
     * <p>
     * 二进制长度编码方式如下
     * <table>
     *  <tr>
     *      <th>第一个字节值</th>
     *      <th>后续字节数</th>
     *      <th>长度值说明</th>
     *  </tr>
     *  <tr>
     *      <td>0-255</td>
     *      <td>0</td>
     *      <td>第一个字节表示数据长度</td>
     *  </tr>
     *  <tr>
     *      <td>251</td>
     *      <td>0</td>
     *      <td>长度为0</td>
     *  </tr>
     *  <tr>
     *      <td>252</td>
     *      <td>2</td>
     *      <td>后续两个字节表示长度，长度小于2^16(0x10000)</td>
     *  </tr>
     *  <tr>
     *      <td>253</td>
     *      <td>3</td>
     *      <td>后续三个字节表示长度，长度小于2^24(0x1000000)</td>
     *  </tr>
     *  <tr>
     *      <td>254</td>
     *      <td>4</td>
     *      <td>后续四个字节表示长度，长度小于2^32(0x100000000)</td>
     *  </tr>
     * </table>
     * </p>
     * 
     * @param src
     * @return
     */
    public static final int getLength(byte[] src) {
        int length = src.length;
        if (length < 251) {
            return 1 + length;
        } else if (length < 0x10000L) {
            return 3 + length;
        } else if (length < 0x1000000L) {
            return 4 + length;
        } else {
            return 9 + length;
        }
    }

}