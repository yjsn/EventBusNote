package de.greenrobot.event;

/**
 * 本类是处理挂载订阅方法的队列
 * 
 * 主要存储需要触发的订阅方法
 */
final class PendingPostQueue {
	
	//用于指向队列的头部
    private PendingPost head;
    //用于指向队列的尾部
    private PendingPost tail;

    //向队列中添加一个需要处理的挂载订阅方法
    synchronized void enqueue(PendingPost pendingPost) {
    	//首先判断传入的对象是否异常
        if (pendingPost == null) {
            throw new NullPointerException("null cannot be enqueued");
        }
        //判断尾部是否存在
        if (tail != null) {
        	//将新添加的挂载放置到尾部
            tail.next = pendingPost;
            tail = pendingPost;
        } else if (head == null) {
        	//判断头部是否存在,将头尾同时指向此对象
            head = tail = pendingPost;
        } else {
        	//其他情况的异常抛出
            throw new IllegalStateException("Head present, but no tail");
        }
        //进行通知处理
        notifyAll();
    }

    //在队列中获取一个需要触发的订阅挂载对象
    synchronized PendingPost poll() {
    	//获取头部对象
        PendingPost pendingPost = head;
        //判断当前头部是否存在
        if (head != null) {
        	//头部后移处理
            head = head.next;
            //判断后移后的对象是否存在
            if (head == null) {
            	//尾部置空处理
                tail = null;
            }
        }
        //返回需要触发的挂载对象
        return pendingPost;
    }

    //等待指定的时间后获取队列中的订阅挂载对象
    synchronized PendingPost poll(int maxMillisToWait) throws InterruptedException {
    	
    	//判断当前头部是否为null,为null是才进行等待处理  或者等待唤醒
        if (head == null) {
            wait(maxMillisToWait);
        }
        //再次调用获取订阅挂载对象的处理
        return poll();
    }

}
