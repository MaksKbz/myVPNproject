# BUILD_ERRORS.md — Диагностика сбоев сборки (NDK 27, Clang)

Репозиторий: `MaksKbz/myVPNproject`  
Дата: 2026-07-07  
Статус: ❌ сборка падает на `assembleDebug` (runs #61–#70)

---

## 1. `conev.h` — отсутствуют `#include` имена файлов

**Файл:** `app/src/main/jni/byedpi/conev.h`

### Симптом
Clang NDK 27 падает с ошибкой:
```
fatal error: expected filename after '#include'
```
Или компилируется без ошибки препроцессора, но потом линковщик не видит `struct pollfd`, `struct epoll_event`, `EPOLLIN` и т.д.

### Причина
В файле присутствуют строки вида:
```c
#include
#include
#include
```
— то есть директивы `#include` **без указания имени заголовочного файла**. Это артефакт некорректного сохранения файла (вероятно, при ручном редактировании через GitHub Web UI — имена файлов были потеряны).

### Что должно быть (сравнение с upstream hufrea/byedpi)

| Место в файле | Нужный include |
|---|---|
| После `#include "params.h"` (до `#ifndef __linux__`) | `#include <sys/types.h>` |
| После `#define NOEPOLL` | `#include <sys/socket.h>` |
| Перед `#ifndef NOEPOLL` | `#include <unistd.h>` |
| Внутри `#ifndef NOEPOLL` | `#include <sys/epoll.h>` |
| Внутри `#else` (NOEPOLL) | `#include <poll.h>` |
| В начале структурных объявлений | `#include <stdbool.h>` |

### Исправление
Полностью заменить `conev.h` на корректную версию (см. раздел «Патчи» ниже).

---

## 2. `desync.c` — forward declaration `gen_offset` ✅ ИСПРАВЛЕНО (commit 578528a)

**Файл:** `app/src/main/jni/byedpi/desync.c`

Функция `get_tcp_fake()` вызывала `gen_offset()` до её определения.  
Clang NDK 27 трактует это как hard error.  
**Исправлено** добавлением прототипа после `#include "error.h"`.

---

## 3. `android.yml` — двойной источник пути к NDK ✅ ИСПРАВЛЕНО (commit 578528a)

**Файл:** `.github/workflows/android.yml`

Строка `ndk.dir=...` в `local.properties` конфликтовала с `ndkVersion` в `build.gradle.kts`, вызывая CXX5106 и CXX1300.  
**Исправлено** — `ndk.dir` убран, единственный источник — `ndkVersion`.

---

## 4. `packets.h` — прототипы `struct packet` используются до определения типа

**Файл:** `app/src/main/jni/byedpi/packets.h`

Функции в конце файла:
```c
int fake_tls_init(struct packet *pkt, size_t len);
int fake_http_init(struct packet *pkt);
int fake_udp_init(struct packet *pkt, size_t len);
```
объявляют `struct packet *`, но `struct packet` определён в `params.h`, который **не включён** в `packets.h`.  
Любой `.c` файл, включающий `packets.h` до `params.h`, получит:
```
error: unknown type name 'packet'
```

### Исправление
Добавить в начало `packets.h` (после `#include <stdbool.h>`):
```c
#include "params.h"
```
**Или** убрать `fake_*_init` из `packets.h` и перенести их прототипы в отдельный `fake_init.h`.

---

## 5. `conev.h` — `struct eval` содержит поле `int to_count` — рассинхрон с upstream

**Файл:** `app/src/main/jni/byedpi/conev.h`

В upstream (hufrea/byedpi `main`) поле `int to_count;` закомментировано:
```c
// int to_count;
```
В нашем репозитории оно присутствует как активное поле.  
Если `extend.c` или `proxy.c` из upstream не используют `to_count`, а наш `ciadpi_jni.c` его использует — размер структуры расходится между единицами компиляции → UB и/или linker error.

### Исправление
Синхронизировать `struct eval` с upstream: закомментировать `int to_count;` или убедиться что все `.c` файлы используют одну версию структуры.

---

## Приоритет исправлений

| # | Файл | Критичность | Статус |
|---|---|---|---|
| 1 | `conev.h` — пустые `#include` | 🔴 BLOCKER | ❌ не исправлено |
| 2 | `packets.h` — `struct packet` без include | 🔴 BLOCKER | ❌ не исправлено |
| 3 | `conev.h` — `int to_count` рассинхрон | 🟡 WARNING | ❌ не исправлено |
| 4 | `desync.c` — forward declaration | ✅ исправлено | commit 578528a |
| 5 | `android.yml` — ndk.dir дубль | ✅ исправлено | commit 578528a |

---

## Патч для `conev.h` (полная замена)

```c
/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef CONEV_H
#define CONEV_H
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include "params.h"

#ifndef __linux__
#define NOEPOLL
#endif

#ifndef _WIN32
#include <sys/types.h>
#include <sys/socket.h>
#include <unistd.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#ifndef NOEPOLL
#include <sys/epoll.h>
#define POLLIN   EPOLLIN
#define POLLOUT  EPOLLOUT
#define POLLERR  EPOLLERR
#define POLLHUP  EPOLLHUP
#define POLLRDHUP EPOLLRDHUP
#else
#include <poll.h>
#endif
#endif  /* !_WIN32 */

#ifndef POLLRDHUP
#define POLLRDHUP 0
#endif

#define POLLTIMEOUT 0
#define MAX_BUFF_INP 8

struct poolhd;
struct eval;
typedef int (*evcb_t)(struct poolhd *, struct eval *, int);

#define FLAG_S4   1
#define FLAG_S5   2
#define FLAG_CONN 4
#define FLAG_HTTP 8

struct buffer {
    size_t        size;
    unsigned int  offset;
    ssize_t       lock;
    struct buffer *next;
    char          data[];
};

struct eval {
    int   fd;
    int   index;
    unsigned long long mod_iter;
    evcb_t cb;
    long  tv_ms;
    struct eval *tv_next, *tv_prev;
    evcb_t after_conn_cb;
    int   conn_state;
    struct eval   *pair;
    struct buffer *buff, *sq_buff;
    int   flag;
    union sockaddr_u addr;
    char *host;
    int   host_len;
    ssize_t recv_count;
    ssize_t round_sent;
    unsigned int round_count;
    struct desync_params *dp;
    uint64_t dp_mask;
    int   detect;
    bool  mark;
    /* int to_count; */  /* removed — sync with upstream hufrea/byedpi */
    int   tls_rec_size;
    int   tls_rec_pos;
    uint8_t tls_rec[5];
    bool  restore_ttl;
    bool  restore_md5;
    char *restore_fake;
    size_t restore_fake_len;
    const char *restore_orig;
    size_t restore_orig_len;
    unsigned int part_sent;
};

struct poolhd {
    int  max;
    int  count;
    int  efd;
    struct eval **links;
    struct eval  *items;
#ifndef NOEPOLL
    struct epoll_event *pevents;
#else
    struct pollfd      *pevents;
#endif
    unsigned long long iters;
    bool brk;
    struct eval *tv_start, *tv_end;
    struct buffer *root_buff;
    int   buff_count;
};

struct poolhd *init_pool(int count);
struct eval   *add_event(struct poolhd *pool, evcb_t cb, int fd, int e);
struct eval   *add_pair(struct poolhd *pool, struct eval *val, int sfd, int e);
void           del_event(struct poolhd *pool, struct eval *val);
void           destroy_pool(struct poolhd *pool);
struct eval   *next_event(struct poolhd *pool, int *offs, int *type, int ms);
int            mod_etype(struct poolhd *pool, struct eval *val, int type);
void           set_timer(struct poolhd *pool, struct eval *val, long ms);
void           remove_timer(struct poolhd *pool, struct eval *val);
void           loop_event(struct poolhd *pool);
struct buffer *buff_pop(struct poolhd *pool, size_t size);
void           buff_push(struct poolhd *pool, struct buffer *buff);
void           buff_destroy(struct buffer *root);

static inline struct buffer *buff_ppop(struct poolhd *pool, size_t size) {
    struct buffer *b = buff_pop(pool, size);
    if (b) buff_push(pool, b);
    return b;
}

#endif  /* CONEV_H */
```

## Патч для `packets.h` (добавить `#include "params.h"`)

В начало `packets.h`, после `#include <stdbool.h>`, добавить:
```c
/* params.h defines struct packet, needed by fake_*_init prototypes */
#include "params.h"
```
