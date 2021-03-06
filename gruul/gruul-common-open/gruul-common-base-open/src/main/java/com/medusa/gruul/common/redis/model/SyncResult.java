package com.medusa.gruul.common.redis.model;


import com.medusa.gruul.common.redis.constant.SyncResultCodeEnum;
/**
 * redis 数据检测 结果
 *
 * @author : wangpeng
 * @version : 1.0
 * @since : 16/6/29 上午11:25
 */
public class SyncResult<T> {

    public static final SyncResult OLD = new SyncResult(SyncResultCodeEnum.IS_OLD.getCode(), null);
    private int code;

    private T result;

    public SyncResult() {
    }

    public SyncResult(int code, T result) {
        this.code = code;
        this.result = result;
    }

    public T getResult() {
        return result;
    }

    public void setResult(T result) {
        this.result = result;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }
}
