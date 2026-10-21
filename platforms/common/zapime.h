/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef ZAPIME_CORE_H
#define ZAPIME_CORE_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct { uint8_t *data; size_t len; } ZapBuffer;
uint32_t zapime_abi_version(void);
/* JSON UTF-8；输入指针在调用期间可读。会话只允许创建线程使用。 */
uint64_t zapime_open(const uint8_t *data, size_t len);
ZapBuffer zapime_request(uint64_t handle, const uint8_t *data, size_t len);
bool zapime_close(uint64_t handle);
/* 只能传入原样返回的输出，不得重复释放。输出没有 NUL 结尾。 */
void zapime_buffer_free(ZapBuffer buffer);
#ifdef __cplusplus
}
#endif
#endif
