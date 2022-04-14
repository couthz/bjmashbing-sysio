package com.bjmashibing.system.io.testreactor1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

public class SelectorThread extends ThreadLocal<LinkedBlockingQueue<Channel>> implements Runnable{
    //每线程对应一个selector
    //多线程情况下，该主机，该程序的并发客户端被分配到多个selector上
    //注意，每个客户端只绑定到其中一个selector
    //其实不会有交互问题

    //代码的框架是什么样的??
    Selector selector = null;
    LinkedBlockingQueue<Channel> lbq = get();
    //lbq  在接口或者类中是固定使用方式逻辑写死了。你需要是lbq每个线程持有自己的独立对象
    SelectorThreadGroup stg;

    @Override
    protected LinkedBlockingQueue<Channel> initialValue() {
        return new LinkedBlockingQueue<>(); //你要丰富的是这里 pool...
    }

    SelectorThread(SelectorThreadGroup stg) {
        try {
            this.stg = stg;
            selector = Selector.open();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {

        //Loop
        while (true){
            //每次死循环问多路复用器有没有东西
            try {
                //1.查看fd的状态
                System.out.println(Thread.currentThread().getName()+"   :  before select..." + selector.keys().size());
                int nums = selector.select(); //不传任何参数，就阻塞，阻塞完了一定>0???
                Thread.sleep(1000); //这绝对不是解决方案
                System.out.println(Thread.currentThread().getName()+"   :  after select..." + selector.keys().size());
                //因为当前线程在调起select方法，阻塞的过程中，如果其他线程给这个selector扔过来一些key，这个select依然会是阻塞的
                //select调起的过程中不会关注其他线程注册的key(为什么会这样，别的线程调用ctl不也是往这个红黑树里加fd吗)，这样select可能会永远阻塞住
                //发现一个问题:listen状态的socket,它的fd原本不在这个selector对应的红黑树上
                // 如果select阻塞过程中关注不到它，说明其它线程并不能直接把accept之后得到的socket的fd放入链表？？为什么不能呢??
                //可以设置timeout，或者其他线程调用wakeup，这时select的返回值就<=0

                //2.nums>0表示有事件,处理selectkeys
                if (nums > 0) {
                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> iter = keys.iterator();
                    while(iter.hasNext()) {  //线性处理过程
                        SelectionKey key = iter.next();
                        iter.remove();
                        if (key.isAcceptable()) {
                            //相对复杂的一点:接收客户端的过程(多线程下,新的客户端注册到哪个selectorz?)
                            //是不是每个服务端都用一套代码??只是有的服务端只处理接收???有的只处理读写??
                            acceptHandler(key);
                        } else if(key.isReadable()) {
                            readHandler(key);
                        } else if(key.isWritable()) {

                        }
                    }
                }

                //3.处理一些task
                if(!lbq.isEmpty()) {  //队列是个啥东西，堆里的对象，线程的栈是独立的，堆是共享的
                    //只有方法的逻辑,本地变量是线程隔离的，是在各自栈中
                    Channel c = lbq.take(); //不一定只有一个吧
                    if (c instanceof ServerSocketChannel) {
                        ServerSocketChannel server = (ServerSocketChannel) c;
                        server.register(selector, SelectionKey.OP_ACCEPT);
                    } else if (c instanceof SocketChannel) {
                        SocketChannel client = (SocketChannel) c;
                        ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
                        client.register(selector, SelectionKey.OP_READ, buffer);
                    }
                }

            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void acceptHandler(SelectionKey key) {
        System.out.println("acceptHandler....");
        ServerSocketChannel server = (ServerSocketChannel)key.channel();
        try {
            SocketChannel client = server.accept();
            client.configureBlocking(false);
            //client注册到别的selector

            stg.nextSelectorV3(client);
            //这种感觉就像,我收到一个client，但是我要交给线程组，让线程组来决定分配给谁来处理
            //目前还是混杂模式
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void readHandler(SelectionKey key) {
        ByteBuffer buffer = (ByteBuffer)key.attachment();
        SocketChannel client = (SocketChannel)key.channel();
        buffer.clear();
        while(true){
            try {
                int num = client.read(buffer);
                if (num > 0) {
                    buffer.flip();
                    while(buffer.hasRemaining()) {
                        client.write(buffer);
                    }
                    buffer.clear();
                } else if (num == 0) {
                    break; //跳出当前事件通知,没读到东西，这个事件就不用处理了
                } else if (num < 0) {
                    //客户端断开
                    System.out.println("client:" + client.getRemoteAddress() + "closed...");
                    key.cancel();
                    //close呢??
                    break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    public void setWorker(SelectorThreadGroup stgWorker) {
        this.stg = stgWorker;
    }
}
