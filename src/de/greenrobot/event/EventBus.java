package de.greenrobot.event;

import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

public class EventBus {

    public static String TAG = "Event";
    //框架内部默认的EventBus对象
    static volatile EventBus defaultInstance;
    //框架内部默认使用的建造器对象--------注意这个是类共享的
    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();
    //本集合用于缓存订阅类型和此类型对应的父类和接口的集合---------------------------------------------------------------------------------->关键数据结构
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<Class<?>, List<Class<?>>>();
    //本集合用于存储  订阅方法中的参数类型   和这个参数类型对应的订阅者和订阅方法的订阅信息集合        即一个参数类型可以对应多个不同的订阅对象的同一个订阅方法
    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    //用于存储本EventBus对象中订阅的对象和对应的方法
    private final Map<Object, List<Class<?>>> typesBySubscriber;
    private final Map<Class<?>, Object> stickyEvents;

    //创建本地线程共享变量
    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
    	
    	//重写该方法用于初始化对象,即进行set方法时不会出现错误
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };

    private final HandlerPoster mainThreadPoster;
    private final BackgroundPoster backgroundPoster;
    private final AsyncPoster asyncPoster;
    private final SubscriberMethodFinder subscriberMethodFinder;
    private final ExecutorService executorService;

    private final boolean throwSubscriberException;
    private final boolean logSubscriberExceptions;
    private final boolean logNoSubscriberMessages;
    private final boolean sendSubscriberExceptionEvent;
    private final boolean sendNoSubscriberEvent;
    private final boolean eventInheritance;

    //获取框架默认的EventBus对象------>内部使用默认的建造器对象来创建EventBus对象
    public static EventBus getDefault() {
        if (defaultInstance == null) {
            synchronized (EventBus.class) {
                if (defaultInstance == null) {
                	//创建默认EventBus对象
                    defaultInstance = new EventBus();
                }
            }
        }
        return defaultInstance;
    }
    
    //使用默认的建造器对象创建EventBus对象
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    //根据给定的建造器来创建EventBus对象---------->创建EventBus对象最终都会走这个方法     主要是完成一些初始化处理
    EventBus(EventBusBuilder builder) {
        subscriptionsByEventType = new HashMap<Class<?>, CopyOnWriteArrayList<Subscription>>();
        //初始化创建存储订阅对象的集合
        typesBySubscriber = new HashMap<Object, List<Class<?>>>();
        stickyEvents = new ConcurrentHashMap<Class<?>, Object>();
        mainThreadPoster = new HandlerPoster(this, Looper.getMainLooper(), 10);
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);
        //创建订阅对象中订阅方法的过滤器对象------------------------>每个EventBus对象都有自己的订阅过滤器对象
        subscriberMethodFinder = new SubscriberMethodFinder(builder.skipMethodVerificationForClasses);
        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }

    //获取一个建造器对象
    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    //清理本EventBus对象中的缓存数据
    public static void clearCaches() {
    	//清理订阅过滤对象与过滤方法的缓存数据
        SubscriberMethodFinder.clearCaches();
        //
        eventTypesCache.clear();
    }

    //对订阅者的注册   使用默认的优先级别
    public void register(Object subscriber) {
        register(subscriber, false, 0);
    }

    //对订阅者的注册   可以设置订阅者的优先级别
    public void register(Object subscriber, int priority) {
        register(subscriber, false, priority);
    }

    //对订阅者的注册   是sticky类型的事件    使用默认的优先级别
    public void registerSticky(Object subscriber) {
        register(subscriber, true, 0);
    }

    //对订阅者的注册   是sticky类型的事件    可以自己设置其优先级别
    public void registerSticky(Object subscriber, int priority) {
        register(subscriber, true, priority);
    }

    //真正进行订阅者注册的处理函数   参数一  订阅者    参数二  是否是sticky事件  参数三  优先级------------------->注册订阅者必须走的注册函数
    private synchronized void register(Object subscriber, boolean sticky, int priority) {
    	//根据订阅者的类来获取其上设置的所有订阅方法---------->内部使用了一些提供效率的优化机制
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriber.getClass());
        //循环所有的订阅方法
        for (SubscriberMethod subscriberMethod : subscriberMethods) {
        	//将订阅方法
            subscribe(subscriber, subscriberMethod, sticky, priority);
        }
    }

    // Must be called in synchronized block
    //
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod, boolean sticky, int priority) {
    	//获取订阅方法参数的类型
        Class<?> eventType = subscriberMethod.eventType;
        //获取在订阅方法中参数类型对应的所有订阅信息
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        //根据订阅对象  订阅方法   订阅权限 创建一个订阅信息
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod, priority);
        //判断原先是否存储了本参数对应的订阅信息
        if (subscriptions == null) {
        	//没有,创建一个集合用于存储本类型对应的订阅信息
            subscriptions = new CopyOnWriteArrayList<Subscription>();
            //将其放置到map集合中    键值为参数类型  值为参数对应的订阅信息  
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
        	//判断订阅信息集合中是否包含了此次订阅的信息
            if (subscriptions.contains(newSubscription)) {
            	//包含则抛出异常
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event " + eventType);
            }
        }

        // Starting with EventBus 2.2 we enforced methods to be public (might change with annotations again)
        // subscriberMethod.method.setAccessible(true);
        //获取原先订阅信息集合中订阅信息的个数
        int size = subscriptions.size();
        //下面是根据设置的权限找到一个合适的插入位置
        for (int i = 0; i <= size; i++) {
        	//进行位置获取
            if (i == size || newSubscription.priority > subscriptions.get(i).priority) {
            	//将新的订阅信息插入到集合中
                subscriptions.add(i, newSubscription);
                break;
            }
        }
        //根据订阅对象获取其对应的订阅方法集合
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        //判断是否创建此订阅方法集合
        if (subscribedEvents == null) {
        	//创建存储订阅方法的集合
            subscribedEvents = new ArrayList<Class<?>>();
            //将订阅对象和创建的订阅方法集合存储到Map集合中
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        //将订阅方法中的事件类型放置到订阅集合中
        subscribedEvents.add(eventType);

        //判断是否是sticky事件类型的
        if (sticky) {
        	//判断是否允许订阅方法中参数的继承关系
            if (eventInheritance) {
                // Existing sticky events of all subclasses of eventType have to be considered.
                // Note: Iterating over all events may be inefficient with lots of sticky events,
                // thus data structure should be changed to allow a more efficient lookup
                // (e.g. an additional map storing sub classes of super classes: Class -> List<Class>).
            	//
                Set<Map.Entry<Class<?>, Object>> entries = stickyEvents.entrySet();
                for (Map.Entry<Class<?>, Object> entry : entries) {
                    Class<?> candidateEventType = entry.getKey();
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        Object stickyEvent = entry.getValue();
                        checkPostStickyEventToSubscription(newSubscription, stickyEvent);
                    }
                }
            } else {
                Object stickyEvent = stickyEvents.get(eventType);
                checkPostStickyEventToSubscription(newSubscription, stickyEvent);
            }
        }
    }

    private void checkPostStickyEventToSubscription(Subscription newSubscription, Object stickyEvent) {
        if (stickyEvent != null) {
            // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
            // --> Strange corner case, which we don't take care of here.
            postToSubscription(newSubscription, stickyEvent, Looper.getMainLooper() == Looper.myLooper());
        }
    }

    //用于检测是否注册了给定的订阅对象
    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    //取消对给定订阅对象的订阅
    public synchronized void unregister(Object subscriber) {
    	//根据订阅对象获取其对应的订阅类型集合
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        //判断是否根据订阅对象获取到对应的订阅类型集合
        if (subscribedTypes != null) {
        	//循环遍历订阅对象中的所有订阅方法
            for (Class<?> eventType : subscribedTypes) {
            	//取消订阅对象中订阅的类型
                unsubscribeByEventType(subscriber, eventType);
            }
            //最后在订阅集合中移除其对应的订阅对象
            typesBySubscriber.remove(subscriber);
        } else {
        	//提示给定的订阅对象没有在订阅集合中
            Log.w(TAG, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }
    
    //取消对订阅对象中一个订阅类型的处理
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
    	//根据订阅类型获取订阅信息集合
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        //判断获取的集合是否存在
        if (subscriptions != null) {
        	//获取订阅信息集合的大小
            int size = subscriptions.size();
            //循环遍历集合,找到订阅对象对应的订阅信息,并进行移除
            for (int i = 0; i < size; i++) {
            	//获取本条订阅信息
                Subscription subscription = subscriptions.get(i);
                //判断订阅对象是否是给定的订阅对象
                if (subscription.subscriber == subscriber) {
                	//设置活跃标志位为false--------------------------------------------->目的有待研究？？？？？？？？？？？？？？？？？？？？？？？？？
                    subscription.active = false;
                    //在集合中移除此条订阅信息
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    //发送一个给定的事件类型给EventBus系统进行处理
    public void post(Object event) {
    	//获取当前线程存储的PostingThreadState参数状态
        PostingThreadState postingState = currentPostingThreadState.get();
        //获取当前线程需要发送的订阅事件
        List<Object> eventQueue = postingState.eventQueue;
        //将当前的订阅事件放置到发送事件集合中----------------------------------------->这个地方注意是一个线程中,所以不会出现添加和删除元素的冲突
        eventQueue.add(event);
        //判断当前线程是否处于发送订阅消息的状态
        if (!postingState.isPosting) {
        	//获取当前线程是否是主线程
            postingState.isMainThread = Looper.getMainLooper() == Looper.myLooper();
            //设置当前状态为正在发送消息的状态
            postingState.isPosting = true;
            //判断当前线程是否取消了发送订阅消息的处理
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
            	//进行循环处理,将待发送的消息发送出去
                while (!eventQueue.isEmpty()) {
                	//进行单个订阅消息的处理
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
            	//最后对状态进行恢复出来
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }
    
    //进行订阅消息的响应处理
    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
    	//获取发送订阅的参数类型对应的类名
        Class<?> eventClass = event.getClass();
        boolean subscriptionFound = false;
        //判断是否允许父类型的参数类型也可以响应这个消息的处理
        if (eventInheritance) {
        	//检测本类型对应的其父类型集合
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            //获取集合中的元素个数
            int countTypes = eventTypes.size();
            //循环处理,进行消息的发送
            for (int h = 0; h < countTypes; h++) {
            	//首先获取一个类型-------------------------------------->注意一开始是提交参数的类型
                Class<?> clazz = eventTypes.get(h);
                //
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
        	//如果进行严格的类型区分,那么只发生此类型的处理消息
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        //判断是否找到对应的处理方法,并启动处理消息
        if (!subscriptionFound) {
            if (logNoSubscriberMessages) {
                Log.d(TAG, "No subscribers registered for event " + eventClass);
            }
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class && eventClass != SubscriberExceptionEvent.class) {
                post(new NoSubscriberEvent(this, event));
            }
        }
    }
    
    //在类型集合中用于检测给定类型的父类型
    private List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
        	//根据给定的参数类型的类名获取
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            //判断是否已经存储了此类型对应的父类集合
            if (eventTypes == null) {
            	//没有存储,首先创建一个用于存储的集合
                eventTypes = new ArrayList<Class<?>>();
                //获取当前类型的名称
                Class<?> clazz = eventClass;
                //进行循环处理,添加本类与其对应的父类对象
                while (clazz != null) {
                	//添加获取到的类------------------------------------------------->这个地方是否可以进行优化处理,使得能够直接对需要的父类和子类进行存储处理
                    eventTypes.add(clazz);
                    //添加类对应的接口
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    //获取其父类
                    clazz = clazz.getSuperclass();
                }
                //将类型和类型对应的父类和接口添加到集合中,进行缓存处理
                eventTypesCache.put(eventClass, eventTypes);
            }
            //最终返回本类型对象的父类和接口的集合
            return eventTypes;
        }
    }
    
    //添加接口类型到集合中------------------>注意点接口的接口
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
    	//循环处理添加接口类型到集合中
        for (Class<?> interfaceClass : interfaces) {
        	//判断在集合中是否包含了此接口类
            if (!eventTypes.contains(interfaceClass)) {
            	//添加接口到集合中
                eventTypes.add(interfaceClass);
                //进行递归处理接口的接口
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }
    
    //
    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
        	//首先根据类型获取订阅信息集合
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        //判断是否有订阅信息集合存在
        if (subscriptions != null && !subscriptions.isEmpty()) {
        	//进行循环处理,获取当个订阅对象,并进行消息的响应处理
            for (Subscription subscription : subscriptions) {
                postingState.event = event;
                postingState.subscription = subscription;
                boolean aborted = false;
                try {
                    postToSubscription(subscription, event, postingState.isMainThread);
                    aborted = postingState.canceled;
                } finally {
                	//
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        //没有找到对应的订阅对象集合,返回没有找到订阅处理
        return false;
    }
    
    //
    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        switch (subscription.subscriberMethod.threadMode) {
            //需要当前线程进行消息处理
            case PostThread:
                invokeSubscriber(subscription, event);
                break;
            //需要主线程进行消息处理
            case MainThread:
            	//判断当前线程是否是主线程
                if (isMainThread) {
                	//是主线程,利用反射机制来将方法触发,进行响应
                    invokeSubscriber(subscription, event);
                } else {
                	//当前不是主线程,那么需要将这个带处理的消息发送到主线程队列中,
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            //需要后台线程进行消息处理
            case BackgroundThread:
                if (isMainThread) {
                    backgroundPoster.enqueue(subscription, event);
                } else {
                    invokeSubscriber(subscription, event);
                }
                break;
            //需要独立的子线程进行消息处理
            case Async:
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link #register(Object, int)}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#PostThread}.
     */
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException("This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.PostThread) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access. This can be {@link #registerSticky(Object)} or
     * {@link #getStickyEvent(Class)}.
     */
    public void postSticky(Object event) {
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events.
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     */
    void invokeSubscriber(PendingPost pendingPost) {
        Object event = pendingPost.event;
        Subscription subscription = pendingPost.subscription;
        PendingPost.releasePendingPost(pendingPost);
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    //通过反射的方法,将订阅对象的订阅方法运行起来,即实现消息的响应处理
    void invokeSubscriber(Subscription subscription, Object event) {
        try {
        	//获取方法,利用反射进行触发
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                Log.e(TAG, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass() + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                Log.e(TAG, "Initial event " + exEvent.causingEvent + " caused exception in " + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                Log.e(TAG, "Could not dispatch event: " + event.getClass() + " to subscribing class " + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event, subscription.subscriber);
                post(exEvent);
            }
        }
    }

    /** For ThreadLocal, much faster to set (and get multiple values). */
    final static class PostingThreadState {
    	
    	//本集合用于暂时存储需要发送的订阅处理事件
        final List<Object> eventQueue = new ArrayList<Object>();
        //用于标示当前是否处于发送订阅事件的状态
        boolean isPosting;
        //用于标示当前线程是否是主线程
        boolean isMainThread;
        //
        Subscription subscription;
        //
        Object event;
        //
        boolean canceled;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

}
