@extends('admin.layout')

@section('title', '控制台')

@section('content')
<div class="stats">
    <div class="stat">
        <div class="n">{{ $enabledKeyCount }} / {{ $keyCount }}</div>
        <div class="l">可用 API Key / 全部</div>
    </div>
    <div class="stat">
        <div class="n">{{ $enabledBackendCount }} / {{ $backendCount }}</div>
        <div class="l">参与调度的 GPU 后端 / 全部</div>
    </div>
    <div class="stat">
        <div class="n">{{ $totalCapacity }}</div>
        <div class="l">当前可用槽位总数（in-flight 上限）</div>
    </div>
    <div class="stat">
        <div class="n">{{ $adminCount }}</div>
        <div class="l"><a href="/admin/users" style="color:inherit">可用管理员</a></div>
    </div>
</div>

<div class="card">
    <h2>部署模式说明</h2>
    <p class="muted">
        队列驱动由 <span class="mono">application.yml</span> 的
        <span class="mono">jaravel.queue.driver</span> 决定，四种组合共用同一套业务代码：
    </p>
    <table>
        <thead>
        <tr><th>部署模式</th><th>队列驱动</th><th>协调状态</th><th>必需模块</th></tr>
        </thead>
        <tbody>
        <tr><td>单机 · 无 Redis</td><td class="mono">sync</td><td>JVM 内存</td><td class="mono">starter</td></tr>
        <tr><td>单机 · 有 Redis</td><td class="mono">redis</td><td>Redis</td><td class="mono">starter + redis</td></tr>
        <tr><td>多机 · 无 Redis</td><td class="mono">database</td><td>共享库 jobs 表</td><td class="mono">starter + queue-database</td></tr>
        <tr><td>多机 · 有 Redis</td><td class="mono">redis</td><td>Redis（原子 + 分布式锁）</td><td class="mono">starter + redis</td></tr>
        </tbody>
    </table>
</div>

<div class="card">
    <h2>快速开始</h2>
    <ol class="muted">
        <li>在「API Key 管理」新建一个 Key（明文仅显示一次，请立即保存）。</li>
        <li>在「GPU 后端管理」填入 vLLM 地址与容量（多数后端容量为 1），并做一次健康检查。</li>
        <li>把 DSH 的 Provider baseURL 指向本中间件的 <span class="mono">/v1</span>，
            API Key 填第 1 步得到的值，并确保请求头带 <span class="mono">x-session-id</span>。</li>
    </ol>
</div>
@endsection
