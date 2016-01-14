package de.greenrobot.event;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

/**
 * 本类是用于将需要触发的订阅对象中的订阅方法处理由子线程中发送到主线程来进行处理
 */
final class HandlerPoster extends Handler {

    private final PendingPostQueue queue;
    private final int maxMillisInsideHandleMessage;
    private final EventBus eventBus;
    private boolean handlerActive;

    HandlerPoster(EventBus eventBus, Looper looper, int maxMillisInsideHandleMessage) {
        super(looper);
        this.eventBus = eventBus;
        this.maxMillisInsideHandleMessage = maxMillisInsideHandleMessage;
        queue = new PendingPostQueue();
    }

    //
    void enqueue(Subscription subscription, Object event) {
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
            queue.enqueue(pendingPost);
            if (!handlerActive) {
                handlerActive = true;
                if (!sendMessage(obtainMessage())) {
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
                    synchronized (this) {
                        // Check again, this time in synchronized
                        pendingPost = queue.poll();
                        if (pendingPost == null) {
                            handlerActive = false;
                            return;
                        }
                    }
                }
                //使用订阅对象来触发此次的订阅方法
                eventBus.invokeSubscriber(pendingPost);
                //获取执行触发消息需要的时间
                long timeInMethod = SystemClock.uptimeMillis() - started;
                //判断是否响应消息是否大于预设的响应最大值
                if (timeInMethod >= maxMillisInsideHandleMessage) {
                    if (!sendMessage(obtainMessage())) {
                        throw new EventBusException("Could not send handler message");
                    }
                    rescheduled = true;
                    return;
                }
            }
        } finally {
            handlerActive = rescheduled;
        }
    }
}