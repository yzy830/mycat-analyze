package io.mycat.yang.charset;

import java.io.UnsupportedEncodingException;

public class Latin1Test {
    public static void main(String[] args) throws UnsupportedEncodingException {
        byte[] array = "杨棕源".getBytes("latin1");
        
        //latin1
        String str = new String(array, "latin1");
        
        System.out.println(str);
    }
}
