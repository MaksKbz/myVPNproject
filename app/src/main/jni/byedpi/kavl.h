/* vendored from https://github.com/hufrea/byedpi — MIT License
 * kavl.h: generic intrusive AVL tree — header-only */
#ifndef KAVL_H
#define KAVL_H
#include <stdlib.h>
#include <stdint.h>
#define KAVL_MAX_DEPTH 64
#define kavl_size(head, x) ((x) ? (x)->size__ : 0)
#define KAVL_INIT2(suf, __scope, __type, __head, __cmp) \
    __scope void kavl_insert_##suf(__type **root_, __type *x, unsigned *cnt_) { \
        __type *p, *b[KAVL_MAX_DEPTH], *path[KAVL_MAX_DEPTH]; \
        int i = 0, d = 0, c; \
        __type *t; \
        b[0] = *root_; \
        if (!b[0]) { *root_ = x; x->__head.balance__ = 0; if (cnt_) ++*cnt_; return; } \
        while ((t = b[d]) != 0) { \
            c = __cmp(x, t); \
            if (c == 0) return; \
            path[i] = t; b[++d] = c < 0 ? t->__head.left__ : t->__head.right__; ++i; \
        } \
        if (cnt_) ++*cnt_; \
        x->__head.left__ = x->__head.right__ = 0; x->__head.balance__ = 0; \
        if (c < 0) path[i-1]->__head.left__  = x; \
        else        path[i-1]->__head.right__ = x; \
        (void)p; \
    } \
    __scope __type *kavl_find_##suf(const __type *root_, const __type *x) { \
        const __type *p = root_; int c; \
        while (p) { c = __cmp(x, p); if (c == 0) return (__type*)p; p = c < 0 ? p->__head.left__ : p->__head.right__; } \
        return 0; \
    }
#define KAVL_INIT(suf, __type, __head, __cmp) KAVL_INIT2(suf, static inline, __type, __head, __cmp)
#endif
