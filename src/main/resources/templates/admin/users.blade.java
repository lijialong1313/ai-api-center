@extends('admin.layout')

@section('title', '管理员管理')

@section('content')
@if(isset($message) && $message != null)
<div class="alert alert-info">{{ $message }}</div>
@endif

<div class="card">
    <h2>新增管理员</h2>
    <form method="post" action="/admin/users">
        <div class="row">
            <div class="field grow">
                <label>学工号（唯一，必填）</label>
                <input type="text" name="staffNo" placeholder="例如：2023001" required>
            </div>
            <div class="field grow">
                <label>姓名（可留空，首次登录会自动补全）</label>
                <input type="text" name="name" placeholder="张三">
            </div>
            <div class="field" style="width:150px">
                <label>角色</label>
                <select name="role">
                    <option value="admin">管理员</option>
                    <option value="user">普通用户</option>
                </select>
            </div>
            <div class="field">
                <button type="submit" class="btn btn-primary">创建</button>
            </div>
        </div>
        <p class="muted" style="margin:12px 0 0">
            这里用于<b>预先授权</b>：某人还没登录过也可以先建成管理员，
            他第一次用统一身份认证登录时会按学工号匹配到这条记录，权限立刻生效。<br>
            反过来，若某人登录后只是普通用户（角色不在 <span class="mono">app.sso.admin-roles</span> 里），
            也可以在这里把他提升为管理员。
        </p>
    </form>
</div>

<div class="stats" style="margin-bottom:20px">
    <div class="stat">
        <div class="n">{{ $adminTotal }}</div>
        <div class="l">当前可用管理员（角色=admin 且启用）</div>
    </div>
    <div class="stat">
        <div class="n">{{ $userTotal }}</div>
        <div class="l">账号总数</div>
    </div>
</div>

<div class="card">
    <h2>账号列表（共 {{ $userTotal }} 个）</h2>
    <table>
        <thead>
        <tr>
            <th>学工号</th>
            <th>姓名 / 部门</th>
            <th>角色</th>
            <th>状态</th>
            <th>最近登录</th>
            <th>操作</th>
        </tr>
        </thead>
        <tbody>
        @forelse($users as $u)
        <tr>
            <td class="mono">
                {{ $u->getStaffNo() }}
                @if($u->getId() == $user->getId())
                <span class="badge badge-ok">当前登录</span>
                @endif
            </td>
            <td>
                {{ $u->getName() }}
                @if($u->getCollege() != null)
                <br><span class="muted" style="font-size:12px">{{ $u->getCollege() }}</span>
                @endif
            </td>
            <td>
                @if('admin' == $u->getRole())
                <span class="badge badge-ok">管理员</span>
                @else
                <span class="badge badge-off">普通用户</span>
                @endif
            </td>
            <td>
                @if($u->getEnabled())
                <span class="badge badge-ok">启用</span>
                @else
                <span class="badge badge-bad">已停用</span>
                @endif
            </td>
            <td class="muted">{{ $u->getLastLoginAt() == null ? '从未登录' : $u->getLastLoginAt() }}</td>
            <td>
                <div class="actions">
                    <button type="button" class="btn btn-sm"
                            onclick="toggleEdit({{ $u->getId() }})">编辑</button>
                    <form method="post" action="/admin/users/toggle-role" class="inline-form">
                        <input type="hidden" name="id" value="{{ $u->getId() }}">
                        <button type="submit" class="btn btn-sm">
                            @if('admin' == $u->getRole()) 降为普通 @else 升为管理员 @endif
                        </button>
                    </form>
                    <form method="post" action="/admin/users/toggle-enabled" class="inline-form">
                        <input type="hidden" name="id" value="{{ $u->getId() }}">
                        <button type="submit" class="btn btn-sm">
                            @if($u->getEnabled()) 停用 @else 启用 @endif
                        </button>
                    </form>
                    <form method="post" action="/admin/users/delete" class="inline-form"
                          onsubmit="return confirm('确认删除该账号？删除后他将无法进入管理后台。');">
                        <input type="hidden" name="id" value="{{ $u->getId() }}">
                        <button type="submit" class="btn btn-sm btn-danger">删除</button>
                    </form>
                </div>
            </td>
        </tr>
        {{-- 编辑行：可改姓名与角色。学工号作为身份标识不允许改（改了等于换人） --}}
        <tr id="edit-{{ $u->getId() }}" style="display:none;background:#f8fafc">
            <td colspan="6">
                <form method="post" action="/admin/users/update">
                    <input type="hidden" name="id" value="{{ $u->getId() }}">
                    <div class="row">
                        <div class="field grow">
                            <label>学工号（不可修改，身份标识）</label>
                            <input type="text" value="{{ $u->getStaffNo() }}" disabled>
                        </div>
                        <div class="field grow">
                            <label>姓名</label>
                            <input type="text" name="name" value="{{ $u->getName() }}"
                                   placeholder="未设置">
                        </div>
                        <div class="field" style="width:150px">
                            <label>角色</label>
                            <select name="role">
                                <option value="admin" @if('admin' == $u->getRole())selected@endif>管理员</option>
                                <option value="user" @if('user' == $u->getRole())selected@endif>普通用户</option>
                            </select>
                        </div>
                        <div class="field">
                            <button type="submit" class="btn btn-primary">保存</button>
                        </div>
                        <div class="field">
                            <button type="button" class="btn"
                                    onclick="toggleEdit({{ $u->getId() }})">取消</button>
                        </div>
                    </div>
                    <p class="muted" style="margin:8px 0 0;font-size:12px">
                        姓名与部门会在该用户下次登录时被统一身份认证返回的值覆盖（组织信息以认证系统为准）。
                    </p>
                </form>
            </td>
        </tr>
        @empty
        <tr><td colspan="6" class="muted">还没有任何账号。用户首次通过统一身份认证登录时会自动创建。</td></tr>
        @endforelse
        </tbody>
    </table>
    <p class="muted" style="margin:14px 0 0">
        提示：<b>最后一个可用管理员不允许删除</b>，否则在认证开启时就没人能进后台了。
    </p>
</div>

<script>
    /** 展开 / 收起某行的编辑表单 */
    function toggleEdit(id) {
        var row = document.getElementById('edit-' + id);
        row.style.display = (row.style.display === 'none' || row.style.display === '') ? 'table-row' : 'none';
    }
</script>
@endsection
