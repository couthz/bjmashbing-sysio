package com.zhc.rpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.junit.Test;

import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/*
    1. 先假设一个需求, 写一个RPC
    2. 来回通信, 连接数量, 拆包
    3. 动态代理,序列化,协议封装
    4. 连接池 （去看看ry的mysql)
    5. 就像调用本地方法一样去调用远程的方法, 面向java中就是所谓的 面向interface开发（去看看突击课的dubbo)
 */
public class MyRPCTest {


    @Test
    public void startServer() {

        //在服务端的主线程中注册, 在io线程中准备处理
        MyCar car = new MyCar();
        MyFly fly = new MyFly();
        Dispatcher dis = new Dispatcher();
        dis.register(Car.class.getName(), car);
        dis.register(Fly.class.getName(), fly);

        NioEventLoopGroup boss = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = new NioEventLoopGroup(50);

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        ChannelFuture bind = serverBootstrap.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel client) throws Exception { //在过桥的handler中直接就能拿到channel,那服务端有没有连接池呢??
                        ChannelPipeline pipeline = client.pipeline();
                        pipeline.addLast(new ServerDecode());  //服务端很简单,关键点无非就在这里
                        pipeline.addLast(new ServerRequestHandler(dis));  //服务端很简单,关键点无非就在这里
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 9090));
        try {
            bind.sync().channel().closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }




    //模拟客户端
    @Test
    public void get(){

        new Thread(() -> {
            startServer();
        }).start();

        //制造一个bug,当多个客户端接入的时候,服务端切割数据会发生错位,反序列化失败
        //这就很奇怪,倘若数据是按照顺序发送过去的,那么切割不应该发生错位
        //根本原因: 服务端handler处理msg,但这个msg并不一定正好是一个包
        //有可能tcp粘包（一个包里有应用层多个包）,
        // 也有可能第一个包到的时候中断,但读的时候网卡里有好几个包了,
        // 也有可能网卡本身也是攒了好几个包才发的中断

        //拆包,很可能应用层发送了两个包，但实际上只发出去一个半包
        //应用层数据大于socket缓冲区大小
        //tcp报文大于mss,分段
        //ip层 大于mtu 分片

        //梳理一下 客户端开了多个线程, 多个线程的数据可能复用连接(channel),
        // channel是已经注册到eventloop上的,一个eventloop循环时一个线程运行的,多个channel可以注册到一个eventloop上
        // 多个线程的数据通过一个连接发出去, 不同线程的数据顺序不能保证,但是发送的内容完整(除非太大，大过socket缓冲区??)
        // 那如果这么说的话,一旦拆包,但只要保证两个包连续到达,也可以接上,这应该是肯定的,往缓冲区放的时候应该还是整个包往里放的
        //tcp是流式协议,而不是基于包的  数据是以字节流的形式传递给接收者的，没有固有的”报文”或”报文边界”的概念。
        // 从这方面来说，读取TCP数据就像从串行端口读取数据一样–无法预先得知在一次指定的读调用中会返回多少字节
        //换句话说,从tcp协议的角度看,上层传下来的数据就是一串字节数组,没有任何边界可言,tcp并不清楚业务数据的含义
        //。实际上，send通常只是将数据复制到主机A的TCP/IP栈中，就返回了。由TCP来决定（如果有的话）需要立即发送多少数据。
        // 做这种决定的过程很复杂，取决于很多因素，
        // 比如发送窗口（当时主机B能够接收的数据量），拥塞窗口（对网络拥塞的估计），路径上的最大传输单元（沿着主机A和B之间的网络路径一次可以传输的最大数据量），
        // 以及连接的输出队列中有多少数据

        int size = 20;
        Thread[] threads = new Thread[size];
        for (int i = 0; i < size; i++) {
            threads[i] = new Thread(() -> {
                Car car = proxyGet(Car.class); //动态代理实现
                String res = car.ooxx("hello");
                System.out.println("客户端收到:" + res);
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        try {
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }


        //Fly fly = proxyGet(Fly.class); //动态代理实现


    }

    public static <T>T proxyGet(Class<T> interfaceInfo) {

        //实现各个版本的动态代理
        //使用jdk动态代理, 有几个参数需要获得
        ClassLoader loader = interfaceInfo.getClassLoader();
        Class<?>[] methodInfo = {interfaceInfo};

        return (T)Proxy.newProxyInstance(loader, methodInfo, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                //如何设计我们的consumer对于provider的调用过程

                //1. 调用服务,方法,参数 ==> 封装成message [cotent](按理还有注册中心)
                String name = interfaceInfo.getName();
                String methodName = method.getName();
                Class<?>[] parameterTypes = method.getParameterTypes();
                MyContent content = new MyContent();
                content.setArgs(args);
                content.setMethodName(methodName);
                content.setName(name);
                content.setParameterTypes(parameterTypes);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ObjectOutputStream oout = new ObjectOutputStream(out);
                oout.writeObject(content); //最终写到ByteArrayOutputStream
                byte[] msgBody = out.toByteArray();

                //2. requestID+message  本地要缓存,不然你不确定返回来的数据该给谁了,你只能知道是给哪个端口的
                //协议: [header<>] [msgbody]
                MyHeader header = createHeader(msgBody);

                out.reset();
                oout = new ObjectOutputStream(out);
                oout.writeObject(header);
                byte[] msgHeader = out.toByteArray();
                //3. 连接池:: 取得连接
                ClientFactory factory = ClientFactory.getFactory();
                NioSocketChannel clientChannel = factory.getClient(new InetSocketAddress("127.0.0.1", 9090));
                //4. 发送 --> 走IO,有返回值怎么办？？future,并不是future只能表示发送成功,并不是接收到返回值
                //为什么一定要有ByteBuf???
                ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.directBuffer(msgHeader.length + msgBody.length);


                long id = header.getRequestID();
                CompletableFuture<Object> res = new CompletableFuture<>();
                ResponseHandler.addCallBack(id, res);

                byteBuf.writeBytes(msgHeader);
                byteBuf.writeBytes(msgBody);
                ChannelFuture channelFuture = clientChannel.writeAndFlush(byteBuf);
                channelFuture.sync();
                //到这里应该开始等返回值了

                //5. ? 如果有返回值, 未来回来了, 怎么将代码执行到这里
                //怎么在这里得到返回值呢???当前线程不仅要等待io线程处理完响应,还要把返回值带回来
                //原来是传递runnable给io线程,现在用CompletableFuture
                //这里就要回顾一下future,以及之前看的ry多线程的设计模式了\


                return res.get(); //阻塞的,等待别的线程调用complete,直到有结果,所以countDownLatch都不需要了
            }
        });
    }

    public static MyHeader createHeader(byte[] msg) {
        MyHeader header = new MyHeader();
        int size = msg.length;
        int f = 0x14141414; //0x14 0001 0100

        //getLeastSignificantBits()方法用于获取这128位中的最低有效64位
        long requestID = Math.abs(UUID.randomUUID().getLeastSignificantBits());
        header.setFlag(f);
        header.setDataLen(size);
        header.setRequestID(requestID);
        return header;

    }
}

class Dispatcher {
    //回忆一一下spring容器

    //这种岂不是单例了，该不该是单例呢???
    public static ConcurrentHashMap<String, Object> invokeMap = new ConcurrentHashMap<>();

    public void register(String k, Object obj) {
        invokeMap.put(k, obj);
    }

    public Object get(String k) {
        return invokeMap.get(k);
    }

}

class MyCar implements Car {

    @Override
    public String ooxx(String msg) {
        System.out.println("server, get client arg:" + msg);
        return "server res " + msg;
    }
}

class MyFly implements Fly {

    @Override
    public void ooxx(String msg) {
        System.out.println("server, get client arg:" + msg);
    }
}


//解决拆包粘包问题的handler,在服务端拿数据之前先处理一下,解码器
class ServerDecode extends ByteToMessageDecoder {

    //如同前面讲的过桥类 ,父类里一定有channelread(decode在这个方法体里) -> bytebuf
    //换句话说,模版方法,使用者只应该关心我要把数据变成什么对象，剩下关于截断,留存的事应该让netty帮忙干了
    //  上面这个思想很重要,其实就是裸写epoll如何一步步变成netty框架,把一些模版化的代码由框架实现
    //  而用户要实现具体的业务,框架不可能提前知道你要把byte转变为什么对象,所以要有所谓的序列化器和反序列化器
    //什么叫解码,你应该把一整条二进制数据变成一个个对象,放进out中
    //剩下的收纳起来
    //其实去看源码,cumulator,就是在拼接老的和新分配的
    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf buf, List<Object> list) throws Exception {

        //粘包的问题还是你自己解决的,但分包帮你解决了
        //这个方法其实能更加模版化,就往里传一个Packmsg的类型,直接帮你反序列化
        //但也许这样灵活度就下降了,虽然易用性提升了
        //这也可以让人想到BIO NIO,处理byte最灵活,处理块数据更容易但更不灵活

        // 也许netty还有其他解码器可以解决上述问题
        //需要注意,对象转二进制之后的长度,会伴随着全限定类型变化而变化
        while (buf.readableBytes() >= 90) {
            byte[] bytes = new byte[90];
            buf.getBytes(buf.readerIndex(), bytes);
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            ObjectInputStream oin = new ObjectInputStream(in);
            MyHeader header = (MyHeader)oin.readObject();



            if (buf.readableBytes() - 90 >= header.getDataLen()){
                buf.readBytes(90);
                byte[] data = new byte[(int)header.getDataLen()];
                buf.readBytes(data);
                ByteArrayInputStream din = new ByteArrayInputStream(data);
                ObjectInputStream doin = new ObjectInputStream(din);

                //DECODE在服务端和客户端都使用
                //通信的协议
                if (header.getFlag() == 0x14141414) {
                    MyContent content = (MyContent) doin.readObject();
                    list.add(new Packmsg(header, content));
                } else if(header.getFlag() == 0x14141424) {
                    MyContent content = (MyContent) doin.readObject();
                    list.add(new Packmsg(header, content));
                }
            }
            else {
                //可以自己解决,不过netty也解决了
                System.out.println("拆包");
                break;
            }
        }
    }
}

//class ServerResponses extends ChannelInboundHandlerAdapter {
//
//    ByteBuf lastBuf;
//
//    @Override
//    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//        ByteBuf buf = (ByteBuf) msg;
//        System.out.println("本次可读字节:" + buf.readableBytes());
//
//        ByteBuf copy = buf.copy();
//
//        //未能解决拆包问题,还是有bug
//        if (lastBuf!= null && lastBuf.readableBytes() > 0) {
//            lastBuf.writeBytes(buf);
//            buf = lastBuf;
//        }
//
//        //判断头部是否完整
//        //if改为while
//        while (buf.readableBytes() >= 90) {
//            byte[] bytes = new byte[90];
//            buf.getBytes(buf.readerIndex(), bytes);
//            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
//            ObjectInputStream oin = new ObjectInputStream(in);
//            MyHeader header = (MyHeader)oin.readObject();
//            System.out.println("头部解析成功" + header.getRequestID());
//
//            if (buf.readableBytes() >= header.getDataLen()){
//                //处理指针
//                buf.readBytes(90); //移动指针到body开始的位置
//
//                byte[] data = new byte[(int)header.getDataLen()];
//                buf.readBytes(data);
//                ByteArrayInputStream din = new ByteArrayInputStream(data);
//                ObjectInputStream doin = new ObjectInputStream(din); //不太懂,头部都解析成功了,这里怎么会失败
//
//                MyContent content = (MyContent) doin.readObject();
//                //TODO 执行服务端方法 该用到反射了吧
//                System.out.println("请求体解析成功:" + header.getRequestID());
//
////                if (buf.readableBytes() > 0) {
////                    System.out.println("粘包了" + header.getRequestID());
////
////                }
//
//            }else {
//                //首先搞清楚,为什么会拆包
//
//                //面对拆包问题
//
//                //尝试读头部时getBytes,不移动指针,确定请求体完整时再移动指针,失败,进入死循环,不断提示拆包,
//                // 说明后续的数据并没有自动接入到bytebuf
//                System.out.println("拆包了" + header.getRequestID());
//                //继续尝试, 成功
//                break;
//            }
//        }
//        //写在循环外,有可能在header处拆的包,也有可能在contentn拆的包
//        if (buf.readableBytes() > 0) {
//            System.out.println(buf.readerIndex());
//            //这里写的不好,maxsize不应该写死
//            //不写maxsize,默认最大容量限制是 Integer.MAX_VALUE
//            lastBuf = ByteBufAllocator.DEFAULT.heapBuffer(buf.readableBytes());
//            buf.readBytes(lastBuf); //这里lastBuf只能分配buf.readableBytes()的字节数,否则会导致buf越界
//            // (readerIndex(390) + length(300) exceeds writerIndex(512))
//
//        }
//        else {
//            if (lastBuf != null) {
//                lastBuf.clear();
//
//            }
//        }
//
//        Channel channel = ctx.channel();
//        //这里为了测试,先把数据原封不动发回去
//        channel.writeAndFlush(copy);
//
//    }
//}

class ServerRequestHandler extends ChannelInboundHandlerAdapter {
    //ByteBuf lastbuf = null;

    Dispatcher dis;

    public ServerRequestHandler(Dispatcher dis) {
        this.dis = dis;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //ByteBuf buf = (ByteBuf) msg;
        //加了解码器之后,就不是ByteBuf类型了
        //会对解码器结果list中的所有数据分别调一次
        Packmsg requestPkg = (Packmsg) msg;

        System.out.println(requestPkg.myheader.getRequestID());

        //这时候,往回写的流程要注意哪些点？？？
        //编码器,变成字节数组
        //另外往回写,客户端也有解码的问题
        //关注rpc通信协议 来的时候flag 0x14141414 不同flag对应不同种类的包,也就对应不同的处理包方式


        //为什要有序列化反序列化呢??
        //1. 网络上传输的就是二进制
        //2. 跨平台的需求,不同语言要遵从同一个序列化协议,这就需要各自平台去匹配序列化协议,撰写自己的序列化器和反序列化器
        //3. netty这里的序列化和反序列化,感觉就是业务数据和物业无属性的二进制流之间的转换,需要处理粘包拆包的问题

        //有新的header+content

        //当前的线程是eventloop的线程,也就是netty启动的IO线程,不同于服务端的业务线程
        String ioThreadName = Thread.currentThread().getName();
        //1. 直接在当前方法处理IO和业务和返回,没拆开IO线程和业务线程
        //2. 使用netty自己的eventloop来处理业务及返回（runAlltasks阶段）来处理业务及返回
        //3. 自己创建线程池

        // //eventloop又是一个executor 这里其实是把任务扔进了线程池(executor)
        // group本身可以理解为线程池
        // （实际上只是eventloop中的runAlltasks阶段执行了一次任务,而且只执行一次,不会循环执行)
//        ctx.executor().execute(new Runnable() {
//            @Override
//            public void run() {
//                String execThreadName = Thread.currentThread().getName();
//                MyContent content = new MyContent();
//                //到目前为止,会发现iothread和execthread相同,也就是没交给其他线程,虽然看上去是给线程池了
//                //也就是说方法2和方法1唯一的区别就是从直接处理任务变成了把任务扔进队列,在runalltasks阶段处理任务
//                //这里还要注意,总的io线程数,就是worker的数量,无论客户端进来多少连接
//                System.out.println("io thread:" + ioThreadName + " exec thread:" + execThreadName);
//                content.setRes("back");
//                byte[] contentByte = SerDerUtil.ser(content);
//
//                MyHeader header = new MyHeader();
//                header.setRequestID(requestPkg.getMyheader().getRequestID());
//                header.setFlag(0x14141424);
//                header.setDataLen(contentByte.length);
//                byte[] headerByte = SerDerUtil.ser(header);
//
//                ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.directBuffer(headerByte.length + contentByte.length);
//                byteBuf.writeBytes(headerByte);
//                byteBuf.writeBytes(contentByte);
//                //ctx.writeAndFlush(byteBuf);
//            }
//        });

        //方法2的正确实现:
        //意义,任务打散,不增加资源消耗和线程数量的情况下,往比较空闲的线程放任务,均衡使用cpu
        //或者说,io thread有10个,我只进来5个连接,其实就有几个io thread白白的开了,就可以用上它们
        //一定是根据业务场景,灵活运用
        ctx.executor().parent().next().execute(new Runnable() {
            @Override
            public void run() {

                String serviceName = requestPkg.content.getName();
                String methodName = requestPkg.content.getMethodName();
                Object c = dis.get(serviceName);

                //反射,这种方法最不推荐,效率低,但代码量少
                Class<?> clazz = c.getClass();
                Object res = null;
                try {
                    //应对重载
                    Method m = clazz.getMethod(methodName, requestPkg.content.parameterTypes);
                    res = m.invoke(c, requestPkg.content.getArgs());
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }

                MyContent content = new MyContent();
                //到目前为止,会发现iothread和execthread相同,也就是没交给其他线程,虽然看上去是给线程池了
                //也就是说方法2和方法1唯一的区别就是从直接处理任务变成了把任务扔进队列,在runalltasks阶段处理任务
                //这里还要注意,总的io线程数,就是worker的数量,无论客户端进来多少连接
                content.setRes((String)res);
                //203课 到此为止就是一个最俗的dispatcher
                byte[] contentByte = SerDerUtil.ser(content);

                MyHeader header = new MyHeader();
                header.setRequestID(requestPkg.getMyheader().getRequestID());
                header.setFlag(0x14141424);
                header.setDataLen(contentByte.length);
                byte[] headerByte = SerDerUtil.ser(header);

                ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.directBuffer(headerByte.length + contentByte.length);
                byteBuf.writeBytes(headerByte);
                byteBuf.writeBytes(contentByte);
                ctx.writeAndFlush(byteBuf);
            }
        });
    }
}

//池子是初始状态, 池子随要随建立连接
class ClientFactory {
    int poolSize = 10;

    Random rand = new Random();
    NioEventLoopGroup clientWorker;

    private static final ClientFactory factory;
    static {
        factory = new ClientFactory();
    }
    public static ClientFactory getFactory() {
        return factory;
    }
    //一个consumer可以连接很多的provider, 每一个provider都有自己的pool k v
    //为什么呢？？到达不同provider的连接不能用一个pool是嘛？？
    ConcurrentHashMap<InetSocketAddress, ClientPool> outboxs = new ConcurrentHashMap<>();

    public synchronized NioSocketChannel getClient(InetSocketAddress address) {

        ClientPool clientPool = outboxs.get(address);
        if (clientPool == null) {
            outboxs.putIfAbsent(address, new ClientPool(poolSize));
            clientPool = outboxs.get(address);
        }
        int i = rand.nextInt(poolSize);
        if (clientPool.clients[i] != null && clientPool.clients[i].isActive()) {
            return clientPool.clients[i];
        }

        synchronized (clientPool.lock[i]) {
            return clientPool.clients[i] = create(address);
        }
    }

    private NioSocketChannel create(InetSocketAddress address) {
        //基于netty的客户端创建方式
        //这里其实有些奇怪, NioSocketChannel一定是要注册到EventLoopGroup上的,
        // 但这个NioEventLoopGroup不应该是临时new出来的
        clientWorker = new NioEventLoopGroup(1);
        Bootstrap bs = new Bootstrap();
        ChannelFuture connect = bs.group(clientWorker)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<NioSocketChannel>() {

                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new ServerDecode());
                        p.addLast(new ClientResponses()); //解决给谁的,利用requestID
                    }
                }).connect(address);
        try {
            NioSocketChannel client = (NioSocketChannel) connect.sync().channel();
            return client;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }
}
//关于countdownlanch那边的逻辑, 比较全局,这里定义一些静态的方法
class ResponseHandler {
    //Runnable这里不一定放到thread里,在这里的作用相当于将函数作为一个对象保存到map中了
    //这里也有响应式那味了, 注册 回调
    //框架里会起一个线程,事件发生时调用回调
    //使用者只需要提前写好回调
    static ConcurrentHashMap<Long, CompletableFuture> mapping = new ConcurrentHashMap<>();

    public static void addCallBack(long requestID, CompletableFuture cb) {
        mapping.putIfAbsent(requestID, cb);
    }
    public static void runCallBack(Packmsg pkg) {
        CompletableFuture res = mapping.get(pkg.getMyheader().getRequestID());
        res.complete(pkg.getContent().getRes());
        removeCB(pkg.getMyheader().getRequestID());
    }

    private static void removeCB(long requestID) {
        mapping.remove(requestID);
    }

}

class ClientResponses extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Packmsg responsepkg = (Packmsg) msg;

        long requestID = responsepkg.getMyheader().getRequestID();

        //之前我们没考虑返回,现在得考虑
        String res = responsepkg.getContent().getRes();
        //但这里有个问题,我怎么把返回值带回到invoke方法中呢,它现在只出现在了handler中

        ResponseHandler.runCallBack(responsepkg);

    }
}

class ClientPool {
    NioSocketChannel[] clients;
    Object[] lock;

    ClientPool(int size) {
        clients = new NioSocketChannel[size];  //init 连接是空的
        lock = new Object[size]; //锁必须初始化
        for (int i = 0; i < size; i++) {
            lock[i] = new Object();
        }
    }
}


class MyHeader implements Serializable {
    //通信上的协议
    /*
    1.ooxx值 标识这是个什么协议
    2.UUID:requestID
    3.DATA_LEN
     */
    int flag; //32bit可以设置很多信息
    long requestID;
    long dataLen;

    public int getFlag() {
        return flag;
    }

    public void setFlag(int flag) {
        this.flag = flag;
    }

    public long getRequestID() {
        return requestID;
    }

    public void setRequestID(long requestID) {
        this.requestID = requestID;
    }

    public long getDataLen() {
        return dataLen;
    }

    public void setDataLen(long dataLen) {
        this.dataLen = dataLen;
    }
}

class MyContent implements Serializable{
    String name;
    String methodName;
    Class<?>[] parameterTypes;
    Object[] args;
    String res;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public Class<?>[] getParameterTypes() {
        return parameterTypes;
    }

    public void setParameterTypes(Class<?>[] parameterTypes) {
        this.parameterTypes = parameterTypes;
    }

    public Object[] getArgs() {
        return args;
    }

    public void setArgs(Object[] args) {
        this.args = args;
    }

    public String getRes() {
        return res;
    }

    public void setRes(String res) {
        this.res = res;
    }
}

class Packmsg implements Serializable {
    MyHeader myheader;
    MyContent content;

    public Packmsg(MyHeader myheader, MyContent content) {
        this.myheader = myheader;
        this.content = content;
    }

    public MyHeader getMyheader() {
        return myheader;
    }

    public void setMyheader(MyHeader myheader) {
        this.myheader = myheader;
    }

    public MyContent getContent() {
        return content;
    }

    public void setContent(MyContent content) {
        this.content = content;
    }
}

interface Car{
    public String ooxx(String msg);
}

interface Fly{
    public void ooxx(String msg);
}