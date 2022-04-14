package com.bjmashibing.system.io.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * @author: 马士兵教育
 * @create: 2020-06-30 20:02
 */
public class MyNetty {

    /*

    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <version>4.11</version>
    </dependency>


    今天主要是netty的初级使用，如果对初级知识过敏的小伙伴可以
    先学点高级的 -。-
    非常初级。。。。
     */

    /*
    目的：前边 NIO 逻辑
    恶心的版本---依托着前面的思维逻辑
    channel  bytebuffer  selector
    bytebuffer   bytebuf【pool】
     */


    @Test
    public void myBytebuf(){

        //1.ByteBufAllocator分配器得到，堆外分配
//      ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(8, 20); //capacity, maxCapacity

        //read write 会控制指针移动
        //get set

        //非池化 堆内
//        ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(8, 20);
        //pool 池化
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(8, 33);
        print(buf);

        buf.writeBytes(new byte[]{1,2,3,4});
        byte b = buf.readByte();
        print(buf);
         buf.writeBytes(new byte[]{1,2,3,4});
        print(buf);
         buf.writeBytes(new byte[]{1,2,3,4});
        print(buf);
         buf.writeBytes(new byte[]{1,2,3,4});
        print(buf);
         buf.writeBytes(new byte[]{1,2,3,4});
        print(buf);
        buf.writeBytes(new byte[]{1,2,3,4});
        print(buf);



        //再多写就会出错



    }

    public static void print(ByteBuf buf){
        //相对于原来的bytebuffer，有两个索引，
        // 原来的bytebuffer就是有一个limit，每次反转，limit会指向可读区域的末尾或者可写区域的末尾，pos指向区间开头
        //不用像之前一样在读写状态之间进行切换
        System.out.println("buf.isReadable()    :"+buf.isReadable());
        System.out.println("buf.readerIndex()   :"+buf.readerIndex());  //有一个读的索引,从哪个位置开始读
        System.out.println("buf.readableBytes() "+buf.readableBytes());  //可以读多少
        System.out.println("buf.isWritable()    :"+buf.isWritable());
        System.out.println("buf.writerIndex()   :"+buf.writerIndex());
        System.out.println("buf.writableBytes() :"+buf.writableBytes());
        System.out.println("buf.capacity()  :"+buf.capacity());
        System.out.println("buf.maxCapacity()   :"+buf.maxCapacity());
        System.out.println("buf.isDirect()  :"+buf.isDirect()); //为true 堆外分配
        //还有个池化概念
        System.out.println("--------------");
    }


    /*
    客户端
    连接别人
    1，主动发送数据
    2，别人什么时候给我发？  应该是基于event的编程  selector
     */

    @Test
    public void loopExecutor() throws Exception {
        //group  可以理解为线程池
        NioEventLoopGroup selector = new NioEventLoopGroup(1);
        //线程池???
        selector.execute(()->{
            try {

                    System.out.println("hello world001");
                    Thread.sleep(1000);

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        selector.execute(()->{
            try {

                    System.out.println("hello world002");
                    Thread.sleep(1000);

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        System.in.read();
    }


    //1.主动发送数据，但也要确定send-q有空间
    //2.别人什么时候给我发，先有事件

    //IO里面事件是第一步，读写是第二步，第一步netty给解决了，我们只要编写第二步 readheadler和writehandler，accepthandler应该不用??
    @Test
    public void clientMode() throws Exception {
        NioEventLoopGroup thread = new NioEventLoopGroup(1);

        //客户端模式：
        NioSocketChannel client = new NioSocketChannel();

        /*直接这么写会把错，会说你没有注册到event loop，本质上没有注册到selector上*/
//        client.connect(new InetSocketAddress("localhost", 9090));
//        ByteBuf buf = Unpooled.copiedBuffer("hello server".getBytes());
//        client.writeAndFlush(buf);
        //写其实也依赖send-q是不是为空，所以能不能写也有条件，所以最好也注册到多路复用器上，不然你还得自己处理异常
        //而且明显浪费系统调用（再从BIO，NIO的发展开始想），上面这段代码其实也就退化成BIO或者普通的NIO了，只有连接，没有使用多路复用器
        //所以在netty上，你的socketChannel要是没注册到selector，直接就报错了


        thread.register(client);  //有点像epoll_ctl(5,ADD,3)
        //增加了这一行之后，连接上了，但是服务端数据没有发过来就断开了。原因是reactor 异步的特征
        //client.connect(new InetSocketAddress("localhost", 9090));
//        ByteBuf buf = Unpooled.copiedBuffer("hello server".getBytes());
//        client.writeAndFlush(buf);

        //readhandler,writehandler放到哪里??
        //响应式：
        //pipeline可能会添加很多东西，读之后还要解码之类的
        ChannelPipeline p = client.pipeline();
        p.addLast(new MyInHandler());

        //注意这里的思路和服务器端又不一样了
        //reactor  异步的特征，将来会有另一个线程把连接成功的相关状态数据返回
        ChannelFuture connect = client.connect(new InetSocketAddress("192.168.202.129", 9090));
        //等待你连接成功，感觉又是像countdownlatch了,另一个线程会有某种机制唤醒当前线程
        ChannelFuture sync = connect.sync();

        ByteBuf buf = Unpooled.copiedBuffer("hello server".getBytes());
        //发数据也是异步的
        ChannelFuture send = client.writeAndFlush(buf);
        //等待发送成功
        send.sync();

        //马老师的多线程
        //等待对方关闭,对方不管就一直不会执行这一句
        //这句不写，客户端程序就结束了,连接就断了
        sync.channel().closeFuture().sync();

        System.out.println("client over....");

    }

    @Test
    public void nettyClient() throws InterruptedException {

        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Bootstrap bs = new Bootstrap();
        ChannelFuture connect = bs.group(group)
                .channel(NioSocketChannel.class)
//                .handler(new ChannelInit())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new MyInHandler());
                    }
                })
                .connect(new InetSocketAddress("192.168.202.129", 9090));

        //等待连接成功
        Channel client = connect.sync().channel();


        ByteBuf buf = Unpooled.copiedBuffer("hello server".getBytes());
        ChannelFuture send = client.writeAndFlush(buf);
        send.sync();

        client.closeFuture().sync();

    }





    @Test
    public void serverMode() throws Exception {

        NioEventLoopGroup thread = new NioEventLoopGroup(1);
        //注意这是ServerSocketChannel，只是listen状态的socket，所以后续只需要绑定accepthandler
        NioServerSocketChannel server = new NioServerSocketChannel();

        //这里应该是前一课的第三步，隐藏了组线程和io线程之间通信等等细节
        thread.register(server);
        //指不定什么时候家里来人。。响应式
        //这部分是第二步，只不过之前是直接写死在线程类中，而这里对于用户而言，只需要实现handler，而不需要实现eventloop
        //这也比较合理，对于用户来说，应该只需要去编写处理事件的函数，这个就叫响应式吗？？？响应事件
        //因为以前，内核能做的，只是到把fd状态置为可读，或者可写，程序员还是要自己编写后续的处理逻辑，编写eventloop
        //netty框架进一步延伸,eventloop的框架其实比较固定，可以进一步简化程序员需要写的东西
        //这可以再从bio一步步推演过来，去感受一步步的封装
        //而之后，有了bootstrap，连MyAcceptHandler都不用写
        ChannelPipeline p = server.pipeline();
        p.addLast(new MyAcceptHandler(thread,new ChannelInit()));  //accept接收客户端，还要注册到selector
//        p.addLast(new MyAcceptHandler(thread,new MyInHandler()));  //这一句有个shareable的问题//accept接收客户端，并且注册到selector
        ChannelFuture bind = server.bind(new InetSocketAddress("192.168.150.1", 9090));

        //都是异步的
        //第一个sync表示要等到客户端
        //第二个sync表示要等客户端结束
        bind.sync().channel().closeFuture().sync();
        System.out.println("server close....");


    }

    @Test
    public void nettyServer() throws InterruptedException {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        ServerBootstrap bs = new ServerBootstrap();
        ChannelFuture bind = bs.group(group, group) //boss和worker???
                .channel(NioServerSocketChannel.class) //服务端未来new的对象,这里不就要用到反射了
//                .childHandler(new ChannelInit())
                .childHandler(new ChannelInitializer<NioSocketChannel>() { //这里不需要自己写accepthandler
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new MyInHandler());
                    }
                })
                .bind(new InetSocketAddress("192.168.150.1", 9090));
                //追踪bind方法的源码，就可以发现我在自定义servermode中的register，addlast，由于这部分逻辑比较固定，也就写到了框架里，不用程序员自己写了
        bind.sync().channel().closeFuture().sync();

    }
}

//就和单例不能加属性一样
class  MyAcceptHandler  extends ChannelInboundHandlerAdapter{

    private final EventLoopGroup selector;
    private final ChannelHandler handler;

    public MyAcceptHandler(EventLoopGroup thread, ChannelHandler myInHandler) {
        this.selector = thread;
        this.handler = myInHandler;  //ChannelInit
    }

    //注册也成为事件了???
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        System.out.println("server registerd...");
    }

    //处理读事件
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //  listen  socket   accept    client
        //  socket           R/W
        SocketChannel client = (SocketChannel) msg;  //accept  我怎么没调用额？
        //原来是ServerSocketChannel的accept，现在不用调用了
        //其实读取时也一样，你直接拿到了butebuffer，而不是再通过socket调用read去读了
        //2，都不是线性的，都是响应式的  handler，也就是把未来可能发生的事情提前埋好
        ChannelPipeline p = client.pipeline();

        //如果有多个客户端连进来，我传入的都是同一个handler对象，感觉也没什么问题，也应该是单例的
        //但如果编写handler的人往里面增加很多属性(比如增加统计信息等等)，那单例就会出问题
        p.addLast(handler);  //1,client::pipeline[ChannelInit,]

        //1，注册
        //为什么是注册到这个eventloop上了,现在是不是一个线程即处理连接又处理读写？？
        selector.register(client);
    }
}

//为啥要有一个inithandler，可以没有，但是MyInHandler就得设计成单例
@ChannelHandler.Sharable
class ChannelInit extends ChannelInboundHandlerAdapter{

    //这是什么事件???注册事件,看做epoll_ctl
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        Channel client = ctx.channel();
        ChannelPipeline p = client.pipeline();
        p.addLast(new MyInHandler());//2,client::pipeline[ChannelInit,MyInHandler]
        ctx.pipeline().remove(this); //过河拆桥，不移出去也无所谓
        //3,client::pipeline[MyInHandler]
    }

//    @Override
//    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//        System.out.println("haha");
//        super.channelRead(ctx, msg);
//    }
}


/*
就是用户自己实现的，你能说让用户放弃属性的操作吗
@ChannelHandler.Sharable  不应该被强压给coder
 */
class MyInHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        System.out.println("client  registed...");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("client active...");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //猜一猜，也会是bytebuf
        ByteBuf buf = (ByteBuf) msg;

        //read会移动指针
//        CharSequence str = buf.readCharSequence(buf.readableBytes(), CharsetUtil.UTF_8);
        //再从缓冲区读出来
        //get需要你自己控制读的区间,这里是从0读到末尾
        CharSequence str = buf.getCharSequence(0,buf.readableBytes(), CharsetUtil.UTF_8);
        System.out.println(str);
        ctx.writeAndFlush(buf); //???写回去了?????
    }
}

