<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>@yield('title', '管理后台') · AI API Center</title>
    <style>
        :root {
            --bg: #f4f6f9;
            --card: #ffffff;
            --border: #e3e8ef;
            --text: #1f2933;
            --muted: #6b7785;
            --primary: #2563eb;
            --primary-dark: #1d4ed8;
            --danger: #dc2626;
            --success: #16a34a;
            --warn: #d97706;
        }
        * { box-sizing: border-box; }
        body {
            margin: 0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
            background: var(--bg);
            color: var(--text);
            font-size: 14px;
            line-height: 1.6;
        }
        .layout { display: flex; min-height: 100vh; }
        .sidebar {
            width: 220px;
            background: #111827;
            color: #cbd5e1;
            padding: 20px 0;
            flex-shrink: 0;
        }
        .sidebar .brand {
            padding: 0 20px 20px;
            font-size: 16px;
            font-weight: 600;
            color: #fff;
            border-bottom: 1px solid #1f2937;
            margin-bottom: 12px;
        }
        .sidebar .brand small { display: block; font-weight: 400; font-size: 11px; color: #94a3b8; }
        .sidebar a {
            display: block;
            padding: 10px 20px;
            color: #cbd5e1;
            text-decoration: none;
            border-left: 3px solid transparent;
        }
        .sidebar a:hover { background: #1f2937; color: #fff; }
        .sidebar a.active { background: #1f2937; color: #fff; border-left-color: var(--primary); }
        .main { flex: 1; padding: 24px 32px; min-width: 0; }
        .topbar {
            display: flex; justify-content: space-between; align-items: center;
            margin-bottom: 20px; padding-bottom: 16px; border-bottom: 1px solid var(--border);
        }
        .topbar h1 { font-size: 20px; margin: 0; }
        .topbar .who { color: var(--muted); font-size: 13px; }
        .topbar .who a { color: var(--danger); margin-left: 10px; }
        .card {
            background: var(--card); border: 1px solid var(--border); border-radius: 8px;
            padding: 20px; margin-bottom: 20px;
        }
        .card h2 { font-size: 15px; margin: 0 0 16px; }
        table { width: 100%; border-collapse: collapse; }
        th, td { text-align: left; padding: 10px 12px; border-bottom: 1px solid var(--border); }
        th { font-size: 12px; color: var(--muted); font-weight: 600; text-transform: uppercase; }
        tr:last-child td { border-bottom: none; }
        input[type=text], input[type=number], input[type=url], select {
            padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px;
            font-size: 13px; font-family: inherit; width: 100%;
        }
        input:focus, select:focus { outline: none; border-color: var(--primary); }
        .row { display: flex; gap: 12px; flex-wrap: wrap; align-items: flex-end; }
        .field { display: flex; flex-direction: column; gap: 4px; }
        .field label { font-size: 12px; color: var(--muted); }
        .field.grow { flex: 1; min-width: 160px; }
        .btn {
            display: inline-block; padding: 7px 14px; border-radius: 6px; border: 1px solid var(--border);
            background: #fff; color: var(--text); cursor: pointer; font-size: 13px;
            font-family: inherit; text-decoration: none; white-space: nowrap;
        }
        .btn:hover { background: #f8fafc; }
        .btn-primary { background: var(--primary); border-color: var(--primary); color: #fff; }
        .btn-primary:hover { background: var(--primary-dark); }
        .btn-danger { color: var(--danger); border-color: #fecaca; }
        .btn-danger:hover { background: #fef2f2; }
        .btn-sm { padding: 4px 10px; font-size: 12px; }
        .badge {
            display: inline-block; padding: 2px 8px; border-radius: 10px;
            font-size: 11px; font-weight: 600;
        }
        .badge-ok { background: #dcfce7; color: var(--success); }
        .badge-off { background: #f1f5f9; color: var(--muted); }
        .badge-warn { background: #fef3c7; color: var(--warn); }
        .badge-bad { background: #fee2e2; color: var(--danger); }
        .alert { padding: 12px 16px; border-radius: 6px; margin-bottom: 16px; font-size: 13px; }
        .alert-error { background: #fef2f2; border: 1px solid #fecaca; color: var(--danger); }
        .alert-info { background: #eff6ff; border: 1px solid #bfdbfe; color: var(--primary-dark); }
        .alert-warn { background: #fffbeb; border: 1px solid #fde68a; color: var(--warn); }
        .mono { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
        .muted { color: var(--muted); }
        .stats { display: flex; gap: 16px; flex-wrap: wrap; }
        .stat {
            flex: 1; min-width: 150px; background: var(--card); border: 1px solid var(--border);
            border-radius: 8px; padding: 16px;
        }
        .stat .n { font-size: 26px; font-weight: 600; }
        .stat .l { font-size: 12px; color: var(--muted); }
        .inline-form { display: inline; }
        .actions { display: flex; gap: 6px; flex-wrap: wrap; }
    </style>
</head>
<body>
<div class="layout">
    <aside class="sidebar">
        <div class="brand">AI API Center<small>vLLM 排队中间件</small></div>
        <a href="/admin" class="@if(isset($active) && $active == 'dashboard')active@endif">控制台</a>
        <a href="/admin/api-keys" class="@if(isset($active) && $active == 'api-keys')active@endif">API Key 管理</a>
        <a href="/admin/backends" class="@if(isset($active) && $active == 'backends')active@endif">GPU 后端管理</a>
        <a href="/admin/users" class="@if(isset($active) && $active == 'users')active@endif">管理员管理</a>
    </aside>
    <div class="main">
        <div class="topbar">
            <h1>@yield('title', '管理后台')</h1>
            @if(isset($user) && $user != null)
            <div class="who">
                {{ $user->getName() }}（{{ $user->getStaffNo() }}）
                <a href="/admin/logout">退出登录</a>
            </div>
            @endif
        </div>
        @yield('content')
    </div>
</div>
</body>
</html>
