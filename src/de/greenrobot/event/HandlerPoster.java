package de.greenrobot.event;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

/**
 * 本类是用于将需要触发的订阅对象中的订阅方法处理由子线程中发送到主线程来进行处理
 */
final class HandlerPoster extends Handler {

	//用于记录挂载待处理订阅方法的队列
    private final PendingPostQueue queue;
    private final int maxMillisInsideHandleMessage;
    //用于关联对应的EventBus对象
    private final EventBus eventBus;
    //用于标示当前的handler是否处于处理任务中
    private boolean handlerActive;

    HandlerPoster(EventBus eventBus, Looper looper, int maxMillisInsideHandleMessage) {
        super(looper);
        this.eventBus = eventBus;
        this.maxMillisInsideHandleMessage = maxMillisInsideHandleMessage;
        queue = new PendingPostQueue();
    }

    //用于向主线程中对应的触发订阅方法集合中添加一个新的需要触发的订阅方法
    void enqueue(Subscription subscription, Object event) {
    	//根据订阅对象,订阅类型来创建一个挂载订阅对象
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
        	//将挂载的订阅方法添加到队列中
            queue.enqueue(pendingPost);
            //判断当前的handler是否处于正在进行触发消息的处理
            if (!handlerActive) {
            	//没有进行,设置进行标识位为true
                handlerActive = true;
                //发送一个消息,将handler开启,在主线程中进行触发订阅方法的处理
                if (!sendMessage(obtainMessage())) {
                	//发送消息失败,抛出异常
                    throw new EventBusException("Could not send handler message");
                }
            }
        }
    }

    //在主线程中进行触发消息的处理
    @Override
    public void handleMessage(Message msg) {
        boolean rescheduled = false;
        try {
        	//获取在主线程中处理消息的时间
            long started = SystemClock.uptimeMillis();
            while (true) {
            	//在队列中获取一条需要触发的订阅方法
                PendingPost pendingPost = queue.poll();
                //判断是否能够获取需要触发的订阅消息
                if (pendingPost == null) {
                	//再次进行异步处理来观察是否能够获取一个需要触发的订阅方法
                    synchronized (this) {
                        //获取待触发的消息
                        pendingPost = queue.poll();
                        //判断是否能够获取到
                        if (pendingPost == null) {
                        	//设置handler没有进入活跃状态
                            handlerActive = false;
                            //结束处理
                            return;
                        }
                    }
                }
                //使用订阅对象来触发此次的订阅方法-------------------------->此处已经在主线程了,即在主线程中触发了订阅方法
                eventBus.invokeSubscriber(pendingPost);
                //获取执行触发消息需要的时间
                long timeInMethod = SystemClock.uptimeMillis() - started;
                //判断是否响应消息是否大于预设的响应最大值----------------------------->这个地方的目的？？？？？？？？？？？？？？？？？？有待研究
                if (timeInMethod >= maxMillisInsideHandleMessage) {
                    if (!sendMessage(obtainMessage())) {
                        throw new EventBusException("Could not send handler message");
                    }
                    rescheduled = true;
                    return;
                }
            }
        } finally {
        	//没有需要处理的订阅方法,设置handler活跃标记为为false
            handlerActive = rescheduled;
        }
    }
}