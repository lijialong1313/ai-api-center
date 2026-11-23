<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{{ $title }} · AI API Center</title>
    <style>
        * { box-sizing: border-box; }
        body {
            margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
            background: linear-gradient(135deg, #1e293b 0%, #0f172a 100%);
            color: #e2e8f0; font-size: 14px; line-height: 1.6;
        }
        .box {
            background: rgba(30, 41, 59, 0.9); border: 1px solid #334155; border-radius: 12px;
            padding: 40px; width: 400px; max-width: calc(100vw - 32px);
            box-shadow: 0 20px 50px rgba(0, 0, 0, 0.4);
        }
        .box h1 { font-size: 20px; margin: 0 0 6px; }
        .box .sub { color: #94a3b8; font-size: 13px; margin-bottom: 24px; }
        .btn {
            display: block; width: 100%; padding: 11px; border-radius: 8px; border: none;
            background: #2563eb; color: #fff; font-size: 15px; font-family: inherit;
            cursor: pointer; text-align: center; text-decoration: none; font-weight: 500;
        }
        .btn:hover { background: #1d4ed8; }
        .btn[disabled] { background: #334155; color: #64748b; cursor: not-allowed; }
        .alert {
            padding: 12px 14px; border-radius: 8px; margin-bottom: 18px; font-size: 13px;
            background: rgba(220, 38, 38, 0.15); border: 1px solid rgba(220, 38, 38, 0.4); color: #fca5a5;
        }
        .alert-warn {
            background: rgba(217, 119, 6, 0.15); border-color: rgba(217, 119, 6, 0.4); color: #fcd34d;
        }
        .hint { margin-top: 20px; font-size: 12px; color: #64748b; }
        ol { padding-left: 20px; margin: 8px 0; }
    </style>
</head>
<body>
<div class="box">
    <h1>{{ $title }}</h1>
    <div class="sub">AI API Center · vLLM 排队中间件</div>

    @if(isset($error) && $error != null)
    <div class="alert">{{ $error }}</div>
    @endif

    @if(isset($configured) && $configured)
    <a class="btn" href="/admin/login">使用统一身份认证登录</a>
    <div class="hint">
        登录由学校统一身份认证系统完成，本系统不接触用户口令。
    </div>
    @else
    <div class="alert alert-warn">
        统一身份认证尚未配置，暂时无法登录。
    </div>
    <div class="hint">
        需要在配置中填写 <code>app.sso.client-id</code> 与
        <code>app.sso.client-secret</code>（由学校统一身份认证系统管理员分配），
        并确认 <code>app.sso.redirect-uri</code> 与认证系统登记的地址一致。
    </div>
    @endif
</div>
</body>
</html>
