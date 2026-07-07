/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef DESYNC_H
#define DESYNC_H
#define STR_MODE
#include <stddef.h>
#include <stdbool.h>
#include <sys/types.h>
#include "conev.h"
#include "params.h"

struct proto_info {
    char init, type;
    int  host_len, host_pos;
};

ssize_t desync(struct poolhd *pool, struct eval *val,
               struct buffer *buff, ssize_t *n, bool *wait);
ssize_t desync_udp(int sfd, char *buffer, ssize_t n,
                   const struct sockaddr *dst,
                   struct desync_params *dp);
int  setttl(int fd, int ttl);
int  pre_desync(int sfd, struct desync_params *dp);
int  post_desync(int sfd, struct desync_params *dp);

#endif
