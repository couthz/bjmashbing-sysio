package com.bjmashibing.system.io.testreactor;

/**
 * @author: 马士兵教育
 * @create: 2020-06-21 20:13
 */

public class MainThread {

    public static void main(String[] args) {
        //这里不做关于IO 和  业务的事情

        //1,创建 IO Thread  （一个或者多个）
        SelectorThreadGroup boss = new SelectorThreadGroup(1);
        //boss有自己的线程组

        SelectorThreadGroup worker = new SelectorThreadGroup(2);
        //worker有自己的线程组

        //混杂模式，其中一个selector即可能负责accept又负责read
//        SelectorThreadGroup stg = new SelectorThreadGroup(3);

        //2，我应该把 监听（9999）的  server  注册到某一个 selector上

        boss.setWorker(worker);
        /**
         * boss需要持有worker的引用
         * 因为未来 listen 一旦accept得到client后得去worker中 next出一个线程分配
         */

        //可以监听很多端口,会分成3组
        boss.bind(9999);
        boss.bind(8888);
        boss.bind(6666);
        boss.bind(7777);

    }
}
