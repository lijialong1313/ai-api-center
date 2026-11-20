@extends('admin.layout')

@section('title', 'GPU 后端管理')

@section('content')
@if(isset($message) && $message != null)
<div class="alert alert-info">{{ $message }}</div>
@endif

<div class="card">
    <h2>新增 GPU 后端</h2>
    <form method="post" action="/admin/backends">
        <div class="row">
            <div class="field grow">
                <label>后端标识（唯一，留空自动生成）</label>
                <input type="text" name="backendId" placeholder="gpu-01">
            </div>
            <div class="field grow">
                <label>名称</label>
                <input type="text" name="name" placeholder="3090 双卡机">
            </div>
            <div class="field grow" style="min-width:240px">
                <label>vLLM 地址（OpenAI 兼容端点）</label>
                <input type="text" name="baseUrl" placeholder="http://192.168.1.10:8000" required>
            </div>
            <div class="field" style="width:110px">
                <label>容量</label>
                <input type="number" name="capacity" value="1" min="1" max="64">
            </div>
            <div class="field" style="width:130px">
                <label>健康路径</label>
                <input type="text" name="healthPath" value="/health">
            </div>
            <div class="field">
                <button type="submit" class="btn btn-primary">添加</button>
            </div>
        </div>
        <p class="muted" style="margin:12px 0 0">
            容量对应槽位数：同一时刻该后端最多允许「容量」个请求在途。vLLM 的
            <span class="mono">max_num_seqs=1</span> 时请填 1，否则会打爆后端。
        </p>
    </form>
</div>

<div class="card">
    <h2>后端列表（共 {{ $backendTotal }} 个）</h2>
    <table>
        <thead>
        <tr>
            <th>ID</th>
            <th>标识 / 名称</th>
            <th>地址</th>
            <th>容量</th>
            <th>状态</th>
            <th>健康</th>
            <th>操作</th>
        </tr>
        </thead>
        <tbody>
        @forelse($backends as $backend)
        <tr>
            <td>{{ $backend->getId() }}</td>
            <td>
                <span class="mono">{{ $backend->getBackendId() }}</span><br>
                <span class="muted">{{ $backend->getName() }}</span>
            </td>
            <td class="mono">{{ $backend->getBaseUrl() }}{{ $backend->resolvedHealthPath() }}</td>
            <td>
                <form method="post" action="/admin/backends/update" class="inline-form">
                    <input type="hidden" name="id" value="{{ $backend->getId() }}">
                    <div class="actions">
                        <input type="number" name="capacity" value="{{ $backend->getCapacity() }}"
                               min="1" max="64" style="width:70px">
                        <button type="submit" class="btn btn-sm">保存</button>
                    </div>
                </form>
            </td>
            <td>
                @if($backend->isUsable())
                <span class="badge badge-ok">参与调度</span>
                @else
                <span class="badge badge-off">已禁用</span>
                @endif
            </td>
            <td>
                @if($backend->getLastHealthStatus() == 'HEALTHY')
                <span class="badge badge-ok">健康</span>
                @elseif($backend->getLastHealthStatus() == 'UNHEALTHY')
                <span class="badge badge-bad">不健康</span>
                @else
                <span class="badge badge-warn">未检测</span>
                @endif
                @if($backend->getLastCheckedAt() != null)
                <br><span class="muted" style="font-size:11px">{{ $backend->getLastCheckedAt() }}</span>
                @endif
            </td>
            <td>
                <div class="actions">
                    <form method="post" action="/admin/backends/health-check" class="inline-form">
                        <input type="hidden" name="id" value="{{ $backend->getId() }}">
                        <button type="submit" class="btn btn-sm">健康检查</button>
                    </form>
                    <form method="post" action="/admin/backends/toggle" class="inline-form">
                        <input type="hidden" name="id" value="{{ $backend->getId() }}">
                        <button type="submit" class="btn btn-sm">
                            @if($backend->getEnabled()) 禁用 @else 启用 @endif
                        </button>
                    </form>
                    <form method="post" action="/admin/backends/delete" class="inline-form"
                          onsubmit="return confirm('确认删除该后端？');">
                        <input type="hidden" name="id" value="{{ $backend->getId() }}">
                        <button type="submit" class="btn btn-sm btn-danger">删除</button>
                    </form>
                </div>
            </td>
        </tr>
        @empty
        <tr><td colspan="7" class="muted">还没有配置任何 GPU 后端。</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
@endsection
