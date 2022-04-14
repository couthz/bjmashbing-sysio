package com.bjmashibing.system.io.testreactor1;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

public class SelectorThreadGroup { //大家天生都是boss

    SelectorThread[] sts;
    ServerSocketChannel server;

    AtomicInteger xid = new AtomicInteger(0);

    SelectorThreadGroup stg = this;
    public void setWorker(SelectorThreadGroup stg) {
        this.stg = stg;
    }

    SelectorThreadGroup(int num) {
        //num 线程数
        sts = new SelectorThread[num];
        for (int i = 0; i < num; i++) {
            sts[i] = new SelectorThread(this); //在这里传stg没有什么用,因为是先调用的这个构造，后调用的上面setWorker
            //也就是说

            new Thread(sts[i]).start(); //启动之后,3个selector都会阻塞在select方法上,因为还没有监听任何fd上的事件(红黑树都是空的)

        }
    }


    public void bind(int port) {
        try {
            server = ServerSocketChannel.open();
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(port));

            //注册到哪个selector上呢??accepthandler中也有相同的问题
            //再甩锅
            nextSelectorV3(server);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void nextSelectorV3(Channel c) {
        try {
            if (c instanceof ServerSocketChannel) {
                SelectorThread st = next(); //listen 选择了 boss组中的一个线程后，要更新这个线程的worker组

                st.lbq.put(c);
                st.setWorker(stg);
                //boss组的线程最初持有的还是boss组的引用,而它们要将客户端channel传递给worker组,调用worker组的nextSelectorV3
                st.selector.wakeup();
            } else {
                SelectorThread st = nextV3(); //选择worker组的一个线程
                //c可能是server,可能是client

                //1.通过队列传递数据 消息 传递准备注册的channel
                st.lbq.add(c);
                //2.通过打断阻塞,让对应的线程去自己在打断后完成注册（让单线程自己处理任务,就可以规避掉并发问题）
                //对应线程实际上就会去进行eventloop的第三步,处理一些task，因为第二步会是0，跳过
                st.selector.wakeup();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void nextSelectorV2(Channel c) {
        try {
            if (c instanceof ServerSocketChannel) {
                sts[0].lbq.put(c);
                sts[0].selector.wakeup();
            } else {
                SelectorThread st = nextV2();
                //c可能是server,可能是client

                //1.通过队列传递数据 消息 传递准备注册的channel
                st.lbq.add(c);
                //2.通过打断阻塞,让对应的线程去自己在打断后完成注册（让单线程自己处理任务,就可以规避掉并发问题）
                //对应线程实际上就会去进行eventloop的第三步,处理一些task，因为第二步会是0，跳过
                st.selector.wakeup();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void nextSelector(Channel c) {
        SelectorThread st = next();
        //c可能是server,可能是client

        //1.通过队列传递数据 消息 传递准备注册的channel
        st.lbq.add(c);
        //2.通过打断阻塞,让对应的线程去自己在打断后完成注册（让单线程自己处理任务,就可以规避掉并发问题）
        //对应线程实际上就会去进行eventloop的第三步,处理一些task，因为第二步会是0，跳过
        st.selector.wakeup();

        //错误版本的代码:
        /*ServerSocketChannel s = (ServerSocketChannel) c;
        //为什么select()处阻塞了,这里也阻塞??阻塞针对的不是线程吗?是因为并发安全吗

        try {

            s.register(st.selector, SelectionKey.OP_ACCEPT);
            st.selector.wakeup(); //功能让selector立刻返回,不阻塞 //写前面写后面都不对
        } catch (ClosedChannelException e) {
            e.printStackTrace();
        }*/


    }

    //无论是那种socketchannel 都是channel
    //这里也可以体现出本类的"管理"功能
    private SelectorThread next() {
        //怎么选selector
        //混杂模式,不区分分工
        //轮询
        int index = xid.incrementAndGet() % sts.length;
        return sts[index];

    }

    private SelectorThread nextV2() {
        //怎么选selector
        //混杂模式,不区分分工
        //轮询
        int index = xid.incrementAndGet() % (sts.length-1);
        return sts[index+1];

    }

    private SelectorThread nextV3() {
        //怎么选selector
        //混杂模式,不区分分工
        //轮询
        int index = xid.incrementAndGet() % stg.sts.length; //动用worker的线程分配逻辑
        return stg.sts[index];

    }
}
