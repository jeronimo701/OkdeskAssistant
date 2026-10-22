package ru.poscenter.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class AssistantService extends AccessibilityService {
    public static volatile AssistantService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Store store;
    private WindowManager windows;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private volatile boolean running, stopped, stepped;
    private volatile boolean inspectingSwitch;
    private volatile CountDownLatch nextStep;
    private volatile String lastIssue = "", progress = "Готов";
    private boolean collapsed = true;
    private TextView progressLabel;
    private TextView issueLabel;
    private boolean progressPanel;
    private final Runnable visibilityUpdate = this::updateVisibility;
    private static final Pattern ISSUE = Pattern.compile("^№\\s*(\\d{3,})\\s*$");

    @Override protected void onServiceConnected() {
        instance = this; store = new Store(this); windows = getSystemService(WindowManager.class);
        collapsed = store.prefs.getBoolean("collapsed", true);
        store.log("Служба подключена. API и сеть не используются.");
        createPanel(); updateVisibility();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (store == null) return;
        // Do not postpone an existing refresh indefinitely during a stream of UI events.
        if (!main.hasCallbacks(visibilityUpdate)) main.postDelayed(visibilityUpdate, 180);
    }

    @Override public void onInterrupt() { stop(); }
    @Override public void onDestroy() {
        stop(); worker.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (panel != null) { try { windows.removeView(panel); } catch (Exception ignored) {} }
        instance = null; super.onDestroy();
    }

    public void refreshPanel() { main.post(() -> { renderPanel(); updateVisibility(); }); }

    private void createPanel() {
        panel = Ui.column(this); int pad = Ui.dp(this, 8); panel.setPadding(pad, pad, pad, pad);
        panel.setBackground(Ui.background(Color.argb(242, 24, 37, 43), Color.argb(100, 185, 221, 226), Ui.dp(this, 20)));
        panel.setElevation(Ui.dp(this, 8));
        panelParams = new WindowManager.LayoutParams(Ui.dp(this, collapsed ? 56 : 260), -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.LEFT;
        panelParams.x = store.prefs.getInt("panelX", Ui.dp(this, 12));
        panelParams.y = store.prefs.getInt("panelY", Ui.dp(this, 330));
        renderPanel(); windows.addView(panel, panelParams); panel.setVisibility(View.GONE);
    }

    private void renderPanel() {
        if (panel == null) return;
        if (running && progressPanel && progressLabel != null) {
            progressLabel.setText(progress); return;
        }
        progressPanel = running; progressLabel = null; issueLabel = null;
        panel.removeAllViews();
        boolean compact = collapsed && !running;
        View handle;
        if (compact) {
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(R.drawable.ic_overlay);
            icon.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
            icon.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));
            handle = icon;
        } else {
            TextView title = Ui.text(this, running ? "Выполнение" : "Assistant", 15, true);
            if (!running) title.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_collapse, 0);
            title.setTextColor(Ui.ACCENT); handle = title;
        }
        handle.setContentDescription(compact ? "Развернуть панель Assistant; удерживайте и перемещайте" : running ? "Переместить панель выполнения" : "Свернуть панель Assistant; удерживайте и перемещайте");
        handle.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int startX, startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { x = e.getRawX(); y = e.getRawY(); startX = panelParams.x; startY = panelParams.y; moved = false; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = e.getRawX() - x, dy = e.getRawY() - y;
                    if (Math.abs(dx) + Math.abs(dy) > Ui.dp(AssistantService.this, 8)) moved = true;
                    if (moved) { panelParams.x = startX + Math.round(dx); panelParams.y = startY + Math.round(dy); clampPanel(); windows.updateViewLayout(panel, panelParams); }
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (!moved && !running) { collapsed = !collapsed; store.prefs.edit().putBoolean("collapsed", collapsed).apply(); renderPanel(); }
                    else store.prefs.edit().putInt("panelX", panelParams.x).putInt("panelY", panelParams.y).apply();
                    v.performClick(); return true;
                }
                return false;
            }
        });
        panel.addView(handle);
        if (running) {
            progressLabel = Ui.text(this, progress, 13, false);
            progressLabel.setLines(3); progressLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
            panel.addView(progressLabel, new LinearLayout.LayoutParams(-1, Ui.dp(this, 76)));
            if (stepped) panel.addView(Ui.button(this, "Следующий шаг", () -> { if (nextStep != null) nextStep.countDown(); }));
            panel.addView(Ui.button(this, "■ Остановить", this::stop));
        } else if (!collapsed) {
            issueLabel = Ui.text(this, lastIssue.trim().isEmpty() ? "Откройте карточку заявки" : "Заявка №" + lastIssue, 13, false);
            panel.addView(issueLabel);
            ScrollView scroll = new ScrollView(this); LinearLayout list = Ui.column(this);
            JSONArray scenarios = store.panelScenarios();
            for (int i = 0; i < scenarios.length(); i++) {
                JSONObject scenario = scenarios.optJSONObject(i);
                Button button = Ui.button(this, scenario.optString("title"), () -> confirm(scenario));
                button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                button.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
                try { int accent = Color.parseColor(scenario.optString("color", "#3f8f72"));
                    button.setBackground(Ui.background(Color.argb(65, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(160, Color.red(accent), Color.green(accent), Color.blue(accent)), Ui.dp(this, 8))); } catch (Exception ignored) {}
                list.addView(button);
            }
            scroll.addView(list); panel.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 260)));
            LinearLayout tools = Ui.column(this);
            tools.setVisibility(View.GONE);
            Button more = Ui.button(this, "▸ Дополнительно", () -> {});
            more.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            more.setOnClickListener(v -> {
                boolean open = tools.getVisibility() != View.VISIBLE;
                tools.setVisibility(open ? View.VISIBLE : View.GONE);
                more.setText((open ? "▾ " : "▸ ") + "Дополнительно");
                panel.post(() -> { clampPanel(); if (panel.isAttachedToWindow()) windows.updateViewLayout(panel, panelParams); });
            });
            panel.addView(more);
            tools.addView(Ui.row(this, Ui.button(this, "Настройки", () -> {
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }), Ui.button(this, "Скрыть", () -> { store.prefs.edit().putBoolean("panel", false).apply(); updateVisibility(); })));
            tools.addView(Ui.button(this, "Диагностика экрана", this::dumpTree));
            panel.addView(tools);
            if (!"Готов".equals(progress)) panel.addView(Ui.text(this, progress, 12, false));
        }
        if (panelParams != null) {
            int screen = getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(Ui.dp(this, collapsed && !running ? 56 : 260), Math.max(Ui.dp(this, 56), screen - Ui.dp(this, 16)));
            // Keep the selected edge stationary when switching between icon and panel.
            if (store.prefs.getBoolean("collapseRight", false)) panelParams.x += panelParams.width - width;
            panelParams.width = width;
            clampPanel(); if (panel.isAttachedToWindow()) windows.updateViewLayout(panel, panelParams);
            store.prefs.edit().putInt("panelX", panelParams.x).putInt("panelY", panelParams.y).apply();
        }
    }

    private void clampPanel() {
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        panelParams.x = Math.max(0, Math.min(panelParams.x, w - panelParams.width));
        int height = running ? Ui.dp(this, stepped ? 260 : 200) : collapsed ? Ui.dp(this, 56) : Math.max(panel.getMeasuredHeight(), Ui.dp(this, 450));
        panelParams.y = Math.max(Ui.dp(this, 28), Math.min(panelParams.y, h - Math.min(height, h - Ui.dp(this, 70))));
    }

    private void updateVisibility() {
        if (panel == null) return;
        main.removeCallbacks(visibilityUpdate);
        main.postDelayed(visibilityUpdate, 800);
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active == null) return;
        boolean target = Store.TARGET.contentEquals(active.getPackageName());
        if (target) {
            try (Tree current = new Tree(active)) {
                String found = current.issue();
                if (!found.isEmpty()) lastIssue = found;
                else if (!running && current.hasText("Заявки")) lastIssue = "";
            }
        } else active.recycle();
        if (issueLabel != null) {
            String caption = lastIssue.trim().isEmpty() ? "Откройте карточку заявки" : "Заявка №" + lastIssue;
            if (!caption.contentEquals(issueLabel.getText())) issueLabel.setText(caption);
        }
        panel.setVisibility(target && !inspectingSwitch && store.prefs.getBoolean("panel", true) ? View.VISIBLE : View.GONE);
        if (running && !target) stop();
    }

    private void confirm(JSONObject scenario) {
        if (running) return;
        try (Tree tree = tree(true)) {
            String issue = tree.issue();
            if (issue.trim().isEmpty() && tree.hasText("Комментарии")) issue = lastIssue;
            if (issue.trim().isEmpty()) throw new Exception("Сначала откройте карточку заявки");
            if (!store.allowedIssue().trim().isEmpty() && !store.allowedIssue().equals(issue)) throw new Exception("Разрешена только тестовая заявка " + store.allowedIssue());
            final String id = issue; lastIssue = id;
            progressPanel = false; progressLabel = null; issueLabel = null;
            panel.removeAllViews();
            panel.addView(Ui.text(this, "Заявка №" + id, 17, true));
            boolean sendsComment = false;
            JSONArray steps = scenario.optJSONArray("steps");
            if (steps != null) for (int i = 0; i < steps.length(); i++) if (steps.optJSONObject(i).optString("kind").equals("send")) sendsComment = true;
            String visibility = sendsComment ? (scenario.optBoolean("public", true) ? "ОТКРЫТЫЙ комментарий" : "СКРЫТЫЙ комментарий") : "Без отправки комментария";
            panel.addView(Ui.text(this, scenario.optString("title") + "\n" + visibility + "\nСтатус: " + (scenario.optString("status").isEmpty() ? "без изменения" : scenario.optString("status")), 14, false));
            if (!scenario.optString("assignee").trim().isEmpty()) panel.addView(Ui.text(this, "Передать: " + scenario.optString("assignee"), 14, true));
            String text = scenario.optString("text"); panel.addView(Ui.text(this, text.length() > 240 ? text.substring(0, 240) + "…" : text, 13, false));
            if (sendsComment) panel.addView(Ui.text(this, store.prefs.getBoolean("allowRepeatComments", true)
                ? "Каждый запуск отправляет новый комментарий, даже если такой текст уже есть в заявке."
                : "Повторные комментарии запрещены в настройках Assistant.", 13, false));
            panel.addView(Ui.button(this, "Подготовить текст", () -> start(scenario, id, true, false)));
            panel.addView(Ui.button(this, "Выполнить", () -> start(scenario, id, false, false)));
            panel.addView(Ui.button(this, "Пошагово", () -> start(scenario, id, false, true)));
            panel.addView(Ui.button(this, "Отмена", this::renderPanel));
            clampPanel(); windows.updateViewLayout(panel, panelParams);
        } catch (Exception e) { report("Не запущено: " + e.getMessage()); }
    }

    private void start(JSONObject scenario, String issue, boolean prepare, boolean debug) {
        if (running) return;
        running = true; stopped = false; stepped = debug; progress = "Запуск №" + issue; renderPanel();
        worker.execute(() -> {
            boolean completed = false;
            try {
                Engine engine = new Engine(scenario, issue, prepare); engine.run();
                if (!prepare) store.recordUse(scenario.optString("id"));
                report(prepare ? "Текст подготовлен. Отправки не было." : "Сценарий выполнен, результат проверен.");
                completed = true;
            } catch (Exception e) { report("Остановлено: " + (e.getMessage() == null ? e.toString() : e.getMessage())); }
            finally {
                boolean collapseAfter = completed;
                main.post(() -> {
                    running = false; stepped = false; nextStep = null;
                    if (collapseAfter) { collapsed = true; store.prefs.edit().putBoolean("collapsed", true).apply(); }
                    renderPanel();
                });
            }
        });
    }

    private void stop() { stopped = true; if (nextStep != null) nextStep.countDown(); }
    private void dumpTree() {
        try (Tree tree = tree(true)) {
            JSONArray data = new JSONArray();
            for (AccessibilityNodeInfo node : tree.nodes) {
                data.put(new JSONObject().put("text", String.valueOf(node.getText()))
                    .put("class", String.valueOf(node.getClassName())).put("desc", String.valueOf(node.getContentDescription()))
                    .put("id", String.valueOf(node.getViewIdResourceName())).put("visible", visible(node))
                    .put("checked", node.isChecked()).put("checkable", node.isCheckable())
                    .put("clickable", node.isClickable()).put("actions", node.getActions()).put("bounds", bounds(node).toShortString()));
            }
            java.nio.file.Files.write(new java.io.File(getFilesDir(), "screen-tree.json").toPath(), data.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            report("Диагностика сохранена локально");
            startActivity(new Intent(this, MainActivity.class).setAction("ru.poscenter.assistant.DIAGNOSTICS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Exception e) { report(e.getMessage()); }
    }
    private void report(String message) { progress = message; if (store != null) store.log(message); main.post(() -> { renderPanel(); android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show(); }); }
    private void progress(String message) { progress = message; store.log(message); main.post(this::renderPanel); }
    private void check() throws Exception {
        if (stopped || Thread.currentThread().isInterrupted()) throw new Exception("Остановлено пользователем или Okdesk ушёл с экрана");
        try (Tree ignored = tree(true)) { }
    }
    private void sleep(long millis) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) { check(); Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis()))); }
    }

    private final class Engine {
        final JSONObject scenario;
        final String issue, text;
        final long timeout;
        final boolean prepare, allowRepeats;
        boolean sent, privacyVerified, sendAttempted;
        Engine(JSONObject scenario, String issue, boolean prepare) {
            this.scenario = scenario; this.issue = issue; this.prepare = prepare;
            allowRepeats = store.prefs.getBoolean("allowRepeatComments", true);
            text = scenario.optString("text"); timeout = scenario.optInt("timeout", 12) * 1000L;
        }

        void guard() throws Exception {
            check();
            try (Tree tree = tree(true)) {
                String current = tree.issue();
                if (!current.trim().isEmpty() && !issue.equals(current)) throw new Exception("Открылась другая заявка. Действия отменены");
                if (!lastIssue.equals(issue)) throw new Exception("Потеряна привязка к заявке");
            }
        }

        void run() throws Exception {
            guard();
            if (text.trim().isEmpty() && scenario.getJSONArray("steps").toString().contains("\"send\"")) throw new Exception("Пустой текст ответа");
            store.log("№" + issue + " сценарий «" + scenario.optString("title") + "», " + (prepare ? "подготовка" : "выполнение"));
            JSONArray steps = scenario.getJSONArray("steps");
            if (!prepare && !allowRepeats && !store.delivery(issue, scenario).equals("new")) {
                for (int i = 0; i < steps.length(); i++) if (steps.getJSONObject(i).optString("kind").equals("send"))
                    throw new Exception("Этот ответ уже отправлялся или его отправка не подтверждена. Проверьте ленту. Для повтора включите «Разрешать повторные комментарии» в настройках");
            }
            for (int i = 0; i < steps.length(); i++) {
                guard(); JSONObject step = steps.getJSONObject(i); String kind = step.getString("kind"), value = step.optString("value");
                if (prepare && (kind.equals("send") || kind.equals("status") || kind.equals("assign"))) return;
                progress((i + 1) + "/" + steps.length() + " · " + label(kind));
                if (stepped) {
                    nextStep = new CountDownLatch(1);
                    while (!nextStep.await(150, TimeUnit.MILLISECONDS)) guard(); guard();
                    nextStep = null;
                }
                switch (kind) {
                    case "comments": comments(); break;
                    case "input": input(); break;
                    case "privacy": privacy(); break;
                    case "send": send(); break;
                    case "status": status(); break;
                    case "assign": assign(value.trim().isEmpty() ? scenario.optString("assignee") : value); break;
                    case "click": clickSelector(value); break;
                    case "wait": await(value, false); break;
                    case "gone": await(value, true); break;
                    case "scroll": scroll(value.equalsIgnoreCase("up")); break;
                    case "pause": sleep(Math.max(100, Math.min(30000, Long.parseLong(value)))); break;
                    case "back": performGlobalAction(GLOBAL_ACTION_BACK); sleep(500); break;
                    default: throw new Exception("Неизвестный шаг");
                }
            }
        }

        void comments() throws Exception {
            try (Tree tree = tree(true)) {
                if (tree.hasText("Комментарии")) {
                    // An open comments screen may still be loading its editor.
                    await("class:android.widget.EditText", false);
                    return;
                }
                if (!issue.equals(tree.issue())) throw new Exception("Не открыта карточка заявки №" + issue);
                List<AccessibilityNodeInfo> direct = tree.match("Комментарии");
                if (direct.size() == 1) click(direct.get(0));
                else {
                    // The original WebView exposes this icon without a label. Its bounds come from the live tree.
                    Rect area = tree.bounds(); List<AccessibilityNodeInfo> icons = new ArrayList<>();
                    for (AccessibilityNodeInfo node : tree.nodes) {
                        Rect r = bounds(node);
                        if (isImage(node) && visible(node) && r.centerY() > area.top + area.height() * .88f
                            && Math.abs(r.centerX() - area.centerX()) < area.width() * .14f) icons.add(node);
                    }
                    if (icons.size() != 1) throw new Exception("Неоднозначная иконка комментариев. Откройте комментарии вручную");
                    click(icons.get(0));
                }
            }
            await("class:android.widget.EditText", false);
            sleep(300);
        }

        void input() throws Exception {
            try (Tree tree = tree(true)) {
                AccessibilityNodeInfo editor = tree.editor();
                if (editor == null) throw new Exception("Поле комментария не найдено");
                String draft = String.valueOf(editor.getText() == null ? "" : editor.getText());
                if (!draft.trim().isEmpty() && !normalize(draft).equals(normalize(text))) throw new Exception("В поле уже есть другой черновик. Он не будет затёрт");
                editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw new Exception("Okdesk не разрешил ввод через Accessibility");
            }
            waitUntil(() -> { try (Tree tree = tree(true)) { AccessibilityNodeInfo editor = tree.editor(); return editor != null && normalize(String.valueOf(editor.getText())).equals(normalize(text)); } }, "Текст не появился в поле");
            sleep(350);
        }

        void privacy() throws Exception {
            if (sent) return;
            boolean expected = scenario.optBoolean("public", true);
            sleep(350);
            Rect switchBounds = null;
            try (Tree tree = tree(true)) {
                List<AccessibilityNodeInfo> checks = new ArrayList<>();
                for (AccessibilityNodeInfo node : tree.nodes) if (visible(node) && node.isCheckable()) checks.add(node);
                if (checks.size() == 1) {
                    AccessibilityNodeInfo toggle = checks.get(0);
                    if (toggle.isChecked() != expected) click(toggle);
                } else {
                    if (!tree.hasText("Комментарии") || tree.editor() == null || !tree.hasText("Публичный")) throw new Exception("Переключатель публичности не найден. Отправка заблокирована");
                    List<AccessibilityNodeInfo> switches = tree.match("class:android.widget.Switch");
                    if (switches.size() != 1) throw new Exception("Неоднозначный переключатель публичности. Отправка заблокирована");
                    switchBounds = bounds(switches.get(0));
                }
            }
            if (switchBounds != null) {
                boolean current = publicSwitchState(switchBounds);
                if (current != expected) {
                    gesture(switchBounds.centerX(), switchBounds.centerY(), switchBounds.centerX(), switchBounds.centerY(), 70);
                    sleep(450);
                } else sleep(400);
                try (Tree tree = tree(true)) {
                    List<AccessibilityNodeInfo> switches = tree.match("class:android.widget.Switch");
                    if (switches.size() != 1) throw new Exception("Переключатель публичности исчез");
                    switchBounds = bounds(switches.get(0));
                }
                if (publicSwitchState(switchBounds) != expected) throw new Exception("Состояние публичности не подтверждено. Отправка заблокирована");
                privacyVerified = true; progress(expected ? "Проверено: открытый комментарий" : "Проверено: скрытый комментарий"); return;
            }
            waitUntil(() -> { try (Tree tree = tree(true)) {
                List<AccessibilityNodeInfo> nodes = new ArrayList<>();
                for (AccessibilityNodeInfo node : tree.nodes) if (visible(node) && node.isCheckable()) nodes.add(node);
                return nodes.size() == 1 && nodes.get(0).isChecked() == expected;
            } }, "Не подтверждено состояние публичности");
            privacyVerified = true;
        }

        void send() throws Exception {
            guard();
            if (sent) { progress("Повторная отправка пропущена"); return; }
            if (sendAttempted) throw new Exception("В одном запуске разрешена только одна отправка");
            if (!privacyVerified) throw new Exception("Перед отправкой нужен шаг проверки публичности");
            privacy();
            try (Tree tree = tree(true)) {
                AccessibilityNodeInfo editor = tree.editor();
                if (editor == null || !normalize(String.valueOf(editor.getText())).equals(normalize(text))) throw new Exception("Текст перед отправкой изменился");
                if (!allowRepeats && tree.commentText().contains(normalize(text)))
                    throw new Exception("Такой комментарий уже виден в заявке. Для повтора включите «Разрешать повторные комментарии» в настройках");
                Rect field = bounds(editor);
                List<AccessibilityNodeInfo> buttons = tree.match("Отправить");
                AccessibilityNodeInfo send;
                if (buttons.size() == 1) send = buttons.get(0);
                else {
                    List<AccessibilityNodeInfo> icons = new ArrayList<>();
                    for (AccessibilityNodeInfo node : tree.nodes) {
                        Rect r = bounds(node);
                        if (visible(node) && isImage(node) && r.left >= field.right - Ui.dp(AssistantService.this, 6)
                            && Math.abs(r.centerY() - field.centerY()) < Ui.dp(AssistantService.this, 40)) icons.add(node);
                    }
                    if (icons.size() != 1) throw new Exception("Кнопка отправки не определена однозначно");
                    send = icons.get(0);
                }
                sendAttempted = true;
                store.delivery(issue, scenario, "uncertain");
                click(send);
            }
            waitUntil(() -> { try (Tree tree = tree(true)) {
                AccessibilityNodeInfo editor = tree.editor();
                return editor != null && (editor.getText() == null || editor.getText().toString().trim().isEmpty())
                    && tree.commentText().contains(normalize(text));
            } }, "Результат отправки не подтверждён. Автоповтор запрещён. Проверьте комментарии");
            store.delivery(issue, scenario, "confirmed"); sent = true;
            progress("Комментарий отправлен и найден в ленте");
        }

        void status() throws Exception {
            String target = scenario.optString("status").trim();
            if (target.trim().isEmpty()) return;
            String action = store.statusAction(target);
            card();
            try (Tree tree = tree(true)) {
                List<AccessibilityNodeInfo> statusNodes = tree.statusNodes();
                if (statusNodes.size() != 1) throw new Exception("Неоднозначное управление статусом: найдено " + statusNodes.size());
                AccessibilityNodeInfo current = statusNodes.get(0);
                if (normalize(String.valueOf(current.getText())).equals(normalize(target))) { progress("Статус уже установлен"); return; }
                Rect r = bounds(current);
                gesture(r.centerX(), r.centerY(), r.centerX(), r.centerY(), 70);
            }
            sleep(1000);
            // Ionic popovers can expose unscaled bounds; verify their visible frame before tapping.
            Rect previous = new Rect(); int stable = 0; Rect chosen = null;
            long end = System.currentTimeMillis() + timeout;
            while (System.currentTimeMillis() < end) {
                guard();
                try (Tree tree = tree(true)) {
                    List<AccessibilityNodeInfo> buttons = tree.statusOptions(action);
                    if (buttons.size() > 1) throw new Exception("Несколько пунктов статуса: " + action);
                    if (buttons.size() == 1) {
                        Rect r = bounds(buttons.get(0));
                        stable = r.equals(previous) ? stable + 1 : 0;
                        previous.set(r);
                        if (stable >= 3) { chosen = new Rect(r); break; }
                    } else stable = 0;
                }
                sleep(180);
            }
            if (chosen == null) throw new Exception("Пункт статуса недоступен: " + action);
            store.log("Выбор статуса: " + action + " " + chosen.toShortString());
            guard(); Rect semanticMenu;
            try (Tree tree = tree(true)) {
                List<AccessibilityNodeInfo> buttons = tree.statusOptions(action);
                if (buttons.size() != 1) throw new Exception("Пункт статуса изменился");
                chosen = bounds(buttons.get(0)); semanticMenu = tree.statusMenuBounds(buttons.get(0));
            }
            final Rect itemBounds = chosen, menuBounds = semanticMenu;
            android.graphics.Point point = inspectScreen(bitmap -> popupPoint(bitmap, menuBounds, itemBounds));
            guard(); gesture(point.x, point.y, point.x, point.y, 80);
            sleep(650);
            waitUntil(() -> { try (Tree tree = tree(true)) {
                return tree.statusConfirmed(issue, target) || !tree.match("re:(?iu)сменить\\s+статус").isEmpty();
            } }, "Не открылась форма подтверждения статуса");
            boolean confirm;
            try (Tree tree = tree(true)) {
                confirm = !tree.statusConfirmed(issue, target);
                if (confirm) {
                    Rect area = tree.bounds(); boolean correctHeader = false;
                    for (AccessibilityNodeInfo node : tree.nodes) if (visible(node) && node.getText() != null) {
                        String caption = normalize(node.getText().toString()); Rect r = bounds(node);
                        if ((caption.equals(normalize(action)) || caption.equals(normalize(target)))
                            && r.centerY() < area.top + area.height() * .2f) correctHeader = true;
                    }
                    if (!correctHeader) throw new Exception("Форма подтверждения относится к другому статусу");
                    AccessibilityNodeInfo editor = tree.editor();
                    if (editor != null && editor.getText() != null && !editor.getText().toString().trim().isEmpty())
                        throw new Exception("В форме смены статуса есть комментарий. Автоматическое подтверждение отменено");
                }
            }
            if (confirm) {
                sleep(400);
                try (Tree tree = tree(true)) {
                    List<AccessibilityNodeInfo> buttons = tree.match("re:(?iu)сменить\\s+статус");
                    if (buttons.size() != 1 || !buttons.get(0).isEnabled()) throw new Exception("Неоднозначная или недоступная кнопка подтверждения статуса");
                    Rect r = bounds(buttons.get(0));
                    gesture(r.centerX(), r.centerY(), r.centerX(), r.centerY(), 80);
                    progress("Подтверждение смены статуса нажато");
                }
            }
            waitUntil(() -> { try (Tree tree = tree(true)) {
                return tree.statusConfirmed(issue, target);
            } }, "Статус не подтверждён. Возможно, Okdesk запросил дополнительные данные. Комментарий повторно не отправляется");
            progress("Статус подтверждён: " + target);
        }

        void card() throws Exception {
            for (int i = 0; i < 3; i++) {
                boolean comments;
                try (Tree tree = tree(true)) { comments = tree.hasText("Комментарии"); }
                if (!comments) break;
                performGlobalAction(GLOBAL_ACTION_BACK); sleep(600);
            }
            waitUntil(() -> { try (Tree tree = tree(true)) {
                return issue.equals(tree.issue()) && !tree.hasText("Комментарии");
            } }, "Не удалось вернуться к карточке заявки");
            sleep(500);
        }

        void assign(String name) throws Exception {
            String target = normalize(name);
            if (target.isEmpty() || target.equals("Без ответственного")) throw new Exception("Укажите точное имя сотрудника");
            card();
            boolean opened = false;
            long end = System.currentTimeMillis() + timeout;
            for (int attempt = 0; attempt < 12 && System.currentTimeMillis() < end; attempt++) {
                guard();
                try (Tree tree = tree(true)) {
                    if (tree.assignedTo(target)) { progress("Ответственный уже установлен: " + target); return; }
                    List<AccessibilityNodeInfo> labels = tree.match("Ответственный");
                    if (labels.size() > 1) throw new Exception("Неоднозначный блок ответственного");
                    if (labels.size() == 1) {
                        Rect label = bounds(labels.get(0)), area = tree.bounds();
                        List<AccessibilityNodeInfo> arrows = new ArrayList<>();
                        for (AccessibilityNodeInfo node : tree.nodes) {
                            Rect r = bounds(node);
                            if (visible(node) && isImage(node) && r.centerX() > area.left + area.width() * .8f
                                && r.centerY() >= label.top && r.centerY() <= label.bottom + Ui.dp(AssistantService.this, 45)) arrows.add(node);
                        }
                        if (arrows.size() != 1) throw new Exception("Кнопка выбора ответственного не определена однозначно");
                        click(arrows.get(0)); opened = true; break;
                    }
                }
                scroll(false);
            }
            if (!opened) throw new Exception("Блок ответственного не найден");
            waitUntil(() -> { try (Tree tree = tree(true)) {
                return tree.editor() != null && tree.hasText("Без ответственного");
            } }, "Список сотрудников не открылся");
            try (Tree tree = tree(true)) {
                AccessibilityNodeInfo search = tree.editor();
                Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target);
                search.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                if (!search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw new Exception("Не удалось ввести имя в поиск сотрудников");
            }
            waitUntil(() -> { try (Tree tree = tree(true)) {
                AccessibilityNodeInfo search = tree.editor();
                return search != null && normalize(String.valueOf(search.getText())).equals(target);
            } }, "Имя не появилось в поиске");
            sleep(700);
            clickSelector(target);
            waitUntil(() -> { try (Tree tree = tree(true)) {
                return issue.equals(tree.issue()) && tree.editor() == null && tree.assignedTo(target);
            } }, "Передача не подтверждена. Проверьте ответственного; комментарий повторно не отправляется");
            progress("Заявка передана: " + target);
        }

        void clickSelector(String selector) throws Exception {
            if (selector.trim().isEmpty()) throw new Exception("Укажите селектор элемента");
            waitUntil(() -> { try (Tree tree = tree(true)) { return !tree.match(selector).isEmpty(); } }, "Элемент не найден: " + selector);
            try (Tree tree = tree(true)) {
                List<AccessibilityNodeInfo> matches = tree.match(selector);
                if (matches.size() != 1) throw new Exception("Неоднозначный селектор: " + selector + ", совпадений: " + matches.size());
                click(matches.get(0));
            }
            sleep(350);
        }

        void await(String selector, boolean gone) throws Exception {
            if (selector.trim().isEmpty()) throw new Exception("Укажите селектор ожидания");
            waitUntil(() -> { try (Tree tree = tree(true)) { return tree.match(selector).isEmpty() == gone; } }, "Тайм-аут ожидания: " + selector);
        }

        void scroll(boolean up) throws Exception {
            try (Tree tree = tree(true)) {
                for (AccessibilityNodeInfo node : tree.nodes) if (node.isScrollable() && visible(node)) {
                    if (node.performAction(up ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { sleep(350); return; }
                }
                Rect r = tree.bounds();
                gesture(r.centerX(), r.top + r.height() * (up ? .35f : .72f), r.centerX(), r.top + r.height() * (up ? .72f : .35f), 450);
            }
            sleep(400);
        }

        void waitUntil(Condition test, String error) throws Exception {
            long end = System.currentTimeMillis() + timeout;
            do { guard(); if (test.test()) return; sleep(250); } while (System.currentTimeMillis() < end);
            throw new Exception(error);
        }
    }

    @FunctionalInterface private interface Condition { boolean test() throws Exception; }
    @FunctionalInterface private interface ScreenCheck<T> { T read(android.graphics.Bitmap bitmap) throws Exception; }

    private boolean publicSwitchState(Rect rect) throws Exception {
        return inspectScreen(bitmap -> {
            if (rect.left < 0 || rect.top < 0 || rect.right > bitmap.getWidth() || rect.bottom > bitmap.getHeight()) throw new Exception("Переключатель за границами экрана");
            int green = 0, gray = 0, total = 0;
            for (int y = rect.top + 2; y < rect.bottom - 2; y += 2) for (int x = rect.left + 2; x < rect.right - 2; x += 2) {
                int color = bitmap.getPixel(x, y), r = Color.red(color), g = Color.green(color), b = Color.blue(color);
                if (g > 75 && g > r * 1.25 && g > b * 1.1) green++;
                if (r > 70 && r < 210 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 25) gray++;
                total++;
            }
            if (total > 50 && green > total * .12) return true;
            if (total > 50 && green < total * .01 && gray > total * .04) return false;
            throw new Exception("Вид переключателя публичности не распознан. Отправка заблокирована");
        });
    }

    private android.graphics.Point popupPoint(android.graphics.Bitmap bitmap, Rect semantic, Rect item) throws Exception {
        int x = semantic.right - 20, y = semantic.top + 40;
        if (x < 0 || y < 0 || x >= bitmap.getWidth() || y >= bitmap.getHeight() || !popupWhite(bitmap.getPixel(x, y))) throw new Exception("Границы меню статусов не распознаны");
        int left = x, right = x, top = y, bottom = y;
        while (left > 0 && popupWhite(bitmap.getPixel(left - 1, y))) left--;
        while (right + 1 < bitmap.getWidth() && popupWhite(bitmap.getPixel(right + 1, y))) right++;
        while (top > 0 && popupWhite(bitmap.getPixel(x, top - 1))) top--;
        while (bottom + 1 < bitmap.getHeight() && popupWhite(bitmap.getPixel(x, bottom + 1))) bottom++;
        float sx = (right + 1f - left) / semantic.width(), sy = (bottom + 1f - top) / semantic.height();
        if (sx < .8f || sx > 1.5f || sy < .8f || sy > 1.5f || Math.abs(sx - sy) > .08f) throw new Exception("Масштаб меню статусов не определён однозначно");
        int px = Math.round(left + (item.centerX() - semantic.left) * sx);
        int py = Math.round(top + (item.centerY() - semantic.top) * sy);
        if (px <= left || px >= right || py <= top || py >= bottom) throw new Exception("Пункт статуса вне меню");
        store.log("Масштаб меню: " + sx + "/" + sy + ", нажатие " + px + "," + py);
        return new android.graphics.Point(px, py);
    }

    private static boolean popupWhite(int color) {
        return Color.red(color) > 235 && Color.green(color) > 235 && Color.blue(color) > 235;
    }

    private <T> T inspectScreen(ScreenCheck<T> inspect) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 30) throw new Exception("Для визуальной проверки Okdesk нужен Android 11 или новее");
        check(); CountDownLatch latch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        String[] error = { "Не удалось проверить экран Okdesk" };
        main.post(() -> {
            boolean windowCapture = android.os.Build.VERSION.SDK_INT >= 34;
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active == null || !Store.TARGET.contentEquals(active.getPackageName())) {
                if (active != null) active.recycle(); latch.countDown(); return;
            }
            int windowId = active.getWindowId(); Rect windowBounds = new Rect();
            AccessibilityWindowInfo window = active.getWindow();
            if (window != null) { window.getBoundsInScreen(windowBounds); window.recycle(); }
            active.recycle();
            if (!windowCapture) { inspectingSwitch = true; panel.setVisibility(View.GONE); }
            main.postDelayed(() -> { try { TakeScreenshotCallback callback = new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult screenshot) {
                android.hardware.HardwareBuffer buffer = screenshot.getHardwareBuffer();
                android.graphics.Bitmap hardware = null, bitmap = null;
                try {
                    hardware = android.graphics.Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (hardware == null) return;
                    bitmap = hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                    // Window screenshots exclude our overlay and use window-local coordinates.
                    if (bitmap != null && windowCapture && !windowBounds.isEmpty()) {
                        android.graphics.Bitmap screen = android.graphics.Bitmap.createBitmap(getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels, android.graphics.Bitmap.Config.ARGB_8888);
                        new android.graphics.Canvas(screen).drawBitmap(bitmap, null, windowBounds, null);
                        bitmap.recycle(); bitmap = screen;
                    }
                    if (bitmap != null) result.set(inspect.read(bitmap));
                } catch (Exception e) { error[0] = e.getMessage();
                } finally {
                    if (bitmap != null) bitmap.recycle(); if (hardware != null) hardware.recycle(); buffer.close();
                    inspectingSwitch = false; main.post(visibilityUpdate); latch.countDown();
                }
            }
            @Override public void onFailure(int code) { error[0] = "Android не разрешил проверку переключателя: " + code; inspectingSwitch = false; main.post(visibilityUpdate); latch.countDown(); }
        };
            if (windowCapture) takeScreenshotOfWindow(windowId, getMainExecutor(), callback);
            else takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), callback);
        } catch (RuntimeException e) { error[0] = "Нет доступа к снимку переключателя. Переподключите службу Assistant"; inspectingSwitch = false; main.post(visibilityUpdate); latch.countDown(); } }, windowCapture ? 0 : 180);
        });
        if (!latch.await(4, TimeUnit.SECONDS)) {
            inspectingSwitch = false; main.post(visibilityUpdate); throw new Exception("Время проверки экрана истекло");
        }
        if (result.get() == null) throw new Exception(error[0]);
        check(); return result.get();
    }

    private void click(AccessibilityNodeInfo node) throws Exception {
        check();
        if (!visible(node)) throw new Exception("Элемент вне экрана");
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        Rect r = bounds(node); gesture(r.centerX(), r.centerY(), r.centerX(), r.centerY(), 70);
    }

    private void gesture(float x1, float y1, float x2, float y2, long duration) throws Exception {
        check(); CountDownLatch latch = new CountDownLatch(1); boolean[] success = { false };
        Path path = new Path(); path.moveTo(x1, y1); if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        main.post(() -> {
            panelParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windows.updateViewLayout(panel, panelParams);
            Runnable restoreTouch = () -> {
                panelParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                if (panel.isAttachedToWindow()) windows.updateViewLayout(panel, panelParams);
            };
            main.postDelayed(() -> {
                boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription result) { restoreTouch.run(); success[0] = true; latch.countDown(); }
                    @Override public void onCancelled(GestureDescription result) { restoreTouch.run(); latch.countDown(); }
                }, main);
                if (!accepted) { restoreTouch.run(); latch.countDown(); }
            }, 100);
        });
        if (Looper.myLooper() == Looper.getMainLooper()) throw new Exception("Жест запущен в UI-потоке");
        if (!latch.await(3, TimeUnit.SECONDS) || !success[0]) throw new Exception("Android не выполнил жест");
    }

    private Tree tree(boolean required) throws Exception {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && Store.TARGET.contentEquals(active.getPackageName())) return new Tree(active);
        if (active != null) active.recycle();
        if (required) throw new Exception("Оригинальный Okdesk не на переднем плане");
        return null;
    }

    private static Rect bounds(AccessibilityNodeInfo node) { Rect rect = new Rect(); node.getBoundsInScreen(rect); return rect; }
    private static boolean visible(AccessibilityNodeInfo node) { Rect r = bounds(node); return node.isVisibleToUser() && !r.isEmpty() && r.right > 0 && r.bottom > 0; }
    private static boolean isImage(AccessibilityNodeInfo node) { return node.getClassName() != null && node.getClassName().toString().endsWith("Image"); }
    private static String normalize(String text) { return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim(); }
    private static String label(String kind) {
        switch (kind) {
            case "comments": return "Открываю комментарии";
            case "input": return "Ввожу текст";
            case "privacy": return "Проверяю публичность";
            case "send": return "Отправляю и проверяю";
            case "status": return "Меняю статус";
            case "assign": return "Передаю заявку сотруднику";
            default: return kind;
        }
    }

    private static final class Tree implements AutoCloseable {
        final List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        final AccessibilityNodeInfo root;
        Tree(AccessibilityNodeInfo root) { this.root = root; collect(root, 0); }
        void collect(AccessibilityNodeInfo node, int depth) {
            nodes.add(node); if (depth >= 60 || nodes.size() > 2500) return;
            for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) collect(child, depth + 1); }
        }
        Rect bounds() { return AssistantService.bounds(root); }
        String issue() {
            for (AccessibilityNodeInfo node : nodes) {
                if (!visible(node) || node.getText() == null) continue;
                java.util.regex.Matcher matcher = ISSUE.matcher(normalize(node.getText().toString()));
                if (matcher.matches()) return matcher.group(1);
            }
            return "";
        }
        boolean hasText(String value) { return !match(value).isEmpty(); }
        List<AccessibilityNodeInfo> statusNodes() {
            List<AccessibilityNodeInfo> result = new ArrayList<>(); Rect area = bounds();
            for (AccessibilityNodeInfo node : nodes) {
                Rect r = AssistantService.bounds(node);
                if (visible(node) && node.getText() != null && !node.getText().toString().trim().isEmpty()
                    && r.centerX() > area.left + area.width() * .6f && r.centerY() < area.top + area.height() * .22f
                    && !node.getText().toString().contains("№") && !node.getText().toString().matches("\\d+")) result.add(node);
            }
            return result;
        }
        boolean statusConfirmed(String issue, String target) {
            List<AccessibilityNodeInfo> current = statusNodes();
            return issue.equals(issue()) && statusOptions(null).isEmpty() && current.size() == 1
                && normalize(String.valueOf(current.get(0).getText())).equals(normalize(target));
        }
        Rect statusMenuBounds(AccessibilityNodeInfo button) throws Exception {
            List<AccessibilityNodeInfo> options = statusOptions(null);
            AccessibilityNodeInfo parent = button.getParent();
            try {
                for (int depth = 0; parent != null && depth < 12; depth++) {
                    Rect r = AssistantService.bounds(parent); boolean contains = options.size() > 1;
                    for (AccessibilityNodeInfo option : options) if (!r.contains(AssistantService.bounds(option))) contains = false;
                    if (contains && r.width() < bounds().width() * .9f && r.height() < bounds().height() * .8f) return r;
                    AccessibilityNodeInfo next = parent.getParent(); parent.recycle(); parent = next;
                }
            } finally { if (parent != null) parent.recycle(); }
            throw new Exception("Контейнер меню статусов не найден");
        }
        List<AccessibilityNodeInfo> statusOptions(String caption) {
            List<AccessibilityNodeInfo> result = new ArrayList<>();
            for (AccessibilityNodeInfo node : nodes) if (visible(node) && node.getClassName() != null
                && node.getClassName().toString().endsWith("Button") && node.getText() != null
                && (caption == null || normalize(node.getText().toString()).equals(normalize(caption)))) result.add(node);
            return result;
        }
        boolean assignedTo(String name) {
            List<AccessibilityNodeInfo> labels = match("Ответственный");
            if (labels.size() != 1) return false;
            Rect label = AssistantService.bounds(labels.get(0));
            int bottom = bounds().bottom;
            for (String section : new String[] { "Соисполнители", "Наблюдатели", "Трудозатраты" }) {
                for (AccessibilityNodeInfo node : match(section)) {
                    int top = AssistantService.bounds(node).top;
                    if (top > label.bottom) bottom = Math.min(bottom, top);
                }
            }
            int found = 0;
            for (AccessibilityNodeInfo node : match(name)) {
                Rect r = AssistantService.bounds(node);
                if (r.top >= label.bottom && r.bottom <= bottom) found++;
            }
            return found == 1;
        }
        List<AccessibilityNodeInfo> match(String selector) {
            List<AccessibilityNodeInfo> result = new ArrayList<>();
            for (AccessibilityNodeInfo node : nodes) {
                if (!visible(node)) continue;
                String text = node.getText() == null ? "" : normalize(node.getText().toString());
                String desc = node.getContentDescription() == null ? "" : normalize(node.getContentDescription().toString());
                boolean matches;
                if (selector.startsWith("id:")) matches = selector.substring(3).equals(node.getViewIdResourceName());
                else if (selector.startsWith("class:")) matches = node.getClassName() != null && selector.substring(6).contentEquals(node.getClassName());
                else if (selector.startsWith("re:")) matches = Pattern.compile(selector.substring(3)).matcher(text).matches() || Pattern.compile(selector.substring(3)).matcher(desc).matches();
                else matches = normalize(selector).equals(text) || normalize(selector).equals(desc);
                if (matches) result.add(node);
            }
            return result;
        }
        AccessibilityNodeInfo editor() {
            AccessibilityNodeInfo found = null;
            for (AccessibilityNodeInfo node : nodes) if (visible(node) && node.getClassName() != null && node.getClassName().toString().endsWith("EditText")) {
                if (found != null) return null; found = node;
            }
            return found;
        }
        String commentText() {
            StringBuilder text = new StringBuilder();
            for (AccessibilityNodeInfo node : nodes) if (visible(node) && node.getChildCount() == 0 && !node.isEditable() && node.getText() != null) text.append(node.getText()).append(' ');
            return normalize(text.toString());
        }
        @Override public void close() { for (AccessibilityNodeInfo node : nodes) node.recycle(); }
    }
}
