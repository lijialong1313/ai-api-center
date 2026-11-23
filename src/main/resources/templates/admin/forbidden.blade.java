<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{{ $title }} · AI API Center</title>
    <style>
        body {
            margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
            background: #f4f6f9; color: #1f2933; font-size: 14px; line-height: 1.6;
        }
        .box {
            background: #fff; border: 1px solid #e3e8ef; border-radius: 12px;
            padding: 40px; width: 460px; max-width: calc(100vw - 32px); text-align: center;
        }
        h1 { font-size: 20px; margin: 0 0 12px; color: #dc2626; }
        p { color: #6b7785; margin: 0 0 8px; }
        .who { margin: 16px 0; padding: 12px; background: #f8fafc; border-radius: 8px; }
        .btn {
            display: inline-block; margin-top: 16px; padding: 9px 18px; border-radius: 8px;
            background: #2563eb; color: #fff; text-decoration: none; font-size: 14px;
        }
        .btn:hover { background: #1d4ed8; }
    </style>
</head>
<body>
<div class="box">
    <h1>无访问权限</h1>
    <p>当前账号可以通过统一身份认证登录，但没有管理后台权限。</p>
    @if(isset($user) && $user != null)
    <div class="who">
        <strong>{{ $user->getName() }}</strong>（{{ $user->getStaffNo() }}）<br>
        <span style="color:#6b7785">部门：{{ $user->getCollege() }}</span><br>
        <span style="color:#6b7785">角色：{{ $user->getAllRoles() }}</span>
    </div>
    @endif
    <p style="font-size:13px">管理后台仅对具备教师 / 管理员角色的账号开放。如需使用，请联系系统管理员为你授权。</p>
    <a class="btn" href="/admin/logout">退出登录</a>
</div>
</body>
</html>
