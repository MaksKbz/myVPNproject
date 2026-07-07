/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef EXTEND_H
#define EXTEND_H
#include "params.h"
#include "conev.h"

int  socket_mod(int fd);
int  protect(int conn_fd, const char *path);

ssize_t tcp_send_hook(struct poolhd *pool, struct eval *val,
                      struct buffer *buff, ssize_t *n, bool *wait);
ssize_t tcp_recv_hook(struct poolhd *pool, struct eval *val,
                      struct buffer *buff);
ssize_t udp_hook(struct eval *val, char *data, size_t len,
                 const union sockaddr_u *dst);

int  connect_hook(struct poolhd *pool, struct eval *val,
                  const union sockaddr_u *dst, evcb_t next);
int  on_connerr(struct poolhd *pool, struct eval *val);
int  on_timeout(struct poolhd *pool, struct eval *val);
int  on_trigger(int type, struct poolhd *pool,
                struct eval *val, bool client_alive);
void dump_all_cache(void);

#endif
