/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef PROXY_H
#define PROXY_H
#include <stdint.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include "conev.h"
#define SA_SIZE(s) \
    (((const struct sockaddr *)(s))->sa_family == AF_INET6) ? \
    sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in)
void map_fix(union sockaddr_u *addr, char f6);
int  resp_error(int fd, int e, int flag);
int  create_conn(struct poolhd *pool, struct eval *val,
                 const union sockaddr_u *dst, evcb_t next);
int  s5_set_addr(char *buffer, size_t n,
                 const union sockaddr_u *addr, char end);
int  listen_socket(const union sockaddr_u *srv);
int  on_tunnel(struct poolhd *pool, struct eval *val, int etype);
int  on_udp_tunnel(struct poolhd *pool, struct eval *val, int et);
int  on_request(struct poolhd *pool, struct eval *val, int et);
int  on_connect(struct poolhd *pool, struct eval *val, int et);
int  on_ignore(struct poolhd *pool, struct eval *val, int etype);
int  start_event_loop(int srvfd);
int  run(const union sockaddr_u *srv);
#endif
