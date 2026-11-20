package com.weacsoft.aiApiCenter.app.http.middleware;

import com.weacsoft.jaravel.vendor.springboot.annotation.MiddlewareAlias;

/**
 * 全局中间件：空字符串转 null。
 * <p>
 * 与 {@link TrimStringsMiddleware} 配合，让控制器里 {@code request.input("x") != null}
 * 就能可靠表达「用户填了值」，不用再叠一层 {@code isBlank()} 判断。
 */
@MiddlewareAlias("nullEmpty")
public class ConvertEmptyStringsToNullMiddleware
        extends com.weacsoft.jaravel.vendor.http.middleware.ConvertEmptyStringsToNull {
}
