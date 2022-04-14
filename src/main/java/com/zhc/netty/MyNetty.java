package com.zhc.netty;


import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;
import org.junit.Test;

import java.net.InetSocketAddress;

//开始用netty,先不用bootstrap
public class MyNetty {

    //netty对channel, bytebuffer, selector 都进行了封装

    @Test
    public void myBytebuf() {
        ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer(8, 20);
    }

    /*
    客户端
    1. 主动发送数据
    2. 别人什么时候给我发 selector
     */
    @Test
    public void loopExecutor(){
        //先创建NioEventLoopGroup, 先理解为线程池
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        //很像线程池,有execute方法
        //1个线程的情况下只会输出hello world1
        group.execute(()->{
            for (;;) {
                System.out.println("hello world1");
            }
        });
        group.execute(()->{
            for (;;) {
                System.out.println("hello world2");
            }
        });
    }

    @Test
    public void clientModeWithoutEventLoop() {
        NioEventLoopGroup thread = new NioEventLoopGroup();

        //客户端模式
        //C10Kclient中,需要创建socketchannel open bind connect
        //netty使用自己封装的NioSocketChannel
        NioSocketChannel client = new NioSocketChannel();
        client.connect(new InetSocketAddress("127.0.0.1", 9090));
        //发东西,需要bytebuffer
        //简写的办法创建bytebuf
        ByteBuf buf = Unpooled.copiedBuffer("hello server".getBytes());
        client.writeAndFlush(buf);
        //以上的逻辑看起来都对,但最终会报错,告诉你channel没有注册到eventloop上
        //使用多路复用器的情况下,无论读写,肯定要存在"能不能读","能不能写"的问题,你必须先把socket注册到eventloop上

        //这里停一停,我可以梳理一下前面所学的所有知识
        //上面这些代码,就像是在写普通的bio代码,不管多路复用器,基本的socket编程的思路,后续需要加上响应式
        //而用了多路复用器,步骤就是,创建了socketchannel,并且要将它绑定到eventloop上,
        // 通过调用selector的select方法阻塞式地获取socket的可读或可写状态,然后再调用读写

        //但是,从使用者的角度讲,其实只关心发送或接收的地址,以及读写什么数据,也就是上面那一套代码逻辑,所以要有网络框架的封装
        //IO的步骤 建立连接-等待数据-从内核移动到用户空间, 使用其实只会参与1,3步, 第2步,epoll进行了优化,
        // 但是还要主动调用注册,select等方法去将fd和多路复用器绑定起来

        System.out.println("client over");

    }

    @Test
    public void clientMode() throws InterruptedException {
        NioEventLoopGroup thread = new NioEventLoopGroup();

        //客户端模式
        //C10Kclient中,需要创建socketchannel open bind connect
        //netty使用自己封装的NioSocketChannel
        NioSocketChannel client = new NioSocketChannel();

        //可以发现这里也仅仅是主动调用了一个register, select方法也不用显式的调用了,直接写就好了
        thread.register(client); //epoll_ctl(5, ADD, 3)
        ChannelPipeline p = client.pipeline();
        p.addLast(new MyInHandler());

        //reactor异步的特征, 连接是异步的,测试的时候,数据还没发过去,客户端就结束了
        ChannelFuture connect = client.connect(new InetSocketAddress("127.0.0.1", 9090));
        //确定连接建立成功,感觉是强行同步了
        ChannelFuture sync = connect.sync();
        //发东西,需要bytebuffer
        //简写的办法创建bytebuf
        ByteBuf buf = Unpooled.copiedBuffer("hello server".getBytes());

        //发送同理,也是异步的
        ChannelFuture send = client.writeAndFlush(buf);
        send.sync();

        //这里我想让主线程阻塞,未来可能有互动的过程
        //等别人断开,再断开
        sync.channel().closeFuture().sync();
        System.out.println("client over");

        //现在写,解决了,还要解决读
        //我肯定不是在channel直接主动调用read
        //肯定是要基于事件之上, 我需要写的是个readhandler,即读事件发生之后的处理方法,handler放哪里呢??
        //channel里有一个概念叫pipeline,即事件产生之后要做什么,可以全放进这个pipeline中
        //还是那个思路,我不关心怎么获取事件,怎么select,我只关心我要怎么处理数据
        //因为像select, 获取selectedKeys,然后迭代这些代码都很固定,而那些handler才是需要用户去写的,怎么加解密,序列化之类的
        //为什么写 不用pipeline, 也许还是因为写是你主动的, 先关心什么时候写,才关心写事件,也就是什么时候写的时候才注册
        //而并不是写事件之后,才开始写,它不是一个响应式的。 pipeline里加handler这种是响应式的
        // 而读事件是一开始就注册



    }

    /**
     * 更简单的官方client写法
     */
    @Test
    public void nettyClient() throws InterruptedException {

        //bootstrap serverboostrap进一步封装细节,不用再像上面一样还要考虑前后顺序,异步之类的问题

        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Bootstrap bs = new Bootstrap();
        ChannelFuture connect = bs.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel socketChannel) throws Exception {
                        ChannelPipeline p = socketChannel.pipeline();
                        p.addLast(new MyInHandler());
                    }
                }) //这里,以后也不用自定义ChannelInit
                .connect(new InetSocketAddress("127.0.0.1", 9090));
        //connect.sync().channel().closeFuture().sync();

        //拆一下
        //连接成功了
        Channel client = connect.sync().channel();

        //发送 等待发送成功
        ByteBuf byteBuf = Unpooled.copiedBuffer("hello netty".getBytes());
        ChannelFuture send = client.writeAndFlush(byteBuf);
        send.sync();

        client.closeFuture().sync();



    }

    /**
     * 服务端
     */
    @Test
    public void serverMode() throws InterruptedException {

        NioEventLoopGroup thread = new NioEventLoopGroup(1);

        NioServerSocketChannel server = new NioServerSocketChannel();

        thread.register(server);
        //指不定什么时候家里来人...
        ChannelPipeline p = server.pipeline();
        p.addLast(new MyAcceptHandler(thread, new ChannelInit()));
        ChannelFuture bind = server.bind(new InetSocketAddress("127.0.0.1", 9090));
        bind.sync().channel().closeFuture().sync();
        System.out.println("server close");

    }

    @Test
    public void nettyServer() throws InterruptedException {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        ServerBootstrap bs = new ServerBootstrap();
        ChannelFuture bind = bs.group(group, group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioServerSocketChannel>() {
                    @Override
                    protected void initChannel(NioServerSocketChannel socketChannel) throws Exception {
                        ChannelPipeline p = socketChannel.pipeline();
                        p.addLast(new MyInHandler());
                    }
                })  //注意这里不用调用.handler  MyAcceptHandler逻辑固定(接收客户端之后注册,添加handler),已经内部实现了
                .bind(new InetSocketAddress("127.0.0.1", 9090));

        bind.sync().channel().closeFuture().sync();
    }


}

//AcceptHandler其实之后也不用写(读写数据的handler还是要自己实现的), 是bootstrap内部私有的类
class MyAcceptHandler extends ChannelInboundHandlerAdapter {

    private final ChannelHandler handler;
    private final EventLoopGroup selector;

    public MyAcceptHandler(EventLoopGroup thread, ChannelHandler myInHandler) {
        this.selector = thread;
        this.handler = myInHandler;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        System.out.println("server registered");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //这个时候接入连接 读到的是个啥
        SocketChannel client = (SocketChannel) msg;
        //后面肯定还要做注册,然后往pipeline里面装handler
        //1. 注册
//        selector.register(client);
        //2. 响应式的 handler
        //都不是线性的,就是把未来可能发生的事情埋好
//        ChannelPipeline p = client.pipeline();
//        p.addLast(handler);
        //如果接入多个客户端，这里会报错, 同一个MyInHandler对象无法共享,相当于单例了
        //倘若设计handler的人在class里加了很多属性,那么多个client连接共享这个handler就不太对了
        //解决:1. 加注解,你不应该让用户放弃加属性的操作
        //2. 每次new handler,但是MyAcceptHandler不是用户自己实现的,要从外界传。想到了反射,但也不太好
        //3. 准备一个套,设计一个没有业务功能的handler
        //思路:接入client之后,pipeline中加入channelInit,然后在注册事件中加逻辑
        //相当于又做成响应式的了,除了反射之外,响应式也是一种往框架里填代码的好方式
        //原来是直接在管道里加MyInHandler,现在是过了一遍手,现在管道里加ChannelInit

        //先把ChannelInit放进去, 然后再注册
        ChannelPipeline p = client.pipeline();
        p.addLast(handler);

        selector.register(client);

    }
}

//这里可以加注解,它没有啥业务功能
@ChannelHandler.Sharable
class ChannelInit extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        Channel client = ctx.channel();
        ChannelPipeline p= client.pipeline();
        p.addLast(new MyInHandler());
        ctx.pipeline().remove(this); //过河拆桥，之后还存在已经没有意义了
    }
    //这么写不优美, 源码的写法是abstarct class 模板方法模式
    //未来你需要实现的是ChannelInitilaizer中的initChannel方法
}


/*
你不应该
 */
//@ChannelHandler.Sharable
class MyInHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        System.out.println("client registered");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("client active");

    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf buf = (ByteBuf) msg;//已经写入ByteBuf了,不用调用client.write(bytebuffer)??这个以后要跟源码看看

        //会移动readIndex
        //CharSequence str = buf.readCharSequence(0,buf.readableBytes(), CharsetUtil.UTF_8);

        CharSequence str = buf.getCharSequence(0,buf.readableBytes(), CharsetUtil.UTF_8);
        //直接写回,也是读bytebuf,所以如果前面移动了readIndex,这里就读不到了
        //这里有点疑惑的是,读的时候直接就进bytebuf了,写的时候还得像下面这样调用,
        //因为读事件来了就可以读了,但写是主动调的,不是响应式的,读操作看上去就是彻底异步了
        ctx.writeAndFlush(buf);
    }


}
