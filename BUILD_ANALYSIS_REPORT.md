# myVPNproject — Анализ сборки NDK / CI

**Дата:** 2026-07-07 15:20 UTC+5 (Almaty)
**Репозиторий:** https://github.com/MaksKbz/myVPNproject
**Ветка:** master
**Последний успешный run:** #75 — 911247a
**CI:** Build Android APK (.github/workflows/android.yml)
**NDK:** 27.0.12077973
**AGP:** 8.3.2 / Gradle 8.7 / Kotlin 1.9.23 / compileSdk 35

---

## 1. Итог

Сборка **восстановлена**. Run #75 (commit 911247a) — **SUCCESS**, 1m 16s.

Артефакт: app-debug.apk генерируется.

Последняя ошибка в run #74 была исправлена патчем `kavl_insert` return type.

⚠️ **КРИТИЧЕСКОЕ: Вы выложили GitHub PAT в открытом чате:**
`github_pat_11AYA6QXQ0...`
**Немедленно отзовите токен в Settings → Developer settings → Personal access tokens.**

---

## 2. Хронология падений (runs #65 — #74)

### Run #70 — 578528a `fix: forward-declare gen_offset in desync.c`
**Ошибки:**
- `mpool.h:36:5: error: type name requires a specifier or qualifier`
  - Причина: circular include `mpool.h ↔ params.h`. `KAVL_HEAD(struct elem)` не раскрывался, т.к. `struct elem` еще не определён.
- `packets.h:29: unknown type name 'ssize_t'`
- `packets.c:178: conflicting types for 'change_tls_sni'` и т.д.
  - Причина: `struct packet` incomplete — `params.h` не был подключён.

### Run #71 — 59c814c `docs: add BUILD_ERRORS.md`
Те же ошибки, что #70. Build intentionally документирован.

### Run #72 — 4e07302 `fix: restore missing #include names in conev.h`
Новые ошибки поверх #71:
- `conev.c:48: error: use of undeclared identifier '_POLLDEF'`
- `conev.c:78: error: call to undeclared function 'munmap'`
- `desync.c:172: call to undeclared function 'vmsplice'`, `SPLICE_F_GIFT`
- `extend.c:113: variable has incomplete type 'struct s5_req'`
- `S_VER5`, `S_ER_OK` undeclared
  - Причина: vendored byedpi заголовки были усечены. SOCKS5 константы и структуры жили в `proxy.c`, а не в `proxy.h`.

### Run #73 — 0a2259e `fix(native): 4 blockers — KAVL_HEAD macro, to_count field...`
Исправлены includes, но сломан kavl:
- `mpool.c:54: error: call to undeclared function 'kavl_find'`
- `kavl_itr_t` undeclared
- `S_AUTH_BAD`, `S_AUTH_NONE`, `S_VER5`, `S_ATP_I4` … — всё ещё undeclared в `proxy.c`
  - Причина: `kavl.h` был переписан на упрощённый вариант с `KAVL_INIT2`, генерирующим только `kavl_insert_my` / `kavl_find_my`, но без макросов-обёрток `kavl_find(suf, ...)`. Вызовы в `mpool.c` остались в upstream-стиле `kavl_find(my, root, x, 0)` — компилятор их не находил.

### Run #74 — 835a38c `fix(native): 3 blockers in run #73`
Автор добавил в `kavl.h`:
- полный `KAVL_INIT2` (insert, find, erase, erase_first, iterator)
- wrapper-макросы:
  ```
  #define kavl_find(suf, root, x, cnt) kavl_find_##suf(root, x)
  #define kavl_insert(suf, rootp, x, cnt) kavl_insert_##suf(rootp, x, cnt)
  ...
  ```
- добавил `_POLLDEF` и `#include <sys/mman.h>` в `conev.h`
- вынес SOCKS5 дефайны в `proxy.h`

**Но:** `kavl_insert_##suf` остался `void`!
Результат:
```
mpool.c:69:7: error: assigning to 'struct elem *' from incompatible type 'void'
    v = kavl_insert(my, &hdr->root, e, 0);
mpool.c:72:11: error: assigning to 'struct elem *' from incompatible type 'void'
```
Это и сломало run #74.

Дополнительно warning:
- `extend.c:399: format specifies type 'ssize_t' but the argument has type 'int'`
- `packets.c:45: warning: unknown attribute 'nonstring'`

---

## 3. Применённое исправление (Run #75 — 911247a)

**Файл:** `app/src/main/jni/byedpi/kavl.h`

```c
- __scope void kavl_insert_##suf(...)
+ __scope __type *kavl_insert_##suf(...)
...
if (!b[0]) { ... return x; }
...
if (c == 0) return t;   // было: return;
...
return x;
```

**Что чинит:**
- `kavl_insert()` теперь возвращает `__type *` — как в upstream byedpi / klib kavl.h
- При дубликате возвращается существующий узел `t`, иначе новый `x`
- `mpool.c: v = kavl_insert(...)` компилируется корректно

Результат CI: **SUCCESS**

Commit: `911247a1768e929b7fb75a4d67a1ecbb52cdf318`
Run: https://github.com/MaksKbz/myVPNproject/actions/runs/28858519752

---

## 4. Текущие оставшиеся риски / warnings

Сборка зелёная, но остаются технические долги:

1. **kavl.h — это не upstream**
   - Текущая версия: упрощённый BST без балансировки AVL, без `size` поля, без `kavl_size`, `kavl_erase` — наивный.
   - `KAVL_HEAD` = `{left__, right__, balance__}` — отличается от upstream `{p[2], balance, size}`.
   - Для продакшн mempool с тысячами хостов — деградация до O(n), риск stack overflow.
   - **Рекомендация:** заменить весь `byedpi/` на чистый сабмодуль:
     ```
     git submodule update --init --recursive
     rm -rf app/src/main/jni/byedpi/*
     cp -r app/src/main/jni/byedpi_upstream/* app/src/main/jni/byedpi/
     ```
     Либо вообще собирать напрямую из `app/src/main/jni/byedpi/` как git submodule, убрав vendored копии.

2. **Circular include mpool.h / params.h**
   - Сейчас решён forward declaration `struct desync_params;` в `mpool.h` — работает, но хрупко.
   - Upstream решает через include-order, но лучше оставить forward decl + вынести `struct desync_params` в отдельный `desync_params_fwd.h`.

3. **Warnings, которые скоро станут errors с -Werror:**
   - `extend.c:399: %zd vs int` — `val->tls_rec_size` int, а формат `%zd`.
     Fix: `(ssize_t)val->tls_rec_size` или `%d`.
   - `packets.c:45: unknown attribute 'nonstring'` — Clang NDK 27 не знает gcc-атрибут.
     Fix: `#if defined(__GNUC__) && !defined(__clang__)`

4. **Missing Android-specific guards:**
   - `vmsplice`, `splice`, `SPLICE_F_GIFT` — в run #72 падали. Сейчас, похоже, вырезаны `#ifdef`? Проверьте `desync.c:172`.
   - Если код попадёт на Android — этих syscall нет. Нужно `#ifdef __linux__ && !__ANDROID__`.

5. **SOCKS дефайны в proxy.h**
   - Сейчас продублированы из `proxy.c`. Это ок, но проверьте ODR — нет ли конфликтов с badvpn/tun2socks headers.

6. **CI workflow**
   - `Node.js 20 is deprecated` warning в actions/checkout@v4 etc.
   - Обновите до:
     - `actions/checkout@v5`
     - `actions/setup-java@v5`
     - `gradle/actions/setup-gradle@v5`
   - `android.suppressUnsupportedCompileSdk=35` — добавьте в `gradle.properties`, чтобы убрать AGP warning (AGP 8.3.2 тестировался до 34).

---

## 5. Файлы, затронутые серией фиксов (последние 10 коммитов)

| commit | файл | суть |
|---|---|---|
| c4b4e9a | desync.h | убран bodyless `gen_offset` |
| 44e9095 | extend.h | `udp_hook` size_t → ssize_t |
| 16a0309 | extend.c | forward `protect()` |
| 51a4538 | mpool.h | break circular mpool↔params |
| 578528a | desync.c | forward-declare `gen_offset()` |
| 4e07302 | conev.h, packets.h | restore includes |
| 0a2259e | kavl.h | добавлен `KAVL_HEAD`, частичный `KAVL_INIT2` |
| 835a38c | kavl.h, conev.h, proxy.h | full KAVL_INIT2 + wrappers, `_POLLDEF`, SOCKS defs |
| **911247a** | **kavl.h** | **kavl_insert return __type*** |

---

## 6. Рекомендованный следующий PR

1. Заменить `kavl.h` на upstream: https://github.com/attractivechaos/klib/blob/master/kavl.h
   или https://raw.githubusercontent.com/hufrea/byedpi/main/kavl.h
   + синхронизировать `mpool.h/.c`, `params.h`, `packets.h` целиком из byedpi v0.14+
2. Починить warnings:
   - `extend.c:399` cast
   - `packets.c:45` nonstring guard
3. Добавить в CI matrix: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` — сейчас падает только первый ABI останавливает билд, но другие могут иметь отличия.
4. Включить `-Werror=implicit-function-declaration` явно (уже есть в NDK 27 по умолчанию) — хорошо, ловит такие баги рано.
5. Отозвать утёкший PAT и перейти на `GITHUB_TOKEN` в workflow + Deploy keys для пуша, если нужен autopush.

---

## 7. Артефакты успешной сборки

Run #75:
- Status: **success**
- Duration: 1m 16s
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- NDK: 27.0.12077973
- CMake: 3.22.1
- ABIs: arm64-v8a, armeabi-v7a, x86, x86_64 (судя по build.gradle.kts)

---

**Вывод:** ветка master теперь собирается. Корневая причина последнего падения — несоответствие сигнатуры `kavl_insert()` между кастомным `kavl.h` (void) и `mpool.c` (ожидает `struct elem*`). Исправлено возвратом указателя. Проект жив, но native-слой byedpi сильно фрагментирован — настоятельно рекомендую синхронизацию с upstream сабмодулем, иначе следующие обновления принесут новые рассинхроны.

—
Arena Agent CI analysis, 2026-07-07
