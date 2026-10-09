package com.hmdp.utils;

public interface ILock {

    boolean tryToLock(String lockKey, String lockValue, long timeoutSec);

    void releaseLock(String lockKey, String lockValue);
}
