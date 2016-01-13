package de.greenrobot.event;

import java.lang.reflect.Method;

/**
 * 封装了订阅者中当个订阅方法的相关信息
 */
final class SubscriberMethod {
	
	//用于存储订阅者中的订阅方法
    final Method method;
    //用于存储对应的模式
    final ThreadMode threadMode;
    //用于存储订阅方法中参数的类型
    final Class<?> eventType;
    //用于与订阅方法想关联的标示------>key值,主要用于两个订阅方法的比较处理
    String methodString;

    SubscriberMethod(Method method, ThreadMode threadMode, Class<?> eventType) {
        this.method = method;
        this.threadMode = threadMode;
        this.eventType = eventType;
    }

    //重写其对应的equals方法,辨别两个订阅方法是否相同
    @Override
    public boolean equals(Object other) {
        if (other instanceof SubscriberMethod) {
        	//检测并创建本检测订阅方法的key值
            checkMethodString();
            //进行类型强制转化
            SubscriberMethod otherSubscriberMethod = (SubscriberMethod)other;
            //检测并创建外来订阅方法的key值
            otherSubscriberMethod.checkMethodString();
            //比较两个字符串是否相同
            return methodString.equals(otherSubscriberMethod.methodString);
        } else {
            return false;
        }
    }

    //检测并进行字符串对象的获取
    private synchronized void checkMethodString() {
        if (methodString == null) {
        	//创建字符串拼接对象
            StringBuilder builder = new StringBuilder(64);
            //进行字符串的拼接处理
            builder.append(method.getDeclaringClass().getName());
            builder.append('#').append(method.getName());
            builder.append('(').append(eventType.getName());
            //获取拼接后的字符串对象
            methodString = builder.toString();
        }
    }

    @Override
    public int hashCode() {
        return method.hashCode();
    }
}