package com.zhc.io.testreactor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

//对应nioeventloop
public class SelectorThread extends ThreadLocal<LinkedBlockingQueue<Channel>> implements Runnable{
    //每线程对应一个selector，多线程情况下

    //先搞出eventloop的框架
    //1.首先要有个selector
    Selector selector = null;
    LinkedBlockingQueue<Channel> lbq = get();
    SelectorThreadGroup stg = null;

    @Override
    protected LinkedBlockingQueue<Channel> initialValue() {
        return new LinkedBlockingQueue<>();//你要丰富的是这里！  pool。。。
    }


    SelectorThread(SelectorThreadGroup stg) {
        try {
            //仅仅是初始化了selector,还没有注册ServerSocketChannel,什么socket bind listen  nonblock啊
            selector = Selector.open();

            this.stg = stg;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //2.然后写一下eventloop的框架
    @Override
    public void run() {

        //loop
        while (true) {
            try {
                //1.select
                int nums = selector.select(); //阻塞
                //注意,调用select的时候只会监听调用时已经注册的fd,
                // 如果select阻塞过程中有其他线程往这个selector注册了fd，这个新来的fd上的事件不会被监听到
                //这样可能就会导致永远阻塞出不来了,这里引出有个wakeup方法,进而会导致nums可能不大于0,但是还会有机会处理一些额外的task
                //2.处理selectkeys
                if (nums > 0) {
                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> iter = keys.iterator();
                    while(iter.hasNext()) {//线性处理
                        SelectionKey key = iter.next();
                        iter.remove();
                        if (key.isAcceptable()) { //复杂,接收客户端的过程,多线程情况下新的客户端送到哪里呢？？
                            acceptHandler(key);
                        } else if (key.isReadable()) {
                            readHandler(key);
                        } else if (key.isWritable()) {

                        }

                    }
                }
                //3.处理一些task
                if (!lbq.isEmpty()) {
                    Channel c = lbq.take();
                    if (c instanceof  ServerSocketChannel){
                        ServerSocketChannel server = (ServerSocketChannel)c;
                        server.register(selector, SelectionKey.OP_ACCEPT);
                    } else if (c instanceof SocketChannel) {
                        SocketChannel client = (SocketChannel)c;
                        //客户端要绑定字节数组,最终你是要完成读写的
                        //可是你还是要client.read,那绑定这个buffer有什么意义呢
                        //也许把buffer和固定的fd绑定了,有某种意义,比如我需要在buffer中暂存
                        ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
                        client.register(selector, SelectionKey.OP_READ, buffer);

                    }
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void readHandler(SelectionKey key) {
        SocketChannel client = (SocketChannel) key.channel();
        //给channel附加的buffer
        ByteBuffer buffer = (ByteBuffer)key.attachment();
        buffer.clear();
        while(true) {
            try {
                int num = client.read(buffer);
                if (num > 0) {
                    //读到东西
                    //给我发啥我就回显
                    //反转??
                    buffer.flip();
                    while(buffer.hasRemaining()) {
                        client.write(buffer);
                    }
                    buffer.clear();
                }
                else if (num == 0) {
                    //没读到东西,或者读完了
                    break;
                }else if(num < 0) {
                    //客户端断开了
                    System.out.println("client: " + client.getRemoteAddress());
                    //取消注册
                    key.cancel();
                    break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void acceptHandler(SelectionKey key) {
        //拿到key上注册的channel
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        try {
            SocketChannel client = server.accept();
            //这一步设置成非阻塞,会有什么后果??必须的，必须是非阻塞才能注册
            client.configureBlocking(false);

            //这一步开始出现和单线程不一样的地方,需要选择一个多路复用器,重点是选择的逻辑
            //去主线程逆推

            //逆推完了,我就知道我要调用自己所在group的nextselector方法了,所以我得引用到group
            //循环引用？？？？
            stg.nextSelectorV3(client);


        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    public void setWorker(SelectorThreadGroup stgWorker) {
        this.stg =  stgWorker;
    }



}
