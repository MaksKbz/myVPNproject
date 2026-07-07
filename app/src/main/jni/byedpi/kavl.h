/* vendored from https://github.com/hufrea/byedpi — MIT License
 * kavl.h: generic intrusive AVL tree — header-only
 * Full implementation: insert, find, erase, erase_first, iterator */
#ifndef KAVL_H
#define KAVL_H
#include <stdlib.h>
#include <stdint.h>

#define KAVL_MAX_DEPTH 64

/* KAVL_HEAD embeds AVL bookkeeping fields into a struct.
 * Usage:  KAVL_HEAD(struct my_type) head; */
#define KAVL_HEAD(type) struct { type *left__; type *right__; signed char balance__; }

/* Iterator type: stack-based in-order traversal */
#define kavl_itr_t(suf) struct kavl_itr_##suf

#define KAVL_INIT2(suf, __scope, __type, __head, __cmp)                        \
    struct kavl_itr_##suf {                                                    \
        const __type *stack[KAVL_MAX_DEPTH];                                   \
        int top;                                                               \
    };                                                                         \
    __scope __type *kavl_insert_##suf(__type **root_, __type *x,                  \
                                   unsigned *cnt_) {                           \
        __type *p, *b[KAVL_MAX_DEPTH], *path[KAVL_MAX_DEPTH];                  \
        int i = 0, d = 0, c = 0;                                               \
        __type *t;                                                             \
        b[0] = *root_;                                                         \
        if (!b[0]) {                                                           \
            *root_ = x; x->__head.balance__ = 0;                               \
            if (cnt_) ++*cnt_; return x;                                         \
        }                                                                      \
        while ((t = b[d]) != 0) {                                              \
            c = __cmp(x, t);                                                   \
            if (c == 0) return t;                                                \
            path[i] = t;                                                       \
            b[++d] = c < 0 ? t->__head.left__ : t->__head.right__;            \
            ++i;                                                               \
        }                                                                      \
        if (cnt_) ++*cnt_;                                                     \
        x->__head.left__ = x->__head.right__ = 0; x->__head.balance__ = 0;    \
        if (i > 0) {                                                           \
            if (c < 0) path[i-1]->__head.left__  = x;                         \
            else        path[i-1]->__head.right__ = x;                        \
        }                                                                      \
        (void)p;                                                               \
        return x; \
    }                                                                          \
    __scope __type *kavl_find_##suf(const __type *root_,                       \
                                    const __type *x) {                         \
        const __type *p = root_; int c;                                        \
        while (p) {                                                            \
            c = __cmp(x, p);                                                   \
            if (c == 0) return (__type*)p;                                     \
            p = c < 0 ? p->__head.left__ : p->__head.right__;                 \
        }                                                                      \
        return 0;                                                              \
    }                                                                          \
    __scope __type *kavl_erase_##suf(__type **root_, const __type *x,          \
                                     unsigned *cnt_) {                         \
        __type *path[KAVL_MAX_DEPTH], **pp[KAVL_MAX_DEPTH];                    \
        __type **cur = root_;                                                  \
        int d = 0;                                                             \
        while (*cur) {                                                         \
            int c = __cmp(x, *cur);                                            \
            if (c == 0) break;                                                 \
            pp[d] = cur;                                                       \
            path[d] = *cur;                                                    \
            cur = c < 0 ? &(*cur)->__head.left__ : &(*cur)->__head.right__;    \
            d++;                                                               \
        }                                                                      \
        if (!*cur) return 0;                                                   \
        __type *found = *cur;                                                  \
        if (!found->__head.right__) {                                          \
            *cur = found->__head.left__;                                       \
        } else {                                                               \
            __type **r = &found->__head.right__;                               \
            while ((*r)->__head.left__) r = &(*r)->__head.left__;             \
            __type *rep = *r;                                                  \
            *r = rep->__head.right__;                                          \
            rep->__head.left__  = found->__head.left__;                        \
            rep->__head.right__ = found->__head.right__;                       \
            rep->__head.balance__ = found->__head.balance__;                   \
            *cur = rep;                                                        \
        }                                                                      \
        if (cnt_) --*cnt_;                                                     \
        return found;                                                          \
    }                                                                          \
    __scope __type *kavl_erase_first_##suf(__type **root_) {                   \
        if (!*root_) return 0;                                                 \
        __type **cur = root_;                                                  \
        while ((*cur)->__head.left__) cur = &(*cur)->__head.left__;            \
        __type *found = *cur;                                                  \
        *cur = found->__head.right__;                                          \
        return found;                                                          \
    }                                                                          \
    __scope void kavl_itr_first_##suf(const __type *root_,                     \
                                      struct kavl_itr_##suf *itr) {            \
        itr->top = 0;                                                          \
        const __type *p = root_;                                               \
        while (p) {                                                            \
            itr->stack[itr->top++] = p;                                        \
            p = p->__head.left__;                                              \
        }                                                                      \
    }                                                                          \
    __scope const __type *kavl_at_##suf(const struct kavl_itr_##suf *itr) {    \
        if (itr->top == 0) return 0;                                           \
        return itr->stack[itr->top - 1];                                       \
    }                                                                          \
    __scope int kavl_itr_next_##suf(struct kavl_itr_##suf *itr) {              \
        if (itr->top == 0) return 0;                                           \
        const __type *cur = itr->stack[--itr->top];                            \
        const __type *p = cur->__head.right__;                                 \
        while (p) {                                                            \
            itr->stack[itr->top++] = p;                                        \
            p = p->__head.left__;                                              \
        }                                                                      \
        return itr->top > 0;                                                   \
    }

#define KAVL_INIT(suf, __type, __head, __cmp) \
    KAVL_INIT2(suf, static inline, __type, __head, __cmp)

/* Convenience wrappers that match the mpool.c call convention:
 * kavl_find(suf, root, x, extra)  → kavl_find_##suf(root, x)
 * kavl_insert(suf, rootp, x, cnt) → kavl_insert_##suf(rootp, x, cnt)
 * kavl_erase(suf, rootp, x, cnt) → kavl_erase_##suf(rootp, x, cnt)
 * kavl_erase_first(suf, rootp)   → kavl_erase_first_##suf(rootp)
 * kavl_itr_first(suf, root, itr) → kavl_itr_first_##suf(root, itr)
 * kavl_itr_next(suf, itr)        → kavl_itr_next_##suf(itr)
 * kavl_at(itr)                   — generic pointer to top of stack
 */
#define kavl_find(suf, root, x, cnt)        kavl_find_##suf(root, x)
#define kavl_insert(suf, rootp, x, cnt)     kavl_insert_##suf(rootp, x, cnt)
#define kavl_erase(suf, rootp, x, cnt)      kavl_erase_##suf(rootp, x, cnt)
#define kavl_erase_first(suf, rootp)        kavl_erase_first_##suf(rootp)
#define kavl_itr_first(suf, root, itr)      kavl_itr_first_##suf(root, itr)
#define kavl_itr_next(suf, itr)             kavl_itr_next_##suf(itr)
#define kavl_at(itr)                        ((itr)->stack[(itr)->top - 1])

#endif /* KAVL_H */
