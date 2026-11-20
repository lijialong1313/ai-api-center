@extends('admin.layout')

@section('title', 'API Key 管理')

@section('content')
@if(isset($newKey) && $newKey != null)
<div class="alert alert-warn">
    <strong>新 Key 已生成，仅显示这一次，请立即保存：</strong><br>
    <span class="mono">{{ $newKey }}</span>
</div>
@endif

<div class="card">
    <h2>新建 API Key</h2>
    <form method="post" action="/admin/api-keys">
        <div class="row">
            <div class="field grow">
                <label>名称 / 用途</label>
                <input type="text" name="label" placeholder="例如：教务系统、算法组-DSH" required>
            </div>
            <div class="field" style="width:140px">
                <label>权重</label>
                <input type="number" name="weight" value="1" min="1" max="1000">
            </div>
            <div class="field">
                <button type="submit" class="btn btn-primary">生成 Key</button>
            </div>
        </div>
        <p class="muted" style="margin:12px 0 0">
            权重用于平滑加权轮询：权重 5 的 Key 大约获得 5 倍服务机会，权重 1 的 Key 有保底执行机会、不会被饿死。
        </p>
    </form>
</div>

<div class="card">
    <h2>Key 列表（共 {{ $keyTotal }} 个）</h2>
    <table>
        <thead>
        <tr>
            <th>ID</th>
            <th>名称</th>
            <th>前缀</th>
            <th>权重</th>
            <th>状态</th>
            <th>最近使用</th>
            <th>操作</th>
        </tr>
        </thead>
        <tbody>
        @forelse($keys as $key)
        <tr>
            <td>{{ $key->getId() }}</td>
            <td>{{ $key->getLabel() }}</td>
            <td class="mono">{{ $key->getKeyPrefix() }}…</td>
            <td>
                <form method="post" action="/admin/api-keys/update" class="inline-form">
                    <input type="hidden" name="id" value="{{ $key->getId() }}">
                    <div class="actions">
                        <input type="number" name="weight" value="{{ $key->getWeight() }}"
                               min="1" max="1000" style="width:80px">
                        <button type="submit" class="btn btn-sm">保存</button>
                    </div>
                </form>
            </td>
            <td>
                @if($key->isUsable())
                <span class="badge badge-ok">启用</span>
                @else
                <span class="badge badge-off">停用</span>
                @endif
            </td>
            <td class="muted">{{ $key->getLastUsedAt() == null ? '从未' : $key->getLastUsedAt() }}</td>
            <td>
                <div class="actions">
                    <form method="post" action="/admin/api-keys/toggle" class="inline-form">
                        <input type="hidden" name="id" value="{{ $key->getId() }}">
                        <button type="submit" class="btn btn-sm">
                            @if($key->getEnabled()) 停用 @else 启用 @endif
                        </button>
                    </form>
                    <form method="post" action="/admin/api-keys/delete" class="inline-form"
                          onsubmit="return confirm('确认删除该 Key？删除后使用它的客户端会立即失效。');">
                        <input type="hidden" name="id" value="{{ $key->getId() }}">
                        <button type="submit" class="btn btn-sm btn-danger">删除</button>
                    </form>
                </div>
            </td>
        </tr>
        @empty
        <tr><td colspan="7" class="muted">还没有任何 Key，请先在上方新建。</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
@endsection
