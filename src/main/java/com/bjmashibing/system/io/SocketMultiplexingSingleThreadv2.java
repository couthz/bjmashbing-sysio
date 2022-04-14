package com.bjmashibing.system.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class SocketMultiplexingSingleThreadv2 {

    private ServerSocketChannel server = null;
    private Selector selector = null;   //linux 多路复用器（select poll epoll） nginx  event{}
    int port = 9090;

    public void initServer() {
        try {
            server = ServerSocketChannel.open();
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(port));
            selector = Selector.open();  //  select  poll  *epoll
            server.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void start() {
        initServer();
        System.out.println("服务器启动了。。。。。");
        try {
            while (true) {
//                Set<SelectionKey> keys = selector.keys();
//                System.out.println(keys.size()+"   size");
                while (selector.select(50) > 0) {
                    Set<SelectionKey> selectionKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iter = selectionKeys.iterator();
                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();
                        if (key.isAcceptable()) {
                            acceptHandler(key);
                        } else if (key.isReadable()) {
//                            key.cancel();  //现在多路复用器里把key  cancel了
                            System.out.println("in.....");
                            key.interestOps(key.interestOps() | ~SelectionKey.OP_READ);

                            readHandler(key); //只处理了read,并注册关心这个key的write事件
                            //还是阻塞的嘛？ 即便以抛出了线程去读取，但是在时差里，这个key的read事件会被重复触发

                        } else if(key.isWritable()){  //我之前没讲过写的事件！！！！！
//                            key.cancel();
                            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                            writeHandler(key);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void writeHandler(SelectionKey key) {
        new Thread(()->{
            System.out.println("write handler...");
            SocketChannel client = (SocketChannel) key.channel(); //取channel
            ByteBuffer buffer = (ByteBuffer) key.attachment(); //取buffer，和读方法里是一个buffer
            buffer.flip();
            while (buffer.hasRemaining()) {
                try {

                    client.write(buffer);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            buffer.clear();
//            key.cancel();

//            try {
////                client.shutdownOutput();
//
////                client.close();
//
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
        }).start();

    }

    public void acceptHandler(SelectionKey key) {
        try {
            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
            SocketChannel client = ssc.accept();
            client.configureBlocking(false);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            client.register(selector, SelectionKey.OP_READ, buffer);
            System.out.println("-------------------------------------------");
            System.out.println("新客户端：" + client.getRemoteAddress());
            System.out.println("-------------------------------------------");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void readHandler(SelectionKey key) {
        new Thread(()->{
            System.out.println("read handler.....");
            SocketChannel client = (SocketChannel) key.channel();
            ByteBuffer buffer = (ByteBuffer) key.attachment();
            buffer.clear();
            int read = 0;
            try {
                while (true) {
                    read = client.read(buffer);
                    System.out.println(Thread.currentThread().getName()+ " " + read);
                    if (read > 0) {
                        key.interestOps(SelectionKey.OP_READ);
                        //这次没有直接写,而是仅仅注册了写事件
                        client.register(key.selector(),SelectionKey.OP_WRITE,buffer);
                    } else if (read == 0) {

                        break;
                    } else {
                        client.close();
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

    }

    public static void main(String[] args) {
        SocketMultiplexingSingleThreadv2 service = new SocketMultiplexingSingleThreadv2();
        service.start();
    }
}
