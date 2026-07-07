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

/* ── SOCKS5 constants ─────────────────────────────────────────────────── */
#define S_VER5       0x05
#define S_VER4       0x04

#define S_AUTH_NONE  0x00
#define S_AUTH_BAD   0xFF

#define S_CMD_CONN   0x01
#define S_CMD_AUDP   0x03

#define S_ATP_I4     0x01
#define S_ATP_ID     0x03
#define S_ATP_I6     0x04

#define S_ER_OK      0x00
#define S_ER_GEN     0x01
#define S_ER_CONN    0x05
#define S_ER_ATP     0x08
#define S_ER_HOST    0x04
#define S_ER_NET     0x03
#define S_ER_CMD     0x07

/* ── SOCKS4 constants ─────────────────────────────────────────────────── */
#define S4_OK  0x5a
#define S4_ER  0x5b

/* ── SOCKS5 request/reply structures ──────────────────────────────────── */
struct s5_req {
    uint8_t ver;
    uint8_t cmd;
    uint8_t rsv;
    uint8_t atp;
    union {
        struct { struct in_addr  ip;  uint16_t port; } i4;
        struct { struct in6_addr ip;  uint16_t port; } i6;
        struct { uint8_t len; char domain[255]; uint16_t port; } id;
    } dst;
} __attribute__((packed));

struct s5_rep {
    uint8_t ver;
    uint8_t code;
    uint8_t rsv;
    uint8_t atp;
    struct in_addr  bnd_addr;
    uint16_t        bnd_port;
} __attribute__((packed));

/* Size helpers */
#define S_SIZE_I4   (4 + 4 + 2)  /* ver+cmd+rsv+atp + IPv4 + port */
#define S_SIZE_I6   (4 + 16 + 2)
#define S_SIZE_ID   (4 + 1 + 2)  /* +domain_len inside */
#define S_SIZE_MIN  S_SIZE_I4

/* ── SOCKS4 request structure ─────────────────────────────────────────── */
struct s4_req {
    uint8_t         ver;
    uint8_t         cmd;
    uint16_t        port;
    struct in_addr  i4;
    /* user-id string follows */
} __attribute__((packed));

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

#endif /* PROXY_H */
