package de.greenrobot.event;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对订阅类中的方法进行过滤,获取订阅的方法
 */
class SubscriberMethodFinder {
	
	//订阅方法的前缀名称
    private static final String ON_EVENT_METHOD_NAME = "onEvent";

    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;
    //过滤方法是使用的修饰符
    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    //缓存集合,用于缓存订阅者与其对应的事件------>使用集合的目的是,对象在下一次订阅时不用进行注册事件的查找，提高运行的速率------->效率优化,空间换取时间----------------->效率提高点------>这个集合是属于类共享的
    private static final Map<Class<?>, List<SubscriberMethod>> methodCache = new HashMap<Class<?>, List<SubscriberMethod>>();
    //集合对象,用于存储EventBus对象中需要排除的过滤类-------->这个集合是属于对象的------------>注意本集合并不是那么用的,它只是在出现检测订阅方法出现错误时才使用的
    private final Map<Class<?>, Class<?>> skipMethodVerificationForClasses;

    //创建订阅方法过滤器对象的构造函数------>此处能够告诉过滤器对象对那些订阅对象的类不进行过滤处理
    SubscriberMethodFinder(List<Class<?>> skipMethodVerificationForClassesList) {
    	//创建集合用于存储需要排除检测的类
        skipMethodVerificationForClasses = new ConcurrentHashMap<Class<?>, Class<?>>();
        //判断当前EventBus对象中是否设置了排除检测的类
        if (skipMethodVerificationForClassesList != null) {
        	//循环处理,将需要排除的类放置到集合中
            for (Class<?> clazz : skipMethodVerificationForClassesList) {
                skipMethodVerificationForClasses.put(clazz, clazz);
            }
        }
    }

    //根据提供的类名来获取订阅者中需要进行注册的事件          在缓存中获取需要注册的事件  或者  在类中获取需要注册的事件
    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
    	//声明用于存储订阅事件的集合
        List<SubscriberMethod> subscriberMethods;
        synchronized (methodCache) {
        	//首先在缓存中检测是否存储本订阅者需要注册的事件
            subscriberMethods = methodCache.get(subscriberClass);
        }
        
        //如果缓存中能够找到,直接使用缓存中存储的事件集合,并进行返回处理----------------->效率提高点
        if (subscriberMethods != null) {
            return subscriberMethods;
        }
        //创建存储订阅方法的集合对象
        subscriberMethods = new ArrayList<SubscriberMethod>();
        //获取需要进行订阅对象的类名
        Class<?> clazz = subscriberClass;
        //创建存储订阅方法中参数类型的集合对象
        HashMap<String, Class> eventTypesFound = new HashMap<String, Class>();
        //创建拼接字符串处理的对象
        StringBuilder methodKeyBuilder = new StringBuilder();
        //循环获取本类以及父类中注册的订阅事件
        while (clazz != null) {
        	//获取类的名字
            String name = clazz.getName();
            //判断是否是系统的一些类,由于系统的类不能给其添加注册事件,因此不需要进行查找处理------->这个地方也是提高效率的方法----------------->效率提高点
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")) {
                break;
            }
            try {
            	//获取本类中的所有的方法
                Method[] methods = clazz.getDeclaredMethods();
                //过滤需要进行订阅的方法
                filterSubscriberMethods(subscriberMethods, eventTypesFound, methodKeyBuilder, methods);
            } catch (Throwable th) {
                //在过滤方法时出现了异常    没有找到对应的类------>处理策略,只找本类中订阅的方法,不去找其父类中的方法
            	//获取本类中的所有方法
                Method[] methods = subscriberClass.getMethods();
                //清除订阅集合中存储的订阅方法
                subscriberMethods.clear();
                //清除找到的订阅方法中的参数类型集合
                eventTypesFound.clear();
                //重新进行订阅方法的查询---------->此处只找本类中的订阅方法
                filterSubscriberMethods(subscriberMethods, eventTypesFound, methodKeyBuilder, methods);
                break;
            }
            //获取其父类中对应的类对象---->目的是获取父类中订阅的事件
            clazz = clazz.getSuperclass();
        }
        
        //判断是否在订阅者中订阅了相关的事件
        if (subscriberMethods.isEmpty()) {
        	//在订阅者中未能找到相关的注册事件方法,抛出异常
            throw new EventBusException("Subscriber " + subscriberClass + " has no public methods called " + ON_EVENT_METHOD_NAME);
        } else {
        	//将订阅者中注册的事件放置到缓存中，进行缓存处理,加快下次获取注册事件的速度
            synchronized (methodCache) {
                methodCache.put(subscriberClass, subscriberMethods);
            }
            //返回订阅者中注册的事件集合
            return subscriberMethods;
        }
    }

    //过滤类中需要订阅的方法
    private void filterSubscriberMethods(List<SubscriberMethod> subscriberMethods, HashMap<String, Class> eventTypesFound, StringBuilder methodKeyBuilder, Method[] methods) {
        for (Method method : methods) {
        	//获取方法的名称
            String methodName = method.getName();
            //判断待测试方法是否以onEvent开头---->即是否是我们需要订阅的方法------------------>进一步过滤需要订阅的方法
            if (methodName.startsWith(ON_EVENT_METHOD_NAME)) {
            	//获取本方法的修饰符对应的整数
                int modifiers = method.getModifiers();
                Class<?> methodClass = method.getDeclaringClass();
                //判断方法修饰符是否是public且不是Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;------------>进一步过滤需要订阅的方法
                if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                	//获取方法中的参数类型的集合
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    //判断是否是只有一个参数类型----------------------->进一步过滤订阅事件
                    if (parameterTypes.length == 1) {
                    	//根据方法名获取此方法对应的类型
                        ThreadMode threadMode = getThreadMode(methodClass, method, methodName);
                        //判断获取的类型对象是否为null
                        if (threadMode == null) {
                            continue;
                        }
                        //获取方法中参数的类型
                        Class<?> eventType = parameterTypes[0];
                        //清除拼接字符串中的内容
                        methodKeyBuilder.setLength(0);
                        //拼接字符串key值
                        methodKeyBuilder.append(methodName);
                        methodKeyBuilder.append('>').append(eventType.getName());
                        String methodKey = methodKeyBuilder.toString();
                        //将key值和参数类型放置到集合中
                        Class methodClassOld = eventTypesFound.put(methodKey, methodClass);
                        //判断是否是首次添加
                        if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                        	//
                            subscriberMethods.add(new SubscriberMethod(method, threadMode, eventType));
                        } else {
                        	//
                            eventTypesFound.put(methodKey, methodClassOld);
                        }
                    }
                } else if (!skipMethodVerificationForClasses.containsKey(methodClass)) {
                    Log.d(EventBus.TAG, "Skipping method (not public, static or abstract): " + methodClass + "." + methodName);
                }
            }
        }
    }

    //根据方法名创建对应的模式
    private ThreadMode getThreadMode(Class<?> clazz, Method method, String methodName) {
    	//获取方法名onEvent后的类型字符串
        String modifierString = methodName.substring(ON_EVENT_METHOD_NAME.length());
        ThreadMode threadMode;
        if (modifierString.length() == 0) {
        	//创建在当前发送线程对应的模式
            threadMode = ThreadMode.PostThread;
        } else if (modifierString.equals("MainThread")) {
        	//创建发送到主线程对应的模式
            threadMode = ThreadMode.MainThread;
        } else if (modifierString.equals("BackgroundThread")) {
        	//创建发送到独立子线程对应的模式
            threadMode = ThreadMode.BackgroundThread;
        } else if (modifierString.equals("Async")) {
        	//
            threadMode = ThreadMode.Async;
        } else {
        	//方法出现错误的处理----------->判断本方法是否在排除的处理类中---->没有时会抛出异常错误
            if (!skipMethodVerificationForClasses.containsKey(clazz)) {
                throw new EventBusException("Illegal onEvent method, check for typos: " + method);
            } else {
            	//设置返回值为null
                threadMode = null;
            }
        }
        //返回创建的类型
        return threadMode;
    }

    //清除缓存集合中的数据
    static void clearCaches() {
        synchronized (methodCache) {
            methodCache.clear();
        }
    }

}
