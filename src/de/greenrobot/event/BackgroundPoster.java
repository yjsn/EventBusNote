package de.greenrobot.event;

import android.util.Log;

final class BackgroundPoster implements Runnable {

    private final PendingPostQueue queue;
    private final EventBus eventBus;
    //用于标示当前线程是否处于触发任务阶段
    private volatile boolean executorRunning;

    BackgroundPoster(EventBus eventBus) {
        this.eventBus = eventBus;
        //创建队列对象
        queue = new PendingPostQueue();
    }

    //用于添加带触发的订阅对象中的订阅方法
    public void enqueue(Subscription subscription, Object event) {
    	//根据提供的订阅对象和订阅方法创建挂载订阅对象
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
        	//将挂载对象放置到队列中
            queue.enqueue(pendingPost);
            //判断当前子线程是否处于发送待触发订阅方法的处理
            if (!executorRunning) {
            	//设置标记为为true
                executorRunning = true;
                //获取线程池执行此线程任务
                eventBus.getExecutorService().execute(this);
            }
        }
    }

    @Override
    public void run() {
        try {
            try {
                while (true) {
                    PendingPost pendingPost = queue.poll(1000);
                    if (pendingPost == null) {
                        synchronized (this) {
                            pendingPost = queue.poll();
                            if (pendingPost == null) {
                                executorRunning = false;
                                return;
                            }
                        }
                    }
                    //在子线程中触发订阅对象的订阅方法
                    eventBus.invokeSubscriber(pendingPost);
                }
            } catch (InterruptedException e) {
                Log.w("Event", Thread.currentThread().getName() + " was interruppted", e);
            }
        } finally {
        	//执行完成后,设置运行标记位为false
            executorRunning = false;
        }
    }

}
