package io.mycat.yang.rt;

public class LongTest {
    public static void main(String[] args) {
        System.out.println(Long.lowestOneBit(0x0C));
        System.out.println(Long.highestOneBit(0x0C));
        System.out.println(Long.numberOfTrailingZeros(0x0C));
    }
}
