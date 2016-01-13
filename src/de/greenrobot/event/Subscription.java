package de.greenrobot.event;

final class Subscription {
	
	//订阅对象
    final Object subscriber;
    //订阅对象中的一个订阅方法
    final SubscriberMethod subscriberMethod;
    //本订阅方法的优先级别
    final int priority;
    
    /**
     * Becomes false as soon as {@link EventBus#unregister(Object)} is called, which is checked by queued event delivery
     * {@link EventBus#invokeSubscriber(PendingPost)} to prevent race conditions.
     */
    volatile boolean active;

    Subscription(Object subscriber, SubscriberMethod subscriberMethod, int priority) {
        this.subscriber = subscriber;
        this.subscriberMethod = subscriberMethod;
        this.priority = priority;
        active = true;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof Subscription) {
            Subscription otherSubscription = (Subscription) other;
            return subscriber == otherSubscription.subscriber && subscriberMethod.equals(otherSubscription.subscriberMethod);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return subscriber.hashCode() + subscriberMethod.methodString.hashCode();
    }
}