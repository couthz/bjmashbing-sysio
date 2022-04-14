package com.zhc.io.testreactor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

public class SelectorThreadGroup { //天生都是boss

    SelectorThread[] sts;
    ServerSocketChannel server = null;
    AtomicInteger xid = new AtomicInteger(0);

    //boss worker分开,不再是混杂模式
    SelectorThreadGroup stg = this; //不从外面传worker, worker就是自己

    //先不用bootstrap
    public void setWorker(SelectorThreadGroup stg) {
        this.stg = stg;
    }

    SelectorThreadGroup(int num) {
        sts = new SelectorThread[num];
        for (int i = 0; i < num; i++) {
            sts[i] = new SelectorThread(this);

            new Thread(sts[i]).start();
            //这时都跑了起来,开始阻塞,且会永远阻塞
        }
    }

    //bind方法,要想办法注册到某个SelectorThread中的selector中
    //目前这种混杂模式,注册到哪个SelectorThread中的selector看起来都可以
    //如果是netty,就得通过bootstrap,注册到boss group中某个eventloop(nioeventgroup)中的selector中

    public void bind(int port) {

        try {
            server = ServerSocketChannel.open();
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(port));

            //注册到哪个selector上呢? 之后接收的客户端同样有选择selector的问题,交给同一个方法么
            //往外甩,交给nextSelector
            nextSelectorV3(server);


        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    protected void nextSelector(Channel c) {
        SelectorThread st = next();
        //重点 c可能是sever,可能是client
//        ServerSocketChannel server = (ServerSocketChannel) c;
        //这里或许会有类型判断
//        try {
////            st.selector.wakeup(); //让select不阻塞,但有个问题,如果下面这行没来得及执行,又阻塞了怎么办
//            //线程之间此时一定需要通信,协调
//            //由于selector.select()阻塞了,导致下面这行代码也会阻塞住...
////            server.register(st.selector, SelectionKey.OP_ACCEPT);
//        } catch (ClosedChannelException e) {
//            e.printStackTrace();
//        }

        //所以,本来想轮询出一个selector,然后注册上去,但由于你没办法找到一个selector唤醒的空当把server注册上去
        //eventloop这时候就像一个高速运转的电扇,你想往里挂个东西但挂不上
        //其实这个时候就需要线程协调了,方案一定还有很多,多线程包下面一定有很多办法,这里用到阻塞队列
        //主线程不管了,既然主线程找不到空当,就异步化让eventloop自己在合适的时间完成注册
        st.lbq.add(c);
        //打断阻塞,对应线程在额外task中完成注册
        st.selector.wakeup();
    }

    //更像netty,分开group
    public void nextSelectorV3(Channel c) {

        try {
            if(c instanceof  ServerSocketChannel){
                SelectorThread st = next();  //listen 选择了 boss组中的一个线程后，要更新这个线程的worker组???
                st.lbq.put(c);
                st.setWorker(stg); //boss组的线程要去持有worker group的引用了,
                // 因为接收到client之后,要调用worker group的nextSelectorV3
                //这么写其实有些恶心,因为之后不断无用的重复赋值
                //事实上并不需要setworker这一步,即使boss线程是通过bossgroup的nextSelectorV3,它也会调用nextV3,最后取出的还是worker group的selector
                //如果setworker,实际上下面没必要用nextV3,用next就好了

                //所以说,要么主线程,boss线程都通过bossgroup的nextSelectorV3,
                // 要么主线程调bossgroup的nextSelectorV3,boss线程都去调workergroup的nextSelectorV3
                //socketMultiplexingThreadsV2 全解耦
                st.selector.wakeup();
            }else {
                SelectorThread st = nextV3();  //在 main线程种，取到堆里的selectorThread对象

                //1,通过队列传递数据 消息
                st.lbq.add(c);
                //2,通过打断阻塞，让对应的线程去自己在打断后完成注册selector
                st.selector.wakeup();

            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


    }

    //取消混杂，让组内第一个线程负责接收
    //有些勉强，如果像netty，应该是一个组就负责接收
    //这里给搞成了一个组内第一个线程负责接收,有点四不像了
    public void nextSelectorV2(Channel c) {

        try {
            if(c instanceof  ServerSocketChannel){
                sts[0].lbq.put(c);
                sts[0].selector.wakeup();
            }else {
                SelectorThread st = nextV2();  //在 main线程种，取到堆里的selectorThread对象
                //1,通过队列传递数据 消息
                st.lbq.add(c);
                //2,通过打断阻塞，让对应的线程去自己在打断后完成注册selector
                st.selector.wakeup();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    //无论是serversocket 还是socket 都复用这个方法
    //混杂模式,所以我可以在group中挑selector
    //如果是netty,应该会在前面加一个“注册到group中”,再从group中挑selector
    private SelectorThread next() {
        int index = xid.incrementAndGet() % sts.length;
        return sts[index];
    }

    //为了取不到sts[0]
    private SelectorThread nextV2() {
        int index = xid.incrementAndGet() % (sts.length-1);  //轮询就会很尴尬，倾斜
        return sts[index+1];
    }

    private SelectorThread nextV3() {
        int index = xid.incrementAndGet() % stg.sts.length;  //动用worker的线程分配
        return stg.sts[index];
    }
}
