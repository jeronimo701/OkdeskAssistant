# Передача на GitHub: 0.1.11

Private-репозиторий. Готовый debug APK находится в Releases (prerelease).
Результаты сборки и ограничения: [PUBLISHING.ru.md](PUBLISHING.ru.md).
Область собственной лицензии: [LICENSE-SCOPE.md](LICENSE-SCOPE.md); сторонние компоненты: [THIRD-PARTY.md](THIRD-PARTY.md).

---

# Okdesk Assistant

Separate Android application controlling the ORIGINAL `ru.okdesk.mobile` app through Accessibility. It does not modify Okdesk or the existing OkdeskMobile project. No API key, network permission, root or other automation app is used.

## MVP 0.1

- Settings show live green checks/red crosses for Accessibility permission, connected Assistant service, enabled overlay and installed Okdesk. Permission and service connection are checked separately.
- 0.1.3: shared editable/reorderable status presets (card name + actual action caption), scenario status picker, status-only button creation, explicit confirmation through `СМЕНИТЬ СТАТУС`, and a private employee60 request button. The private button is seeded once; deletion is respected on later starts.
- 0.1.2: optional exact employee name in the scenario editor adds an assignment step after the other actions. Assignment can also be an independent `assign` step (its `value` overrides the employee field). The engine opens the assignee picker, searches the name, rejects ambiguous matches and verifies the responsible block after selection. Prepare-only mode never assigns. Existing scenarios keep their settings.
- Draggable, collapsible accessibility overlay, visible only over original Okdesk.
- Quick responses seeded from the existing userscript, plus a clearly marked test response.
- Native editor: title, multiline text, visibility, target status caption, color, timeout, ordered steps.
- Steps: open comments, input text, verify visibility, send and verify, select status, click a selector, wait for appearance/disappearance, scroll, pause, back.
- Selectors: exact text or description, `id:full_resource_id`, `class:full_class_name`, `re:regex` (full match).
- JSON import/export; same IDs update, identical title/content/steps are skipped. Import is validated before saving.
- Prepare-only mode; step-by-step execution; stop button; local rolling log.
- Default restriction: issue 181466. It can be changed in settings; enabling all issues requires confirmation.
- A send attempt is persisted BEFORE clicking. Unknown results block retries. Confirmed sends are skipped on repeat runs; status can be retried separately.

## Installation

Install APK, open Assistant, read the access disclosure, then enable Okdesk Assistant under Android Accessibility settings. Some sideloaded APKs require allowing restricted settings in Android App info first. Return to the original Okdesk CARD before opening its comments, so Assistant can bind the visible issue number.

Tap OA to expand the panel. Every run displays the issue number, comment visibility, text preview and target status. Prepare does not send or change status. Debug waits for Next before each step.

## Boundaries

This is a profile for the inspected original Okdesk WebView, NOT a universal automation engine. Named elements use live Accessibility nodes. Unlabelled comment/send icons use their live bounds relative to the issue footer or editor. On Android 11+, in-memory Accessibility screenshots verify the known green/gray public switch and calibrate the known white status popover frame (Ionic can expose unscaled node bounds). The overlay is hidden during inspection. Unknown appearance or ambiguous geometry stops the action. Screenshots are never saved or transmitted. General OCR, image-template learning and Lua are NOT implemented.

An element changing position is supported when its label/structure survives; arbitrary redesigned screens are not guaranteed. Visibility must be observable before sending. Unknown privacy state stops the scenario without sending. UI-only verification cannot provide server-side exactly-once guarantees. Do not run a scenario while manually navigating the app. Stop cannot undo an already dispatched tap or comment.

Target status must be the actual menu caption, such as `Ожидание ответа от клиента`, not an API status code. A final status sometimes has a different action caption or a confirmation screen: these require a tailored scenario.

Shared presets map the card name to its menu action, for example `Отложена` to `Отложить`. Extra required fields (such as a defer date) are not guessed or filled automatically. Final confirmation requires the correct form header and an empty comment field, then the card's actual target status. The list contains the actions inspected in issue 181466, not an API-wide status catalogue. Existing manual status names are retained in the picker.

Phone verification on issue 181466: Open -> Waiting for client -> Working in technical support; already-set status skips successfully; no duplicate test comments. The employee60 private response was prepared and privacy was verified without sending or notifying the employee. Actual mention notification is not verified.

Assignment uses the existing employee picker and does not explicitly change the group. Use the exact displayed employee name. Identical names across groups stop the action instead of choosing an arbitrary person. On-device inspection of the picker is complete; automated reassignment to another employee is not yet verified end to end.

The bounded delivery history keeps the most recent 200 response/issue combinations. Logs and diagnostic trees stay in app-private storage. Diagnostic trees may contain issue text. Keep exports and diagnostics within your organization.

## Build

Use JDK 17 and Android SDK Platform 35. Set ANDROID_HOME, then run:

```powershell
.\gradlew.bat lintDebug assembleDebug --no-daemon --console=plain
```

No dependency on Tampermonkey or userscript versions. The old browser/WebView application is untouched.
