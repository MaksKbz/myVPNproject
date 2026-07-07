/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef MPOOL_H
#define MPOOL_H
#include <stddef.h>
typedef struct mphdr mphdr_t;
struct mphdr {
    size_t       key_size;
    size_t       val_size;
    unsigned int count;
    void        *root;
};
struct mphdr *mem_pool_create(size_t key_size, size_t val_size);
void          mem_pool_destroy(struct mphdr *pool);
void         *mem_pool_get(struct mphdr *pool, const void *key);
void         *mem_pool_set(struct mphdr *pool, const void *key);
int           mem_pool_del(struct mphdr *pool, const void *key);
struct mphdr *mem_pool_alloc(size_t key_size, size_t val_size, void *buf, size_t len);
#endif
