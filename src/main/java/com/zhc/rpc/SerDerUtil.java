package com.zhc.rpc;


import java.io.*;

public class SerDerUtil {
    static ByteArrayOutputStream out = new ByteArrayOutputStream();


    //序列化工具,想重复利用内存空间,就得加锁
    //这样每次序列化就没必要重开空间了,工具类

    //输入输出流是对输入输出设备的抽象
    //inputstream有read方法,从流中读字节
    //ouputstream有write方法,往流中写字节
    //利用装饰器模式,避免自己手工将数据转换为byte

    //序列化 obj - byte[]
    //outpuestarm输出流 输出流是相对程序而言的，程序把数据传输到外部需要借助输出流
    // 1. ObjectOutputStream套ByteArrayOutputStream
    // 2. ObjectOutputStream writeObject 往ObjectOutputStream里面写对象
    // 3. ByteArrayOutputStream里面读出字节数组(严格来说不能叫读,一般都是往输出流里面write)
    public synchronized static byte[] ser(Object msg) {
        out.reset();
        ObjectOutputStream oout = null;
        byte[] msgBody = null;

        try {
            oout = new ObjectOutputStream(out);
            oout.writeObject(msg);
            msgBody = out.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return msgBody;
    }

    public static Object der(byte[] msgBody) {
        ByteArrayInputStream in = new ByteArrayInputStream(msgBody);
        Object msg = null;
        try {
            ObjectInputStream oin = new ObjectInputStream(in);
            msg = oin.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return msg;

    }
}
