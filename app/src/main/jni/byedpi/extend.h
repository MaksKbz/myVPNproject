/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef EXTEND_H
#define EXTEND_H
#include "params.h"
int  tcp_send_hook(struct poolhd *pool, struct eval *val,
                   struct buffer *buff, ssize_t *n, bool *wait);
int  tcp_recv_hook(struct poolhd *pool, struct eval *val, struct buffer *buff);
int  udp_hook(struct eval *val, char *data, size_t len,
              const union sockaddr_u *dst);
int  is_tls_chello(const char *data, size_t n);
int  parse_tls(const char *data, size_t n, char **host);
int  parse_http(const char *data, size_t n, char **host, uint16_t *port);
#endif
