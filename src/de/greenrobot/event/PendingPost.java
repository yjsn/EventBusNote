package de.greenrobot.event;

import java.util.ArrayList;
import java.util.List;

/**
 * 挂载待处理的订阅对象中的订阅方法
 * 
 * 注意使用了对象池来缓存创建的挂载对象,以提高效率
 * 
 * 这个地方可以效仿Message中的消息池,使用链表来完成     这个地方使用的是list集合
 */
final class PendingPost {
	
	//类中的属性,所有对象都共享的对象池
    private final static List<PendingPost> pendingPostPool = new ArrayList<PendingPost>();

    //带处理的订阅方法的参数类型
    Object event;
    //带处理的订阅对象
    Subscription subscription;
    //
    PendingPost next;

    //根据类型和订阅对象进行构造对象
    private PendingPost(Object event, Subscription subscription) {
        this.event = event;
        this.subscription = subscription;
    }

    //获取挂载的处理方法
    static PendingPost obtainPendingPost(Subscription subscription, Object event) {
        synchronized (pendingPostPool) {
        	//获取当前对象池中对象的个数
            int size = pendingPostPool.size();
            //判断是否有可用对象
            if (size > 0) {
            	//获取第一个可用的对象
                PendingPost pendingPost = pendingPostPool.remove(size - 1);
                //设置相关的参数到挂载对象上
                pendingPost.event = event;
                pendingPost.subscription = subscription;
                pendingPost.next = null;
                return pendingPost;
            }
        }
        //没有可用对象就创建对象
        return new PendingPost(event, subscription);
    }

    //是否对象到对象池中
    static void releasePendingPost(PendingPost pendingPost) {
    	//置空对象中设置的参数
        pendingPost.event = null;
        pendingPost.subscription = null;
        pendingPost.next = null;
        //
        synchronized (pendingPostPool) {
            //判断池子中对象个数是否超过预设值
            if (pendingPostPool.size() < 10000) {
            	//没有超过就将对象放置到对象池中
                pendingPostPool.add(pendingPost);
            }
        }
    }

}