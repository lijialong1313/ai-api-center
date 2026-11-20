package com.weacsoft.aiApiCenter.app.http.middleware;

import com.weacsoft.jaravel.vendor.springboot.annotation.MiddlewareAlias;

/**
 * 全局中间件：裁剪请求参数首尾空白。
 * <p>
 * 框架的预定义中间件是普通类（不是 Spring Bean），需要继承后标注
 * {@link MiddlewareAlias} 才会被扫描注册；这里直接继承用默认行为。
 * 密码类字段本系统不存在（无口令登录），因此无需额外排除。
 */
@MiddlewareAlias("trim")
public class TrimStringsMiddleware extends com.weacsoft.jaravel.vendor.http.middleware.TrimStrings {
}
