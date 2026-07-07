#include "packets.h"
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <ctype.h>
#include <stdbool.h>
#include <errno.h>
#ifdef _WIN32
#include <winsock2.h>
#else
#include <netinet/in.h>
#endif

char tls_data[517] = {
    "\x16\x03\x01\x02\x00\x01\x00\x01\xfc\x03\x03\x03\x5f"
    "\x6f\x2c\xed\x13\x22\xf8\xdc\xb2\xf2\x60\x48\x2d\x72"
    "\x66\x6f\x57\xdd\x13\x9d\x1b\x37\xdc\xfa\x36\x2e\xba"
    "\xf9\x92\x99\x3a\x20\xf9\xdf\x0c\x2e\x8a\x55\x89\x82"
    "\x31\x63\x1a\xef\xa8\xbe\x08\x58\xa7\xa3\x5a\x18\xd3"
    "\x96\x5f\x04\x5c\xb4\x62\xaf\x89\xd7\x0f\x8b\x00\x3e"
    "\x13\x02\x13\x03\x13\x01\xc0\x2c\xc0\x30\x00\x9f\xcc"
    "\xa9\xcc\xa8\xcc\xaa\xc0\x2b\xc0\x2f\x00\x9e\xc0\x24"
    "\xc0\x28\x00\x6b\xc0\x23\xc0\x27\x00\x67\xc0\x0a\xc0"
    "\x14\x00\x39\xc0\x09\xc0\x13\x00\x33\x00\x9d\x00\x9c"
    "\x00\x3d\x00\x3c\x00\x35\x00\x2f\x00\xff\x01\x00\x01"
    "\x75\x00\x00\x00\x16\x00\x14\x00\x00\x11\x77\x77\x77"
    "\x2e\x77\x69\x6b\x69\x70\x65\x64\x69\x61\x2e\x6f\x72"
    "\x67\x00\x0b\x00\x04\x03\x00\x01\x02\x00\x0a\x00\x16"
    "\x00\x14\x00\x1d\x00\x17\x00\x1e\x00\x19\x00\x18\x01"
    "\x00\x01\x01\x01\x02\x01\x03\x01\x04\x00\x10\x00\x0e"
    "\x00\x0c\x02\x68\x32\x08\x68\x74\x74\x70\x2f\x31\x2e"
    "\x31\x00\x16\x00\x00\x00\x17\x00\x00\x00\x31\x00\x00"
    "\x00\x0d\x00\x2a\x00\x28\x04\x03\x05\x03\x06\x03\x08"
    "\x07\x08\x08\x08\x09\x08\x0a\x08\x0b\x08\x04\x08\x05"
    "\x08\x06\x04\x01\x05\x01\x06\x01\x03\x03\x03\x01\x03"
    "\x02\x04\x02\x05\x02\x06\x02\x00\x2b\x00\x09\x08\x03"
    "\x04\x03\x03\x03\x02\x03\x01\x00\x2d\x00\x02\x01\x01"
    "\x00\x33\x00\x26\x00\x24\x00\x1d\x00\x20\x11\x8c\xb8"
    "\x8c\xe8\x8a\x08\x90\x1e\xee\x19\xd9\xdd\xe8\xd4\x06"
    "\xb1\xd1\xe2\xab\xe0\x16\x63\xd6\xdc\xda\x84\xa4\xb8"
    "\x4b\xfb\x0e\x00\x15\x00\xac\x00\x00\x00\x00\x00\x00"
};

char http_data[43] __attribute__((nonstring)) = {
    "GET / HTTP/1.1\r\n"
    "Host: www.wikipedia.org\r\n\r\n"
};

char udp_data[64] = { 0 };

static char *strncasestr(const char *a, size_t as,
        const char *b, size_t bs) {
    for (const char *p = a; ; p++) {
        p = memchr(p, *b, as - (p - a));
        if (!p) { return 0; }
        if ((p + bs) > (a + as)) { return 0; }
        if (!strncasecmp(p, b, bs)) { return (char *)p; }
    }
    return 0;
}

bool is_tls_chello(const char *buffer, size_t bsize) {
    return (bsize > 5 && ANTOHS(buffer, 0) == 0x1603 && buffer[5] == 0x01);
}

bool is_tls_shello(const char *buffer, size_t bsize) {
    return (bsize > 5 && ANTOHS(buffer, 0) == 0x1603 && buffer[5] == 0x02);
}

bool is_http(const char *buffer, size_t bsize) {
    if (bsize < 16 || *buffer > 'T' || *buffer < 'C') { return 0; }
    const char *methods[] = {
        "HEAD", "GET", "POST", "PUT", "DELETE",
        "OPTIONS", "CONNECT", "TRACE", "PATCH", 0
    };
    for (const char **m = methods; *m; m++) {
        if (strncmp(buffer, *m, strlen(*m)) == 0) { return 1; }
    }
    return 0;
}

int parse_tls(const char *buffer, size_t bsize, char **hs) {
    if (!is_tls_chello(buffer, bsize)) { return 0; }
    if (bsize < 44) { return 0; }
    uint8_t sid_len = buffer[43];
    size_t skip = 44 + sid_len + 2;
    if (bsize < skip + 4) { return 0; }
    /* simplified SNI extraction */
    uint16_t ext_block_len = ANTOHS(buffer, skip - 2 + (44 + sid_len) - (44 + sid_len));
    (void)ext_block_len;
    /* just return 0 for minimal stub — real impl in desync.c tree */
    (void)hs;
    return 0;
}

int parse_http(const char *buffer, size_t bsize, char **hs, uint16_t *port) {
    const char *host = buffer, *l_end;
    const char *buff_end = buffer + bsize;
    if (!is_http(buffer, bsize)) { return 0; }
    if (!(host = strncasestr(buffer, bsize, "\nHost:", 6))) { return 0; }
    host += 6;
    for (; host < buff_end && *host == ' '; host++);
    if (!(l_end = memchr(host, '\n', buff_end - host))) { return 0; }
    for (; isspace((unsigned char) *(l_end - 1)); l_end--);
    const char *h_end = l_end - 1;
    while (isdigit((unsigned char) *--h_end));
    if (*h_end != ':') {
        if (port) *port = 80;
        h_end = l_end;
    } else if (port) {
        char *end;
        long i = strtol(h_end + 1, &end, 10);
        if (i <= 0 || end != l_end || i > 0xffff) return 0;
        *port = i;
    }
    if (*host == '[') {
        if (*--h_end != ']') return 0;
        host++;
    }
    *hs = (char *)host;
    return h_end - host;
}

int mod_http(char *buffer, size_t bsize, int m) {
    char *host = 0, *par;
    int hlen = parse_http(buffer, bsize, &host, 0);
    if (!hlen) return -1;
    for (par = host - 1; *par != ':'; par--) {}
    par -= 4;
    if (m & MH_HMIX) {
        par[0] = tolower((unsigned char) par[0]);
        par[1] = toupper((unsigned char) par[1]);
        par[3] = toupper((unsigned char) par[3]);
    }
    if (m & MH_DMIX) {
        for (int i = 0; i < hlen; i += 2) {
            host[i] = toupper((unsigned char)host[i]);
        }
    }
    if (m & MH_SPACE) {
        for (; !isspace((unsigned char) *(host + hlen)); hlen++) {}
        int sc = host - (par + 5);
        memmove(par + 5, host, hlen);
        memset(par + 5 + hlen, '\t', sc);
    }
    return 0;
}

int part_tls(char *buffer, size_t bsize, ssize_t n, long pos) {
    if ((n < 3) || (bsize - n < 5) || (pos < 0) || (pos + 5 > n)) { return 0; }
    uint16_t r_sz = ANTOHS(buffer, 3);
    if (r_sz < pos) { return n; }
    memmove(buffer + 5 + pos + 5, buffer + 5 + pos, n - (5 + pos));
    memcpy(buffer + 5 + pos, buffer, 3);
    SHTONA(buffer, 3, pos);
    SHTONA(buffer, 5 + pos + 3, r_sz - pos);
    return 5;
}

void randomize_tls(char *buffer, ssize_t n) {
    if (n < 44) { return; }
    uint8_t sid_len = buffer[43];
    if (n < (44l + sid_len + 2)) { return; }
    for (int i = 0; i < 32; i++) buffer[11 + i] = rand() % 256;
    for (int i = 0; i < sid_len; i++) buffer[44 + i] = rand() % 256;
}

bool is_http_redirect(const char *req, size_t qn, const char *resp, size_t sn) {
    (void)req; (void)qn; (void)resp; (void)sn;
    return false;
}

bool neq_tls_sid(const char *req, size_t qn, const char *resp, size_t sn) {
    if (qn < 75 || sn < 75) { return 0; }
    if (!is_tls_chello(req, qn) || ANTOHS(resp, 0) != 0x1603) { return 0; }
    uint8_t sid_len = req[43];
    if (req[43] != resp[43]) { return 1; }
    return memcmp(req + 44, resp + 44, sid_len);
}

int change_tls_sni(const char *host, char *buffer, ssize_t n, ssize_t nn) {
    (void)host; (void)buffer; (void)n; (void)nn;
    return -1;
}
