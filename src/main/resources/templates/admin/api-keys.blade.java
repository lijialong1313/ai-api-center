@extends('admin.layout')

@section('title', 'API Key 管理')

@section('content')
@if(isset($newKey) && $newKey != null)
<div class="alert alert-warn">
    <strong>新 Key：</strong>
    <span class="mono" id="newKeyValue">{{ $newKey }}</span>
    <button type="button" class="btn btn-sm" onclick="copyText('{{ $newKey }}', this)">复制</button>
    <div style="margin-top:6px;font-size:12px">
        这个值之后仍可在下方列表中查看与复制，不会丢失。
    </div>
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
            <div class="field" style="width:120px">
                <label>权重</label>
                <input type="number" name="weight" value="1" min="1" max="1000">
            </div>
            <div class="field" style="width:150px">
                <label>等级</label>
                <input type="number" name="level" value="0" min="0" max="99">
            </div>
            <div class="field">
                <button type="submit" class="btn btn-primary">生成 Key</button>
            </div>
        </div>
        <p class="muted" style="margin:12px 0 0">
            权重用于平滑加权轮询：权重 5 的 Key 大约获得 5 倍服务机会，权重 1 的 Key 有保底、不会被饿死。<br>
            等级用于分级调度：某些 GPU 可设「最低等级」，只有达到该等级的 Key 才会被调度到它上面。0 为默认等级。
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
            <th>Key</th>
            <th>权重</th>
            <th>等级</th>
            <th>状态</th>
            <th>最近使用</th>
            <th>操作</th>
        </tr>
        </thead>
        <tbody>
        @forelse($keys as $key)
        <tr>
            <td>{{ $key->getId() }}</td>
            <td>
                {{-- 名称就地编辑：单独一个小表单，避免与权重互相影响 --}}
                <form method="post" action="/admin/api-keys/update" class="inline-form">
                    <input type="hidden" name="id" value="{{ $key->getId() }}">
                    <div class="actions">
                        <input type="text" name="label" value="{{ $key->getLabel() }}"
                               placeholder="未命名" style="width:130px">
                        <button type="submit" class="btn btn-sm">改名</button>
                    </div>
                </form>
            </td>
            <td>
                @if($key->hasKeyValue())
                <span class="mono" id="key-{{ $key->getId() }}"
                      data-full="{{ $key->getKeyValue() }}"
                      data-masked="{{ $key->maskedKeyValue() }}"
                      style="font-size:12px">{{ $key->maskedKeyValue() }}</span>
                <div class="actions" style="margin-top:4px">
                    <button type="button" class="btn btn-sm"
                            onclick="toggleKey({{ $key->getId() }}, this)">显示</button>
                    <button type="button" class="btn btn-sm"
                            onclick="copyText(document.getElementById('key-{{ $key->getId() }}').dataset.full, this)">复制</button>
                </div>
                @else
                <span class="muted" style="font-size:12px">{{ $key->getKeyPrefix() }}…（旧数据无明文）</span>
                @endif
            </td>
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
                <form method="post" action="/admin/api-keys/update" class="inline-form">
                    <input type="hidden" name="id" value="{{ $key->getId() }}">
                    <div class="actions">
                        <input type="number" name="level" value="{{ $key->getLevel() }}"
                               min="0" max="99" style="width:70px">
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
                    <form method="post" action="/admin/api-keys/regenerate" class="inline-form"
                          onsubmit="return confirm('重新生成后旧 Key 立即失效，使用它的客户端需更新配置。确认继续？');">
                        <input type="hidden" name="id" value="{{ $key->getId() }}">
                        <button type="submit" class="btn btn-sm">重新生成</button>
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
        <tr><td colspan="8" class="muted">还没有任何 Key，请先在上方新建。</td></tr>
        @endforelse
        </tbody>
    </table>
</div>

<script>
    /**
     * 复制文本到剪贴板，并给按钮一个短暂的"已复制"反馈。
     *
     * 为什么用 execCommand 兜底：后台可能通过 http（非 https）访问，
     * 而 navigator.clipboard 只在 secure context 下可用。
     */
    function copyText(text, btn) {
        if (!text) { return; }
        var done = function () {
            var old = btn.textContent;
            btn.textContent = '已复制';
            setTimeout(function () { btn.textContent = old; }, 1200);
        };
        if (navigator.clipboard && window.isSecureContext) {
            navigator.clipboard.writeText(text).then(done, function () { fallback(); });
        } else {
            fallback();
        }
        function fallback() {
            var ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            try { document.execCommand('copy'); done(); } catch (e) { alert('复制失败，请手动选择文本'); }
            document.body.removeChild(ta);
        }
    }

    /** 在打码与明文之间切换 */
    function toggleKey(id, btn) {
        var el = document.getElementById('key-' + id);
        var showing = el.dataset.showing === '1';
        el.textContent = showing ? el.dataset.masked : el.dataset.full;
        el.dataset.showing = showing ? '0' : '1';
        btn.textContent = showing ? '显示' : '隐藏';
    }
</script>
@endsection
