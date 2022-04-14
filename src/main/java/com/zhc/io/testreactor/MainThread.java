package com.zhc.io.testreactor;

public class MainThread {

    //主线程这里不做IO和业务的事情，只是启动各个组件
    public static void main(String[] args) {
        //1. 创建IO Thread(一个或者多个)
        //给group来管理多个thread,包括thread的创建,参数的初始化等等,
        // 确实,这些工作不应该交给使用者,使用者唯一关心的,就是监听哪个端口而已

        //面向对象,把功能甩出去, 自顶向下编程
        SelectorThreadGroup boss = new SelectorThreadGroup(3); //混杂模式,一个selector既能注册server也能注册客户端
        SelectorThreadGroup worker = new SelectorThreadGroup(3); //混杂模式,一个selector既能注册server也能注册客户端

        //2. 我应该把监听的server注册到某一个selector上
        //绑定的内部逻辑也甩给group
        boss.setWorker(worker);
        //bossgroup得持有workergroup的引用,

        boss.bind(9999);
    }
}
