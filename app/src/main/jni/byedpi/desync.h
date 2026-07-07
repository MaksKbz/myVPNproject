/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef DESYNC_H
#define DESYNC_H
#include "params.h"
#include "conev.h"
int  socket_mod(int fd);
int  connect_hook(struct poolhd *pool, struct eval *val,
                  const union sockaddr_u *dst, evcb_t next);
int  on_connerr(struct poolhd *pool, struct eval *val);
int  on_timeout(struct poolhd *pool, struct eval *val);
void dump_all_cache(void);
#endif
