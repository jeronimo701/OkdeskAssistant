package ru.poscenter.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MainActivity extends Activity {
    private Store store;
    private LinearLayout page;
    private ScrollView pageScroll;
    private LinearLayout serviceChecks;
    private String checksState = "";
    private final android.os.Handler checksHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable updateChecks = new Runnable() {
        @Override public void run() { renderChecks(); checksHandler.postDelayed(this, 1000); }
    };
    private static final int IMPORT = 10, EXPORT = 11;
    private final String[] kinds = { "comments", "input", "privacy", "send", "status", "assign", "click", "wait", "gone", "scroll", "pause", "back" };
    private final String[] names = { "Открыть комментарии", "Ввести текст ответа", "Открытый / скрытый", "Отправить и проверить", "Выбрать статус", "Передать сотруднику", "Нажать элемент", "Ждать элемент", "Ждать исчезновения", "Прокрутить", "Пауза, миллисекунды", "Назад" };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store = new Store(this); home();
    }
    @Override protected void onResume() {
        super.onResume();
        if (store != null) {
            if ("ru.poscenter.assistant.DIAGNOSTICS".equals(getIntent().getAction())) {
                getIntent().setAction(null); diagnostics();
            } else home();
        }
        checksHandler.post(updateChecks);
    }
    @Override protected void onPause() { checksHandler.removeCallbacks(updateChecks); super.onPause(); }

    private LinearLayout page(String title) {
        serviceChecks = null;
        checksState = "";
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        pageScroll = scroll;
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        page = Ui.column(this); int p = Ui.dp(this, 16); page.setPadding(p, p, p, p);
        scroll.addView(page); setContentView(scroll);
        page.addView(Ui.text(this, title, 23, true)); return page;
    }

    private void home() {
        page("Okdesk Assistant");
        page.addView(Ui.text(this, "Проверка служб", 18, true));
        serviceChecks = Ui.column(this); page.addView(serviceChecks); renderChecks();
        page.addView(Ui.button(this, "Настройки доступа", () -> {
            new AlertDialog.Builder(this).setTitle("Доступ к интерфейсу Okdesk")
                .setMessage("Assistant будет читать экран оригинального Okdesk и по вашему нажатию вводить комментарии, нажимать кнопки и менять статусы.\n\nДля проверки публичности и масштаба меню может считываться снимок экрана Okdesk: анализируются переключатель или границы меню статусов. Снимок не сохраняется и не передаётся.\n\nAPI, пароль и сеть не используются. Текст сценариев и журнал хранятся только на телефоне. Служба не обрабатывает содержимое других приложений.\n\nВ следующем окне включите Okdesk Assistant. На некоторых телефонах для APK сначала нужно разрешить ограниченные настройки в информации о приложении.")
                .setPositiveButton("Открыть настройки", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Отмена", null).show();
        }));
        page.addView(Ui.button(this, "Открыть оригинальный Okdesk", this::openOkdesk));
        page.addView(Ui.button(this, "Шаблоны статусов", this::statusSettings));
        page.addView(Ui.button(this, "Руководство", this::manual));
        page.addView(Ui.button(this, "Последняя диагностика экрана", this::diagnostics));
        CheckBox enabled = new CheckBox(this); enabled.setText("Показывать плавающую панель"); enabled.setTextColor(Ui.TEXT);
        enabled.setChecked(store.prefs.getBoolean("panel", true));
        enabled.setOnCheckedChangeListener((button, value) -> { store.prefs.edit().putBoolean("panel", value).apply(); refreshService(); }); page.addView(enabled);
        page.addView(Ui.text(this, "Сторона сворачивания панели", 18, true));
        android.widget.RadioGroup side = new android.widget.RadioGroup(this);
        android.widget.RadioButton left = new android.widget.RadioButton(this);
        left.setId(android.view.View.generateViewId()); left.setText("Слева — раскрывать вправо"); left.setTextColor(Ui.TEXT);
        android.widget.RadioButton right = new android.widget.RadioButton(this);
        right.setId(android.view.View.generateViewId()); right.setText("Справа — раскрывать влево"); right.setTextColor(Ui.TEXT);
        side.addView(left); side.addView(right);
        side.check(store.prefs.getBoolean("collapseRight", false) ? right.getId() : left.getId());
        side.setOnCheckedChangeListener((group, selected) -> {
            store.prefs.edit().putBoolean("collapseRight", selected == right.getId()).apply(); refreshService();
        });
        page.addView(side);
        page.addView(Ui.text(this, "Безопасность", 18, true));
        CheckBox repeats = new CheckBox(this);
        repeats.setText("Разрешать повторные комментарии"); repeats.setTextColor(Ui.TEXT);
        repeats.setChecked(store.prefs.getBoolean("allowRepeatComments", true));
        repeats.setOnCheckedChangeListener((button, value) -> {
            store.prefs.edit().putBoolean("allowRepeatComments", value).apply();
        });
        page.addView(repeats);
        page.addView(Ui.text(this, "Разрешённая заявка. Пустое поле — любые заявки.", 14, false));
        EditText issue = Ui.field(this, "181466", store.allowedIssue(), false); issue.setInputType(InputType.TYPE_CLASS_NUMBER); page.addView(issue);
        page.addView(Ui.button(this, "Сохранить ограничение", () -> {
            String value = issue.getText().toString().trim();
            Runnable save = () -> { store.prefs.edit().putString("allowedIssue", value).commit(); toast("Ограничение сохранено"); refreshService(); };
            if (value.trim().isEmpty()) new AlertDialog.Builder(this).setTitle("Все заявки")
                .setMessage("Сценарии смогут отправлять комментарии и менять статусы в любой открытой заявке. Подтверждение перед запуском останется.")
                .setPositiveButton("Разрешить", (d, w) -> save.run()).setNegativeButton("Отмена", null).show();
            else save.run();
        }));
        page.addView(Ui.text(this, "Порядок быстрых ответов", 18, true));
        android.widget.RadioGroup order = new android.widget.RadioGroup(this);
        android.widget.RadioButton manual = new android.widget.RadioButton(this);
        manual.setId(android.view.View.generateViewId()); manual.setText("Ручной порядок"); manual.setTextColor(Ui.TEXT);
        android.widget.RadioButton frequency = new android.widget.RadioButton(this);
        frequency.setId(android.view.View.generateViewId()); frequency.setText("По частоте использования"); frequency.setTextColor(Ui.TEXT);
        order.addView(manual); order.addView(frequency);
        order.check(store.frequencyOrder() ? frequency.getId() : manual.getId());
        order.setOnCheckedChangeListener((group, selected) -> {
            store.prefs.edit().putBoolean("frequencyOrder", selected == frequency.getId()).apply(); refreshService();
        });
        page.addView(order);
        page.addView(Ui.button(this, "Сбросить частоту использования", () -> new AlertDialog.Builder(this)
            .setTitle("Сбросить счётчики?").setMessage("Ответы вернутся к ручному порядку. Сами шаблоны останутся.")
            .setPositiveButton("Сбросить", (dialog, which) -> {
                store.prefs.edit().remove("scenarioUsage").apply(); refreshService(); rebuildKeepingScroll(this::home); toast("Счётчики сброшены");
            }).setNegativeButton("Отмена", null).show()));
        page.addView(Ui.text(this, "Быстрые ответы · ручной порядок", 18, true));
        JSONArray items = store.scenarios();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); final int index = i;
            LinearLayout group = Ui.column(this);
            group.addView(Ui.text(this, item.optString("title"), 16, true));
            group.addView(Ui.text(this, "Выполнено: " + store.uses(item.optString("id")), 12, false));
            group.addView(Ui.text(this, (item.optBoolean("public", true) ? "Открытый" : "Скрытый") + " · " + (item.optString("status").trim().isEmpty() ? "Без смены статуса" : item.optString("status")), 13, false));
            group.addView(Ui.row(this,
                Ui.button(this, "Изменить", () -> edit(item, index)),
                Ui.button(this, "Копия", () -> {
                    try { JSONObject copy = new JSONObject(item.toString()); copy.put("id", UUID.randomUUID().toString()).put("title", item.optString("title") + " (копия)"); edit(copy, -1); }
                    catch (Exception e) { error(e); }
                }),
                Ui.button(this, "Удалить", () -> new AlertDialog.Builder(this).setTitle("Удалить сценарий?").setMessage(item.optString("title"))
                    .setPositiveButton("Удалить", (d, w) -> { try { JSONArray all = store.scenarios(); all.remove(index); store.save(all); refreshService(); home(); } catch (Exception e) { error(e); } })
                    .setNegativeButton("Отмена", null).show())));
            android.widget.Button up = Ui.button(this, "↑", () -> moveScenario(item.optString("id"), -1));
            android.widget.Button down = Ui.button(this, "↓", () -> moveScenario(item.optString("id"), 1));
            up.setContentDescription("Переместить ответ вверх"); down.setContentDescription("Переместить ответ вниз");
            up.setEnabled(index > 0); down.setEnabled(index + 1 < items.length());
            group.addView(Ui.row(this, up, down));
            page.addView(group);
        }
        page.addView(Ui.button(this, "+ Добавить сценарий", () -> {
            try { edit(Store.standard(UUID.randomUUID().toString(), "Новый ответ", "", "", "#3f8f72"), -1); } catch (Exception e) { error(e); }
        }));
        page.addView(Ui.row(this, Ui.button(this, "Импорт JSON", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent, IMPORT);
        }), Ui.button(this, "Экспорт JSON", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "okdesk-assistant.json"); startActivityForResult(intent, EXPORT);
        })));
        page.addView(Ui.button(this, "Журнал выполнения", () -> {
            page("Журнал"); page.addView(Ui.button(this, "Назад", this::home));
            android.widget.TextView log = Ui.text(this, store.logText(), 13, false); log.setTextIsSelectable(true); page.addView(log);
        }));
        page.addView(Ui.text(this, "0.1.11 · Без API · Только ru.okdesk.mobile", 12, false));
    }

    private void edit(JSONObject source, int index) {
        try {
            JSONObject draft = new JSONObject(source.toString());
            page("Редактор сценария");
            EditText title = labeled("Название", draft.optString("title"), false);
            EditText text = labeled("Текст комментария", draft.optString("text"), true);
            String[] selectedStatus = { draft.optString("status") };
            page.addView(Ui.text(this, "Статус после выполнения", 14, false));
            android.widget.Button status = Ui.button(this, selectedStatus[0].isEmpty() ? "Без смены статуса ▾" : selectedStatus[0] + " ▾", () -> {});
            status.setOnClickListener(v -> {
                JSONArray presets = store.statuses();
                java.util.ArrayList<String> choices = new java.util.ArrayList<>(); choices.add("Без смены статуса");
                for (int i = 0; i < presets.length(); i++) choices.add(presets.optJSONObject(i).optString("name"));
                if (!selectedStatus[0].isEmpty() && !choices.contains(selectedStatus[0])) choices.add(selectedStatus[0]);
                new AlertDialog.Builder(this).setTitle("Статус").setSingleChoiceItems(choices.toArray(new String[0]),
                    selectedStatus[0].isEmpty() ? 0 : choices.indexOf(selectedStatus[0]), (dialog, which) -> {
                        selectedStatus[0] = which == 0 ? "" : choices.get(which);
                        status.setText(choices.get(which) + " ▾"); dialog.dismiss();
                    }).setNegativeButton("Отмена", null).show();
            });
            page.addView(status);
            EditText assignee = labeled("Передать сотруднику: точное имя в Okdesk (пусто — не менять)", draft.optString("assignee"), false);
            CheckBox publicBox = new CheckBox(this); publicBox.setText("Открытый комментарий"); publicBox.setTextColor(Ui.TEXT); publicBox.setChecked(draft.optBoolean("public", true)); page.addView(publicBox);
            EditText timeout = labeled("Тайм-аут шага, секунд (2–60)", String.valueOf(draft.optInt("timeout", 12)), false); timeout.setInputType(InputType.TYPE_CLASS_NUMBER);
            page.addView(Ui.text(this, "Цвет кнопки", 14, false));
            Spinner colors = new Spinner(this); String[] palette = { "#3f8f72", "#3e78a5", "#ad6b45", "#8065aa", "#a85a63" };
            colors.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, palette));
            for (int c = 0; c < palette.length; c++) if (palette[c].equals(draft.optString("color"))) colors.setSelection(c);
            page.addView(colors);
            page.addView(Ui.text(this, "Шаги", 18, true));
            page.addView(Ui.button(this, "Справка по шагам и селекторам", () -> {
                ScrollView help = new ScrollView(this);
                android.widget.TextView content = Ui.text(this, readManual(), 15, false);
                int p = Ui.dp(this, 16); content.setPadding(p, p, p, p); content.setTextIsSelectable(true);
                help.setBackgroundColor(Ui.BG); help.addView(content);
                new AlertDialog.Builder(this).setTitle("Руководство по сценариям").setView(help).setPositiveButton("Закрыть", null).show();
            }));
            LinearLayout stepsHost = Ui.column(this); page.addView(stepsHost);
            JSONArray steps = draft.getJSONArray("steps");
            Runnable render = new Runnable() {
                @Override public void run() {
                    stepsHost.removeAllViews();
                    for (int i = 0; i < steps.length(); i++) {
                        JSONObject step = steps.optJSONObject(i); final int pos = i;
                        int kind = java.util.Arrays.asList(kinds).indexOf(step.optString("kind"));
                        stepsHost.addView(Ui.text(MainActivity.this, (i + 1) + ". " + (kind < 0 ? step.optString("kind") : names[kind]), 15, true));
                        if (SetValueNeeded(step.optString("kind"))) {
                            EditText value = Ui.field(MainActivity.this, "Текст; id:полный_ID; class:класс; re:выражение", step.optString("value"), false);
                            value.addTextChangedListener(new android.text.TextWatcher() {
                                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                                public void onTextChanged(CharSequence s, int start, int before, int count) { try { step.put("value", s.toString()); } catch (Exception ignored) {} }
                                public void afterTextChanged(android.text.Editable e) {}
                            }); stepsHost.addView(value);
                        }
                        stepsHost.addView(Ui.row(MainActivity.this,
                            Ui.button(MainActivity.this, "↑", () -> { if (pos > 0) { swap(steps, pos, pos - 1); run(); } }),
                            Ui.button(MainActivity.this, "↓", () -> { if (pos < steps.length() - 1) { swap(steps, pos, pos + 1); run(); } }),
                            Ui.button(MainActivity.this, "Убрать", () -> { steps.remove(pos); run(); })));
                    }
                }
            };
            render.run();
            page.addView(Ui.button(this, "+ Добавить шаг", () -> new AlertDialog.Builder(this).setTitle("Действие").setItems(names, (d, which) -> {
                try { steps.put(Store.step(kinds[which], kinds[which].equals("pause") ? "500" : "")); render.run(); } catch (Exception e) { error(e); }
            }).show()));
            page.addView(Ui.row(this, Ui.button(this, "Отмена", this::home), Ui.button(this, "Сохранить", () -> {
                try {
                    draft.put("title", title.getText().toString().trim()).put("text", text.getText().toString())
                        .put("status", selectedStatus[0]).put("public", publicBox.isChecked())
                        .put("assignee", assignee.getText().toString().trim())
                        .put("color", palette[colors.getSelectedItemPosition()]).put("timeout", Integer.parseInt(timeout.getText().toString()));
                    boolean hasAssign = false;
                    for (int s = 0; s < steps.length(); s++) if (steps.getJSONObject(s).optString("kind").equals("assign")) hasAssign = true;
                    if (!draft.optString("assignee").isEmpty() && !hasAssign) steps.put(Store.step("assign", ""));
                    if (draft.optString("assignee").isEmpty()) for (int s = steps.length() - 1; s >= 0; s--) {
                        JSONObject step = steps.getJSONObject(s);
                        if (step.optString("kind").equals("assign") && step.optString("value").trim().isEmpty()) steps.remove(s);
                    }
                    JSONArray items = store.scenarios(); if (index < 0) items.put(draft); else items.put(index, draft);
                    store.save(items); refreshService(); toast("Сценарий сохранён"); home();
                } catch (Exception e) { error(e); }
            })));
        } catch (Exception e) { error(e); }
    }

    private void statusSettings() {
        page("Шаблоны статусов");
        page.addView(Ui.button(this, "Назад", this::home));
        JSONArray items = store.statuses();
        for (int i = 0; i < items.length(); i++) {
            final int index = i; JSONObject item = items.optJSONObject(i);
            page.addView(Ui.text(this, item.optString("name"), 17, true));
            page.addView(Ui.text(this, "Пункт в Okdesk: " + item.optString("action"), 13, false));
            android.widget.Button up = Ui.button(this, "↑", () -> { if (index > 0) { swap(items, index, index - 1); saveStatusList(items); } });
            android.widget.Button down = Ui.button(this, "↓", () -> { if (index + 1 < items.length()) { swap(items, index, index + 1); saveStatusList(items); } });
            up.setContentDescription("Переместить статус вверх"); down.setContentDescription("Переместить статус вниз");
            up.setEnabled(index > 0); down.setEnabled(index + 1 < items.length());
            page.addView(Ui.row(this, up, down));
            page.addView(Ui.row(this,
                Ui.button(this, "Изменить", () -> editStatus(items, index)),
                Ui.button(this, "Удалить", () -> new AlertDialog.Builder(this).setTitle("Удалить статус из списка?")
                    .setMessage("В сохранённых сценариях выбранный статус останется. Для них проверьте настройки.")
                    .setPositiveButton("Удалить", (d, w) -> { items.remove(index); saveStatusList(items); })
                    .setNegativeButton("Отмена", null).show())));
        }
        page.addView(Ui.button(this, "+ Добавить статус", () -> editStatus(items, -1)));
        page.addView(Ui.button(this, "Создать кнопку смены статуса", () -> {
            try {
                JSONObject scenario = Store.standard(UUID.randomUUID().toString(), "Смена статуса", "", "", "#3e78a5");
                scenario.put("steps", new JSONArray().put(Store.step("status", ""))); edit(scenario, -1);
            } catch (Exception e) { error(e); }
        }));
    }

    private void saveStatusList(JSONArray items) {
        try { store.saveStatuses(items); refreshService(); rebuildKeepingScroll(this::statusSettings); } catch (Exception e) { error(e); }
    }

    private void rebuildKeepingScroll(Runnable render) {
        int y = pageScroll == null ? 0 : pageScroll.getScrollY();
        render.run();
        ScrollView scroll = pageScroll;
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void moveScenario(String id, int direction) {
        try {
            JSONArray items = store.scenarios();
            for (int i = 0; i < items.length(); i++) if (id.equals(items.getJSONObject(i).optString("id"))) {
                int to = i + direction;
                if (to < 0 || to >= items.length()) return;
                swap(items, i, to); store.save(items); refreshService(); rebuildKeepingScroll(this::home); return;
            }
        } catch (Exception e) { error(e); }
    }

    private void editStatus(JSONArray items, int index) {
        JSONObject old = index < 0 ? new JSONObject() : items.optJSONObject(index);
        LinearLayout content = Ui.column(this); int padding = Ui.dp(this, 16); content.setPadding(padding, padding, padding, padding);
        content.addView(Ui.text(this, "Название статуса в карточке", 14, false));
        EditText name = Ui.field(this, "Например: Отложена", old.optString("name"), false); content.addView(name);
        content.addView(Ui.text(this, "Точный пункт в меню Okdesk", 14, false));
        EditText action = Ui.field(this, "Например: Отложить", old.optString("action"), false); content.addView(action);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(index < 0 ? "Новый статус" : "Изменить статус").setView(content)
            .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                JSONArray next = new JSONArray(items.toString());
                JSONObject value = Store.status(name.getText().toString().trim(), action.getText().toString().trim());
                if (index < 0) next.put(value); else next.put(index, value);
                store.saveStatuses(next); dialog.dismiss(); statusSettings();
            } catch (Exception e) { error(e); }
        })); dialog.show();
    }

    private String readManual() {
        try (InputStream input = getAssets().open("manual-ru.txt")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { return "Не удалось открыть руководство: " + e.getMessage(); }
    }

    private void manual() {
        page("Руководство"); page.addView(Ui.button(this, "Назад", this::home));
        for (String section : readManual().split("(?m)^## ")) {
            if (section.trim().isEmpty()) continue;
            int line = section.indexOf('\n');
            String title = line < 0 ? section : section.substring(0, line);
            android.widget.TextView body = Ui.text(this, line < 0 ? "" : section.substring(line + 1).trim(), 15, false);
            body.setTextIsSelectable(true); body.setVisibility(android.view.View.GONE);
            android.widget.Button toggle = Ui.button(this, "▸ " + title, () -> {});
            toggle.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            toggle.setOnClickListener(v -> {
                boolean open = body.getVisibility() != android.view.View.VISIBLE;
                body.setVisibility(open ? android.view.View.VISIBLE : android.view.View.GONE);
                toggle.setText((open ? "▾ " : "▸ ") + title);
            });
            page.addView(toggle); page.addView(body);
        }
    }

    private static String nodeValue(JSONObject node, String key) {
        String value = node.optString(key, ""); return "null".equals(value) ? "" : value;
    }

    private void diagnostics() {
        page("Диагностика экрана"); page.addView(Ui.button(this, "Назад", this::home));
        page.addView(Ui.text(this, "В Okdesk откройте нужный экран → панель Assistant → «Дополнительно» → «Диагностика экрана». Здесь появится снимок элементов, а не изображение экрана.", 14, false));
        try {
            java.io.File file = new java.io.File(getFilesDir(), "screen-tree.json");
            if (!file.exists()) { page.addView(Ui.text(this, "Диагностика ещё не создана", 16, true)); return; }
            JSONArray nodes = new JSONArray(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            page.addView(Ui.text(this, "Сохранено: " + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(file.lastModified())), 13, false));
            EditText search = Ui.field(this, "Найти по тексту, описанию, ID или классу", "", false); page.addView(search);
            android.widget.TextView summary = Ui.text(this, "", 13, false); page.addView(summary);
            LinearLayout list = Ui.column(this); page.addView(list);
            Runnable render = () -> {
                list.removeAllViews(); int found = 0;
                String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (int i = 0; i < nodes.length(); i++) {
                    JSONObject node = nodes.optJSONObject(i);
                    if (node == null || !node.optBoolean("visible")) continue;
                    String text = nodeValue(node, "text"), desc = nodeValue(node, "desc"), id = nodeValue(node, "id"), type = nodeValue(node, "class");
                    if (!(text + "\n" + desc + "\n" + id + "\n" + type).toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
                    found++; if (found > 100) continue;
                    android.widget.TextView details = Ui.text(this, "Текст: " + text + "\nОписание: " + desc + "\nID: " + id
                        + "\nКласс: " + type + "\nГраницы: " + nodeValue(node, "bounds"), 13, false);
                    details.setTextIsSelectable(true); list.addView(details);
                    list.addView(Ui.button(this, "Скопировать селектор", () -> copySelector(node, nodes)));
                }
                summary.setText("Найдено: " + found + (found > 100 ? ". Показаны первые 100 — уточните поиск." : ""));
            };
            search.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
                public void afterTextChanged(android.text.Editable e) {}
            }); render.run();
        } catch (Exception e) { page.addView(Ui.text(this, "Не удалось прочитать диагностику: " + e.getMessage(), 14, false)); }
    }

    private void copySelector(JSONObject node, JSONArray nodes) {
        java.util.ArrayList<String> selectors = new java.util.ArrayList<>();
        String text = nodeValue(node, "text"), desc = nodeValue(node, "desc"), id = nodeValue(node, "id"), type = nodeValue(node, "class");
        if (!text.isEmpty()) selectors.add("re:" + java.util.regex.Pattern.quote(text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim()));
        if (!desc.isEmpty() && !desc.equals(text)) selectors.add("re:" + java.util.regex.Pattern.quote(desc.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim()));
        if (!id.isEmpty()) selectors.add("id:" + id);
        if (!type.isEmpty()) selectors.add("class:" + type);
        String[] labels = new String[selectors.size()];
        for (int i = 0; i < labels.length; i++) {
            String selector = selectors.get(i); int matches = 0;
            for (int j = 0; j < nodes.length(); j++) {
                JSONObject other = nodes.optJSONObject(j);
                if (other == null || !other.optBoolean("visible")) continue;
                boolean match;
                if (selector.startsWith("id:")) match = selector.substring(3).equals(nodeValue(other, "id"));
                else if (selector.startsWith("class:")) match = selector.substring(6).equals(nodeValue(other, "class"));
                else {
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(selector.substring(3));
                    match = pattern.matcher(nodeValue(other, "text").replace('\u00a0', ' ').replaceAll("\\s+", " ").trim()).matches()
                        || pattern.matcher(nodeValue(other, "desc").replace('\u00a0', ' ').replaceAll("\\s+", " ").trim()).matches();
                }
                if (match) matches++;
            }
            labels[i] = selector + "\nСовпадений в снимке: " + matches;
        }
        new AlertDialog.Builder(this).setTitle("Выберите селектор").setItems(labels, (dialog, index) -> {
            android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Селектор Okdesk", selectors.get(index)));
            toast("Селектор скопирован. Для нажатия нужен один видимый элемент с таким селектором.");
        }).setNegativeButton("Отмена", null).show();
    }

    private static boolean SetValueNeeded(String kind) { return java.util.Arrays.asList("click", "wait", "gone", "pause", "scroll").contains(kind); }
    private static void swap(JSONArray array, int a, int b) {
        try { Object old = array.get(a); array.put(a, array.get(b)); array.put(b, old); } catch (Exception ignored) {}
    }
    private EditText labeled(String label, String value, boolean multi) { page.addView(Ui.text(this, label, 14, false)); EditText field = Ui.field(this, label, value, multi); page.addView(field); return field; }
    private void openOkdesk() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(Store.TARGET);
        if (intent == null) toast("Оригинальный Okdesk не установлен"); else startActivity(intent);
    }
    private void refreshService() { if (AssistantService.instance != null) AssistantService.instance.refreshPanel(); }
    private void renderChecks() {
        if (serviceChecks == null || store == null) return;
        boolean permitted = false;
        String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices != null) for (String name : enabledServices.split(":")) {
            android.content.ComponentName component = android.content.ComponentName.unflattenFromString(name);
            if (component != null && component.getPackageName().equals(getPackageName())
                && component.getClassName().equals(AssistantService.class.getName())) permitted = true;
        }
        boolean connected = AssistantService.instance != null;
        boolean panel = store.prefs.getBoolean("panel", true);
        boolean installed = getPackageManager().getLaunchIntentForPackage(Store.TARGET) != null;
        String state = permitted + ":" + connected + ":" + panel + ":" + installed;
        if (state.equals(checksState)) return;
        checksState = state;
        serviceChecks.removeAllViews();
        serviceCheck("Доступ к специальным возможностям", permitted);
        serviceCheck("Служба Assistant подключена", connected);
        serviceCheck("Плавающая панель включена", panel);
        serviceCheck("Оригинальный Okdesk установлен", installed);
    }
    private void serviceCheck(String label, boolean okay) {
        android.widget.TextView status = Ui.text(this, (okay ? "✓  " : "✕  ") + label, 14, false);
        status.setTextColor(Color.parseColor(okay ? "#75d09d" : "#ff9898"));
        status.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5)); serviceChecks.addView(status);
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void error(Exception e) { new AlertDialog.Builder(this).setTitle("Не удалось выполнить").setMessage(e.getMessage()).setPositiveButton("Понятно", null).show(); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (request == IMPORT) {
                String json;
                try (InputStream input = getContentResolver().openInputStream(data.getData())) {
                    if (input == null) throw new Exception("Файл недоступен");
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = input.read(chunk)) != -1) {
                        if (buffer.size() + count > 1_000_000) throw new Exception("Файл больше 1 МБ");
                        buffer.write(chunk, 0, count);
                    }
                    byte[] bytes = buffer.toByteArray();
                    if (bytes.length > 1_000_000) throw new Exception("Файл больше 1 МБ"); json = new String(bytes, StandardCharsets.UTF_8);
                }
                int count = store.merge(json); refreshService(); home(); toast("Добавлено / обновлено сценариев: " + count + ". Повторы пропущены.");
            } else if (request == EXPORT) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    if (output == null) throw new Exception("Файл недоступен"); output.write(store.export().getBytes(StandardCharsets.UTF_8));
                } toast("Сценарии экспортированы");
            }
        } catch (Exception e) { error(e); }
    }
}
