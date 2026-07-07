#define _GNU_SOURCE
#include "desync.h"
#include <string.h>
#include <stdlib.h>
#ifndef _WIN32
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <sys/mman.h>
#include <signal.h>
#ifndef __linux__
#include <netinet/tcp.h>
#else
#include <linux/tcp.h>
#include <linux/filter.h>
#endif
#else
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#endif
#include "packets.h"
#include "error.h"

#define DEFAULT_TTL 8
#define ERR_WAIT -12

int setttl(int fd, int ttl) {
    int ret6 = setsockopt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, (char *)&ttl, sizeof(ttl));
    int ret4 = setsockopt(fd, IPPROTO_IP, IP_TTL, (char *)&ttl, sizeof(ttl));
    if (ret4 && ret6) {
        uniperror("setttl");
        return -1;
    }
    return 0;
}

#ifdef __linux__
static int drop_sack(int fd) {
    struct sock_filter code[] = {
        { 0x30, 0, 0, 0x0000000c },
        { 0x74, 0, 0, 0x00000004 },
        { 0x35, 0, 3, 0x0000000b },
        { 0x30, 0, 0, 0x00000022 },
        { 0x15, 0, 1, 0x00000005 },
        { 0x6,  0, 0, 0x00000000 },
        { 0x6,  0, 0, 0x00040000 },
    };
    struct sock_fprog bpf = {
        .len = sizeof(code)/sizeof(*code),
        .filter = code
    };
    if (setsockopt(fd, SOL_SOCKET, SO_ATTACH_FILTER,
            (char *)&bpf, sizeof(bpf)) == -1) {
        uniperror("setsockopt SO_ATTACH_FILTER");
        return -1;
    }
    return 0;
}
#endif

int pre_desync(int sfd, struct desync_params *dp) {
#ifdef __linux__
    if (dp->drop_sack && drop_sack(sfd)) {
        return -1;
    }
#endif
    return 0;
}

int post_desync(int sfd, struct desync_params *dp) {
#ifdef __linux__
    int nop = 0;
    if (dp->drop_sack) {
        if (setsockopt(sfd, SOL_SOCKET, SO_DETACH_FILTER, &nop, sizeof(nop)) == -1) {
            uniperror("setsockopt SO_DETACH_FILTER");
            return -1;
        }
    }
#endif
    return 0;
}

static long gen_offset(long pos, int flag, const char *buffer, size_t n,
        long lp, struct proto_info *info);

static void tamp(char *buffer, size_t bfsize, ssize_t *n,
        const struct desync_params *dp, struct proto_info *info);

ssize_t desync(struct poolhd *pool, struct eval *val, struct buffer *buff,
        ssize_t *np, bool *wait);

ssize_t desync_udp(int sfd, char *buffer, ssize_t n,
        const struct sockaddr *dst, struct desync_params *dp);
