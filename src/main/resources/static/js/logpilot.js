/* LogPilot 前端交互：fetch 封装、采集/测试/AI 按钮状态、简易 Markdown 渲染 */

async function lpFetch(url, options) {
    const resp = await fetch(url, Object.assign({
        headers: { 'X-Requested-With': 'XMLHttpRequest' }
    }, options || {}));
    let data;
    try { data = await resp.json(); } catch (e) { throw new Error('HTTP ' + resp.status); }
    if (!data.ok) throw new Error(data.message || '操作失败');
    return data.data;
}

/* 按钮：异步执行并显示结果 */
function lpAction(btn, url, opts) {
    const original = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> 执行中...';
    lpFetch(url, Object.assign({ method: 'POST' }, opts))
        .then(data => {
            btn.innerHTML = '✓ 完成';
            const msg = typeof data === 'string' ? data : (data && data.message) || '操作成功';
            lpToast(msg, 'success');
            setTimeout(() => location.reload(), opts && opts.reloadDelay ? opts.reloadDelay * 1000 : 1200);
        })
        .catch(e => {
            btn.innerHTML = original;
            btn.disabled = false;
            lpToast(e.message, 'danger');
        });
    return false;
}

function lpToast(message, type) {
    let holder = document.getElementById('lp-toast-holder');
    if (!holder) {
        holder = document.createElement('div');
        holder.id = 'lp-toast-holder';
        holder.className = 'toast-container position-fixed top-0 end-0 p-3';
        holder.style.zIndex = 2000;
        document.body.appendChild(holder);
    }
    const el = document.createElement('div');
    el.className = 'toast align-items-center text-bg-' + (type || 'secondary');
    el.innerHTML = '<div class="d-flex"><div class="toast-body"></div>' +
        '<button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button></div>';
    el.querySelector('.toast-body').textContent = message;
    holder.appendChild(el);
    new bootstrap.Toast(el, { delay: 4000 }).show();
    el.addEventListener('hidden.bs.toast', () => el.remove());
}

/* 极简 Markdown 渲染（AI 输出）：标题/加粗/行内代码/代码块/列表/段落 */
function lpRenderMarkdown(text) {
    if (!text) return '';
    const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const lines = String(text).split(/\r?\n/);
    let html = [], inCode = false, inList = false;

    const closeList = () => { if (inList) { html.push('</ul>'); inList = false; } };
    const inline = s => esc(s)
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>');

    for (let line of lines) {
        if (line.trim().startsWith('```')) {
            closeList();
            if (inCode) { html.push('</code></pre>'); inCode = false; }
            else { html.push('<pre><code>'); inCode = true; }
            continue;
        }
        if (inCode) { html.push(esc(line) + '\n'); continue; }
        const t = line.trim();
        if (!t) { closeList(); continue; }
        if (/^#{1,4}\s/.test(t)) { closeList(); html.push('<h2>' + inline(t.replace(/^#{1,4}\s*/, '')) + '</h2>'); continue; }
        if (/^[-*]\s+/.test(t)) {
            if (!inList) { html.push('<ul class="mb-2">'); inList = true; }
            html.push('<li>' + inline(t.replace(/^[-*]\s+/, '')) + '</li>');
            continue;
        }
        if (/^\d+[.)]\s+/.test(t)) {
            if (!inList) { html.push('<ul class="mb-2">'); inList = true; }
            html.push('<li>' + inline(t.replace(/^\d+[.)]\s+/, '')) + '</li>');
            continue;
        }
        closeList();
        html.push('<p class="mb-2">' + inline(t) + '</p>');
    }
    closeList();
    if (inCode) html.push('</code></pre>');
    return html.join('');
}

/* AI 分析按钮 */
function lpAiAnalyze(btn, groupId) {
    const target = document.getElementById('ai-result');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> AI 分析中（约 10~60 秒）...';
    lpFetch('/errors/' + groupId + '/ai', { method: 'POST' })
        .then(text => {
            target.innerHTML = lpRenderMarkdown(text);
            target.classList.remove('d-none');
            btn.innerHTML = '✓ 分析完成，刷新查看';
            lpToast('AI 分析完成', 'success');
            setTimeout(() => location.reload(), 1500);
        })
        .catch(e => {
            btn.disabled = false;
            btn.innerHTML = '🤖 AI 根因分析';
            lpToast(e.message, 'danger');
        });
    return false;
}

/* 确认提交（删除等危险操作） */
function lpConfirm(form, message) {
    if (confirm(message || '确认执行该操作？')) form.submit();
    return false;
}
