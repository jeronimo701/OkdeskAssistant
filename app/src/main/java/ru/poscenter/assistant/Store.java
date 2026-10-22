package ru.poscenter.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class Store {
    static final String TARGET = "ru.okdesk.mobile";
    static final Set<String> KINDS = new java.util.HashSet<>(java.util.Arrays.asList("comments", "input", "privacy", "send", "status", "assign", "click", "wait", "gone", "scroll", "pause", "back"));
    final SharedPreferences prefs;
    private final File logFile;

    Store(Context context) {
        prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE);
        logFile = new File(context.getFilesDir(), "actions.log");
    }

    JSONArray scenarios() {
        try {
            String saved = prefs.getString("scenarios", null);
            if (saved != null) {
                JSONArray items = new JSONArray(saved);
                addPrivateRequest(items);
                return items;
            }
            JSONArray items = new JSONArray();
            items.put(standard("test", "Проверка Assistant", "[Тест Okdesk Assistant] Проверка отправки комментария и изменения статуса через интерфейс приложения. API не используется.", "Ожидание ответа от клиента", "#3f8f72"));
            items.put(standard("relevance", "Актуальность", "Вопрос по-прежнему актуален? Если да, просьба дать ответ на вопрос из комментария ниже. В ином случае переведём заявку в статус «Решена».", "Ожидание ответа от клиента", "#3e78a5"));
            items.put(standard("waiting", "Ожидание", "Вопрос находится в работе. Просьба ожидать, сообщим результат после уточнения информации.", "Ожидание ответа от клиента", "#ad6b45"));
            items.put(standard("incomplete", "Неполная заявка", "Для корректной и оперативной обработки обращения просим дополнить заявку необходимой информацией.\n\nПожалуйста, укажите:\n• модель оборудования;\n• серийный номер устройства;\n• подробное описание неисправности;\n• какие проверки уже были выполнены;\n• фото/видео проявления и скриншоты ошибок;\n• версии ПО, драйверов и ОС.\n\nПосле получения данных продолжим работу по обращению.", "Ожидание ответа от клиента", "#8065aa"));
            items.put(standard("service", "В АСЦ", "Мы можем предоставить консультацию в рамках заявки. Для ремонта, настройки или диагностики на месте просьба обратиться в ближайший авторизованный сервисный центр: https://optposcenter.ru/service/", "Ожидание ответа от клиента", "#3f8f72"));
            addPrivateRequest(items);
            save(items);
            return items;
        } catch (Exception e) { throw new IllegalStateException("Не удалось прочитать сценарии", e); }
    }

    private void addPrivateRequest(JSONArray items) throws Exception {
        if (prefs.getBoolean("privateRequestAdded", false)) return;
        boolean found = false;
        for (int i = 0; i < items.length(); i++) if (items.getJSONObject(i).optString("id").equals("employee60-request")) found = true;
        if (!found) items.put(standard("employee60-request", "Запрос данных", "[~employee60|Администратор Сервис Посцентр]\nЗапроси пожалуйста данные по оборудованию и партнеру", "", "#a85a63").put("public", false));
        save(items);
        prefs.edit().putBoolean("privateRequestAdded", true).commit();
    }

    boolean frequencyOrder() { return prefs.getBoolean("frequencyOrder", false); }

    private JSONObject usage() {
        try { return new JSONObject(prefs.getString("scenarioUsage", "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    long uses(String id) { return Math.max(0, usage().optLong(id, 0)); }

    synchronized void recordUse(String id) {
        if (id.isEmpty()) return;
        try {
            JSONObject counts = usage();
            counts.put(id, Math.min(1_000_000_000L, Math.max(0, counts.optLong(id, 0))) + 1);
            prefs.edit().putString("scenarioUsage", counts.toString()).apply();
        } catch (Exception e) { log("Не удалось обновить частоту использования: " + e.getMessage()); }
    }

    JSONArray panelScenarios() {
        JSONArray items = scenarios();
        if (!frequencyOrder()) return items;
        JSONObject counts = usage();
        java.util.ArrayList<JSONObject> sorted = new java.util.ArrayList<>();
        for (int i = 0; i < items.length(); i++) sorted.add(items.optJSONObject(i));
        // Stable sorting retains manual order when usage counts are equal.
        sorted.sort((a, b) -> Long.compare(counts.optLong(b.optString("id"), 0), counts.optLong(a.optString("id"), 0)));
        JSONArray result = new JSONArray();
        for (JSONObject item : sorted) result.put(item);
        return result;
    }

    JSONArray statuses() {
        try {
            String saved = prefs.getString("statuses", null);
            if (saved != null) {
                JSONArray items = new JSONArray(saved);
                if (!prefs.getBoolean("closedStatusAdded", false)) {
                    boolean found = false;
                    for (int i = 0; i < items.length(); i++) if (items.getJSONObject(i).optString("name").equals("Закрыта")) found = true;
                    if (!found) items.put(status("Закрыта", "Закрыть"));
                    saveStatuses(items); prefs.edit().putBoolean("closedStatusAdded", true).commit();
                }
                return items;
            }
            JSONArray result = new JSONArray();
            result.put(status("Открыта", "Открыть"));
            result.put(status("Отложена", "Отложить"));
            result.put(status("В работе в техподдержке", "В работе в техподдержке"));
            result.put(status("Ожидание ответа от клиента", "Ожидание ответа от клиента"));
            result.put(status("Диагностика/Ремонт", "Диагностика/Ремонт"));
            result.put(status("Подтверждение отправки ЗИП", "Подтверждение отправки ЗИП"));
            result.put(status("Решена", "Решить"));
            result.put(status("Закрыта", "Закрыть"));
            saveStatuses(result); return result;
        } catch (Exception e) { throw new IllegalStateException("Не удалось прочитать статусы", e); }
    }

    static JSONObject status(String name, String action) throws Exception {
        return new JSONObject().put("name", name).put("action", action);
    }

    void saveStatuses(JSONArray items) throws Exception {
        if (items.length() > 100) throw new Exception("Не больше 100 статусов");
        Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String name = item.optString("name").trim(), action = item.optString("action").trim();
            if (name.isEmpty() || action.isEmpty() || name.length() > 200 || action.length() > 200) throw new Exception("Укажите название статуса и пункт меню до 200 символов");
            if (!names.add(name)) throw new Exception("Повтор названия статуса: " + name);
            item.put("name", name).put("action", action);
        }
        if (!prefs.edit().putString("statuses", items.toString()).commit()) throw new Exception("Не удалось сохранить статусы");
    }

    String statusAction(String name) {
        JSONArray items = statuses();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item.optString("name").equals(name)) return item.optString("action");
        }
        return name;
    }

    static JSONObject standard(String id, String title, String text, String status, String color) throws Exception {
        return new JSONObject().put("id", id).put("title", title).put("text", text).put("status", status)
            .put("public", true).put("color", color).put("timeout", 12)
            .put("steps", new JSONArray().put(step("comments", "")).put(step("input", ""))
                .put(step("privacy", "")).put(step("send", "")).put(step("status", "")));
    }

    static JSONObject step(String kind, String value) throws Exception {
        return new JSONObject().put("kind", kind).put("value", value);
    }

    void save(JSONArray items) throws Exception {
        if (items.length() > 100) throw new Exception("Не больше 100 сценариев");
        Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item.optString("id").trim().isEmpty()) item.put("id", UUID.randomUUID().toString());
            if (!ids.add(item.getString("id"))) throw new Exception("Повтор ID сценария");
            if (item.optString("title").trim().isEmpty() || item.optString("title").length() > 120) throw new Exception("Укажите название до 120 символов");
            if (item.optString("text").length() > 20000) throw new Exception("Текст больше 20000 символов");
            if (item.optString("status").length() > 200) throw new Exception("Слишком длинное название статуса");
            if (item.optString("assignee").length() > 200) throw new Exception("Слишком длинное имя сотрудника");
            int timeout = item.optInt("timeout", 12);
            if (timeout < 2 || timeout > 60) throw new Exception("Тайм-аут: от 2 до 60 секунд");
            JSONArray steps = item.getJSONArray("steps");
            if (steps.length() < 1 || steps.length() > 40) throw new Exception("Нужно от 1 до 40 шагов");
            for (int j = 0; j < steps.length(); j++) {
                JSONObject step = steps.getJSONObject(j);
                if (!KINDS.contains(step.optString("kind"))) throw new Exception("Неизвестный шаг: " + step.optString("kind"));
                if (step.optString("kind").equals("assign") && step.optString("value", "").trim().isEmpty()
                    && item.optString("assignee").trim().isEmpty()) throw new Exception("Для передачи укажите имя сотрудника");
                if (step.optString("value").length() > 20000) throw new Exception("Слишком длинный параметр шага");
            }
        }
        if (items.toString().length() > 800000) throw new Exception("Слишком большой набор сценариев");
        if (!prefs.edit().putString("scenarios", items.toString()).commit()) throw new Exception("Не удалось сохранить сценарии");
    }

    int merge(String json) throws Exception {
        JSONObject bundle = new JSONObject(json);
        if (!"okdesk-assistant".equals(bundle.optString("format")) || bundle.optInt("version") != 1) throw new Exception("Неверный формат файла");
        JSONArray source = bundle.getJSONArray("scenarios");
        JSONArray result = new JSONArray(scenarios().toString());
        int changed = 0;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.getJSONObject(i);
            boolean duplicate = false;
            for (int j = 0; j < result.length(); j++) {
                JSONObject old = result.getJSONObject(j);
                if (fingerprint(old).equals(fingerprint(item)) && old.optString("title").equals(item.optString("title"))) { duplicate = true; break; }
                if (!item.optString("id").trim().isEmpty() && old.optString("id").equals(item.optString("id"))) {
                    result.put(j, item); changed++; duplicate = true; break;
                }
            }
            if (!duplicate) { result.put(item); changed++; }
        }
        save(result);
        return changed;
    }

    String export() throws Exception {
        return new JSONObject().put("format", "okdesk-assistant").put("version", 1).put("scenarios", scenarios()).toString(2);
    }

    String allowedIssue() { return prefs.getString("allowedIssue", "181466").trim(); }

    static String fingerprint(JSONObject scenario) {
        try {
            String text = scenario.optString("text") + "\n" + scenario.optString("status") + "\n" + scenario.optBoolean("public", true) + "\n" + scenario.optJSONArray("steps");
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    synchronized String delivery(String issue, JSONObject scenario) {
        return prefs.getString("delivery:" + issue + ":" + fingerprint(scenario), "new");
    }

    synchronized void delivery(String issue, JSONObject scenario, String state) {
        String key = "delivery:" + issue + ":" + fingerprint(scenario);
        JSONArray history;
        try { history = new JSONArray(prefs.getString("deliveryHistory", "[]")); } catch (Exception e) { history = new JSONArray(); }
        JSONArray next = new JSONArray();
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < history.length(); i++) {
            String old = history.optString(i);
            if (!old.equals(key)) next.put(old);
        }
        next.put(key);
        while (next.length() > 200) { editor.remove(next.optString(0)); next.remove(0); }
        if (!editor.putString("deliveryHistory", next.toString()).putString(key, state).commit())
            throw new IllegalStateException("Не удалось сохранить защиту от повторной отправки");
    }

    synchronized void log(String text) {
        try {
            String old = logFile.exists() ? new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8) : "";
            if (old.length() > 180000) old = old.substring(old.length() - 120000);
            String stamp = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(new Date());
            Files.write(logFile.toPath(), (old + stamp + "  " + text + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    synchronized String logText() {
        try { return logFile.exists() ? new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8) : "Журнал пуст"; }
        catch (Exception e) { return e.toString(); }
    }
}
