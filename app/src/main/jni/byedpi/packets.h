/* vendored from https://github.com/hufrea/byedpi — MIT License */
#ifndef PACKETS_H
#define PACKETS_H
#include <stdint.h>
#include <stddef.h>
uint16_t ip_checksum(const void *data, size_t len);
uint16_t tcp_checksum(const void *pseudo, const void *data, size_t len);
int      fake_tls_init(struct packet *pkt, size_t len);
int      fake_http_init(struct packet *pkt);
int      fake_udp_init(struct packet *pkt, size_t len);
#endif
