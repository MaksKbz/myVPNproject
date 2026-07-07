/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef DESYNC_H
#define DESYNC_H
#include "params.h"
#include "conev.h"
int  setttl(int fd, int ttl);
int  pre_desync(int sfd, struct desync_params *dp);
int  post_desync(int sfd, struct desync_params *dp);
ssize_t desync(struct poolhd *pool, struct eval *val,
               struct buffer *buff, ssize_t *np, bool *wait);
ssize_t desync_udp(int sfd, char *buffer, ssize_t n,
                   const struct sockaddr *dst,
                   struct desync_params *dp);
#endif
