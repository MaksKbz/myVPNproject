#include "mpool.h"
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdio.h>
#include <stddef.h>

static int bit_cmp(const struct elem *p, const struct elem *q) {
    int len = q->len < p->len ? q->len : p->len;
    int df = len % 8, bytes = len / 8;
    int cmp = memcmp(p->data, q->data, bytes);
    if (cmp || !df) { return cmp; }
    uint8_t c1 = p->data[bytes] >> (8 - df);
    uint8_t c2 = q->data[bytes] >> (8 - df);
    if (c1 != c2) { if (c1 < c2) return -1; else return 1; }
    return 0;
}

static int byte_cmp(const struct elem *p, const struct elem *q) {
    if (p->len != q->len) { return p->len < q->len ? -1 : 1; }
    return memcmp(p->data, q->data, p->len);
}

static int host_cmp(const struct elem *p, const struct elem *q) {
    int len = q->len < p->len ? q->len : p->len;
    char *pd = p->data + p->len, *qd = q->data + q->len;
    while (len-- > 0) {
        if (*--pd != *--qd) { return *pd < *qd ? -1 : 1; }
    }
    if (p->len == q->len ||
            (p->len > q->len ? pd[-1] : qd[-1]) == '.')
        return 0;
    return p->len > q->len ? 1 : -1;
}

static int scmp(const struct elem *p, const struct elem *q) {
    switch (p->cmp_type) {
        case CMP_BITS: return bit_cmp(p, q);
        case CMP_HOST: return host_cmp(p, q);
        default:       return byte_cmp(p, q);
    }
}

KAVL_INIT(my, struct elem, head, scmp)

struct mphdr *mem_pool(unsigned short flags, unsigned char cmp_type) {
    struct mphdr *hdr = calloc(1, sizeof(struct mphdr));
    if (hdr) { hdr->flags = flags; hdr->cmp_type = cmp_type; }
    return hdr;
}

void *mem_get(const struct mphdr *hdr, const char *str, int len) {
    struct elem temp = { .cmp_type = hdr->cmp_type, .len = len, .data = (char *)str };
    return kavl_find(my, hdr->root, &temp, 0);
}

static void destroy_elem(struct mphdr *hdr, struct elem *e) {
    if (!(hdr->flags & MF_STATIC)) { free(e->data); }
    if (hdr->flags & MF_EXTRA)    { free(((struct elem_ex *)e)->extra); }
    free(e);
}

void *mem_add(struct mphdr *hdr, char *str, int len, size_t struct_size) {
    struct elem *v, *e = calloc(1, struct_size);
    if (!e) { return 0; }
    e->len = len;
    e->cmp_type = hdr->cmp_type;
    e->data = str;
    v = kavl_insert(my, &hdr->root, e, 0);
    while (e != v && e->len < v->len) {
        mem_delete(hdr, v->data, v->len);
        v = kavl_insert(my, &hdr->root, e, 0);
    }
    if (e != v) { destroy_elem(hdr, e); }
    else hdr->count++;
    return v;
}

void mem_delete(struct mphdr *hdr, const char *str, int len) {
    struct elem temp = { .cmp_type = hdr->cmp_type, .len = len, .data = (char *)str };
    struct elem *e = kavl_erase(my, &hdr->root, &temp, 0);
    if (!e) { return; }
    destroy_elem(hdr, e);
    hdr->count--;
}

void mem_destroy(struct mphdr *hdr) {
    while (hdr && hdr->root) {
        struct elem *e = kavl_erase_first(my, &hdr->root);
        if (!e) { break; }
        destroy_elem(hdr, e);
    }
    free(hdr);
}

void dump_cache(struct mphdr *hdr, FILE *out, struct desync_params *dp) {
    (void)hdr; (void)out; (void)dp;
}

void load_cache(struct mphdr *hdr, FILE *in, struct desync_params *dp) {
    (void)hdr; (void)in; (void)dp;
}
