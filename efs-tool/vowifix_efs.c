#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define DIAG_IOCTL_SWITCH_LOGGING 7
#define DIAG_IOCTL_REMOTE_DEV 32
#define DIAG_IOCTL_REGISTER_CALLBACK 37

#define CALLBACK_MODE 6
#define USER_SPACE_DATA_TYPE 0x20

/* Diag 基础与 EFS2 子系统命令字 */
#define DIAG_SUBSYS_CMD 0x4B
#define DIAG_EXT_BUILD_ID 0x7C
#define DIAG_SUBSYS_FS 0x13

#define EFS2_DIAG_OPEN 0x02
#define EFS2_DIAG_CLOSE 0x03
#define EFS2_DIAG_READ 0x04
#define EFS2_DIAG_WRITE 0x05
#define EFS2_DIAG_STAT 0x0F
#define EFS2_DIAG_SYNC_NO_WAIT 0x30
#define EFS2_DIAG_GET_SYNC_STATUS 0x31

#define EFS2_DIAG_ENOENT 2
#define EFS2_DIAG_OPEN_WRONLY_TRUNC 0x0201
#define EFS2_DEFAULT_MODE 0644

/* 双卡写入路径严格白名单 */
#define EFS_PATH_SLOT0 "/efsprofiles/overideconfig"
#define EFS_PATH_SLOT1 "/efsprofiles/overideconfig_Subscription01"
#define EFS_SE_PATH_SLOT0 "/nv/item_files/ims/qp_ims_service_enablement_config"
#define EFS_SE_PATH_SLOT1 "/nv/item_files/ims/qp_ims_service_enablement_config_Subscription01"
#define DEFAULT_BACKUP_DIR "/data/adb/vowifix"
#define DEFAULT_LOCK_PATH "/data/adb/vowifix/diag.lock"

/* Qualcomm QRTR 与 QMI VS EFS 热刷新参数（无需加载外部动态库） */
#ifndef AF_QIPCRTR
#define AF_QIPCRTR 42
#endif
#define QRTR_PORT_CTRL 0xfffffffeU
#define QRTR_TYPE_NEW_SERVER 4
#define QRTR_TYPE_NEW_LOOKUP 10
#define QMI_VS_SERVICE_ID 0x00e5U
#define QMI_VS_MSG_REFRESH_EFS 0x008eU
#define QMI_HDR_FLAG_REQ 0x00U
#define QMI_HDR_FLAG_RESP 0x02U
#define MCFG_TYPE_SW 1U
#define MCFG_ACTIVATION_REFRESH_ONLY 1U

struct __attribute__((packed)) diag_logging_mode_param_t {
    uint32_t req_mode;
    uint32_t peripheral_mask;
    uint32_t pd_mask;
    uint8_t mode_param;
    uint8_t pad[3];
    int32_t diag_id;
    uint32_t pd_val;
};

typedef enum {
    DIAG_MODE_AUTO = 0,
    DIAG_MODE_REMOTE = 1,
    DIAG_MODE_LOCAL = 2
} diag_mode_opt_t;

typedef struct {
    const char *slot_name;
    const char *efs_path;
    const char *se_path;
    const char *se_backup_prefix;
} slot_target_t;

typedef struct {
    const char *section;
    const char *key;
    const char *value;
} ini_patch_entry_t;

/* 经过 Run 4 / Run 5 实证收敛的最小核心与跨运营商通用防泄露键表 */
static const ini_patch_entry_t ILS_ONLY_PATCHES[] = {
    { "ILS:IlsGeoLocImsWlan", "firstPriorityForLocation", "2" },
    { "ILS:IlsGeoLocImsWlan", "secondPriorityForCountryCode", "2" },
    { "ILS:IlsGeoLocImsWlan", "thirdPriorityForCountryCode", "2" },
    { "ILS:IlsGeoLocImsWlan", "OverrideMCCCountryCode", "1" },
};

static const ini_patch_entry_t UNIVERSAL_PATCHES[] = {
    { "ILS:IlsGeoLocImsWlan", "firstPriorityForLocation", "2" },
    { "ILS:IlsGeoLocImsWlan", "secondPriorityForCountryCode", "2" },
    { "ILS:IlsGeoLocImsWlan", "thirdPriorityForCountryCode", "2" },
    { "ILS:IlsGeoLocImsWlan", "OverrideMCCCountryCode", "1" },
    { "SIPConfig:GeoLocation", "iEnableCellInfoInPANI", "0" },
    { "SIPConfig:FeatureFlags", "IsCNIEnabled", "0" },
};

static const slot_target_t ALL_SLOTS[] = {
    { "slot0", EFS_PATH_SLOT0, EFS_SE_PATH_SLOT0, "slot0_se" },
    { "slot1", EFS_PATH_SLOT1, EFS_SE_PATH_SLOT1, "slot1_se" },
};

typedef struct {
    int fd;
    int32_t token;       /* 外挂 MDM 使用 -1，内置 MPSS 使用 0 */
    uint16_t remote_mask;
    int is_remote;
} diag_ctx_t;

/* ===================== HDLC 与 /dev/diag 通信层 ===================== */

/* 计算 Qualcomm Diag HDLC 帧校验 CRC-16/CCITT (LSB-first) */
static uint16_t crc16_ccitt(const uint8_t *data, int len) {
    uint16_t crc = 0xFFFF;
    for (int i = 0; i < len; i++) {
        crc ^= data[i];
        for (int b = 0; b < 8; b++) {
            crc = (crc & 1) ? ((crc >> 1) ^ 0x8408) : (crc >> 1);
        }
    }
    return crc ^ 0xFFFF;
}

/* 构造带 CRC 与 HDLC 转义字节的完整命令帧 */
static int build_hdlc(const uint8_t *raw, int raw_len, uint8_t *out, int cap) {
    uint16_t crc = crc16_ccitt(raw, raw_len);
    uint8_t tmp[4096];
    if (raw_len + 2 > (int)sizeof(tmp)) return -1;
    memcpy(tmp, raw, (size_t)raw_len);
    tmp[raw_len] = (uint8_t)(crc & 0xFF);
    tmp[raw_len + 1] = (uint8_t)((crc >> 8) & 0xFF);
    int j = 0;
    for (int i = 0; i < raw_len + 2; i++) {
        uint8_t c = tmp[i];
        if (c == 0x7E || c == 0x7D) {
            if (j + 2 >= cap) return -1;
            out[j++] = 0x7D;
            out[j++] = c ^ 0x20;
        } else {
            if (j + 1 >= cap) return -1;
            out[j++] = c;
        }
    }
    if (j + 1 >= cap) return -1;
    out[j++] = 0x7E;
    return j;
}

/* 反转义单个 HDLC 帧并验证 CRC */
static int unwrap_one_hdlc_with_crc(const uint8_t *in, int len, uint8_t *out, int cap) {
    int j = 0;
    for (int i = 0; i < len; i++) {
        uint8_t b = in[i];
        if (b == 0x7D) {
            if (i + 1 >= len) return -1;
            b = in[++i] ^ 0x20;
        }
        if (j >= cap) return -1;
        out[j++] = b;
    }
    if (j < 3) return -1;
    uint16_t calc = crc16_ccitt(out, j - 2);
    uint16_t pkt_crc = (uint16_t)out[j - 2] | ((uint16_t)out[j - 1] << 8);
    if (calc != pkt_crc) return -1;
    return j - 2;
}

/* 判断收到的 HDLC 解包数据是否匹配当前发出的 Diag 请求 */
static int match_response(const uint8_t *cmd, int cmd_len, const uint8_t *rsp, int rlen, int *is_diag_err) {
    *is_diag_err = 0;
    if (rlen <= 0 || cmd_len <= 0) return 0;
    if ((rsp[0] == 0x13 || rsp[0] == 0x14 || rsp[0] == 0x15 || rsp[0] == 0x42) && rlen >= 2) {
        if (rsp[1] == cmd[0]) {
            *is_diag_err = 1;
            return 1;
        }
    }
    if (rsp[0] != cmd[0]) return 0;
    if (cmd[0] == DIAG_SUBSYS_CMD) {
        if (cmd_len >= 4 && rlen >= 4) {
            return rsp[1] == cmd[1] && rsp[2] == cmd[2];
        }
    }
    return 1;
}

/* 自动或按需开启 /dev/diag 并切换到对应外挂 MDM 或内置 MPSS 的 CALLBACK 模式 */
static int diag_open(diag_ctx_t *ctx, diag_mode_opt_t mode_opt) {
    memset(ctx, 0, sizeof(*ctx));
    ctx->fd = open("/dev/diag", O_RDWR | O_CLOEXEC);
    if (ctx->fd < 0) {
        fprintf(stderr, "open(/dev/diag) failed: errno=%d (%s)\n", errno, strerror(errno));
        return -1;
    }

    uint16_t remote_mask = 0;
    if (ioctl(ctx->fd, DIAG_IOCTL_REMOTE_DEV, &remote_mask) < 0) {
        remote_mask = 0;
    }
    ctx->remote_mask = remote_mask;

    if (mode_opt == DIAG_MODE_REMOTE) {
        ctx->is_remote = 1;
        if (remote_mask == 0) remote_mask = 1;
    } else if (mode_opt == DIAG_MODE_LOCAL) {
        ctx->is_remote = 0;
    } else {
        ctx->is_remote = (remote_mask != 0) ? 1 : 0;
    }
    ctx->token = ctx->is_remote ? -1 : 0;

    int client_id = 0;
    ioctl(ctx->fd, DIAG_IOCTL_REGISTER_CALLBACK, &client_id);

    struct diag_logging_mode_param_t mode;
    memset(&mode, 0, sizeof(mode));
    mode.req_mode = CALLBACK_MODE;
    mode.peripheral_mask = 0xFFF;
    mode.pd_mask = 0;
    mode.mode_param = 1;
    mode.diag_id = -22;
    mode.pd_val = ctx->is_remote ? (1u | (((uint32_t)remote_mask) << 1)) : 1u;

    if (ioctl(ctx->fd, DIAG_IOCTL_SWITCH_LOGGING, &mode) < 0) {
        fprintf(stderr, "ioctl(DIAG_IOCTL_SWITCH_LOGGING) failed: errno=%d (%s)\n", errno, strerror(errno));
        close(ctx->fd);
        ctx->fd = -1;
        return -1;
    }
    return 0;
}

static void diag_close(diag_ctx_t *ctx) {
    if (ctx && ctx->fd >= 0) {
        close(ctx->fd);
        ctx->fd = -1;
    }
}

/* 向目标基带通道收发单条同步 Diag 指令 */
static int diag_transact(const diag_ctx_t *ctx, const uint8_t *cmd, int cmd_len,
                         uint8_t *rsp_out, int rsp_cap, int timeout_ms) {
    uint8_t send_buf[4096];
    uint8_t payload[4096];
    int pay_len = build_hdlc(cmd, cmd_len, payload, sizeof(payload));
    if (pay_len < 0 || 8 + pay_len > (int)sizeof(send_buf)) return -1;

    uint32_t dt = USER_SPACE_DATA_TYPE;
    int32_t tok = ctx->token;
    memcpy(send_buf, &dt, 4);
    memcpy(send_buf + 4, &tok, 4);
    memcpy(send_buf + 8, payload, (size_t)pay_len);

    if (write(ctx->fd, send_buf, (size_t)(8 + pay_len)) < 0) {
        fprintf(stderr, "diag write failed: errno=%d (%s)\n", errno, strerror(errno));
        return -1;
    }

    static uint8_t rbuf[65536];
    static uint8_t acc[65536];
    int acc_len = 0;
    struct pollfd pfd = { .fd = ctx->fd, .events = POLLIN };

    for (int tries = 0; tries < 20; tries++) {
        int pr = poll(&pfd, 1, timeout_ms);
        if (pr <= 0) break;
        ssize_t n = read(ctx->fd, rbuf, sizeof(rbuf));
        if (n < 8) continue;

        uint32_t r_dt = 0;
        int32_t num_pkts = 0;
        memcpy(&r_dt, rbuf, 4);
        memcpy(&num_pkts, rbuf + 4, 4);
        if (r_dt != USER_SPACE_DATA_TYPE || num_pkts <= 0) continue;

        int off = 8;
        for (int p = 0; p < num_pkts && off + 8 <= n; p++) {
            int32_t pkt_tok = 0;
            uint32_t pkt_len = 0;
            memcpy(&pkt_tok, rbuf + off, 4);
            memcpy(&pkt_len, rbuf + off + 4, 4);
            off += 8;
            if (off + (int)pkt_len > n) break;

            if (pkt_tok == ctx->token) {
                if (acc_len + (int)pkt_len > (int)sizeof(acc)) acc_len = 0;
                memcpy(acc + acc_len, rbuf + off, pkt_len);
                acc_len += (int)pkt_len;

                int start = 0;
                for (int i = 0; i < acc_len; i++) {
                    if (acc[i] == 0x7E) {
                        if (i > start) {
                            static uint8_t cand[8192];
                            int clen = unwrap_one_hdlc_with_crc(acc + start, i - start, cand, sizeof(cand));
                            int is_err = 0;
                            if (clen > 0 && match_response(cmd, cmd_len, cand, clen, &is_err)) {
                                if (is_err) return -1;
                                int copy_n = clen < rsp_cap ? clen : rsp_cap;
                                memcpy(rsp_out, cand, (size_t)copy_n);
                                return copy_n;
                            }
                        }
                        start = i + 1;
                    }
                }
                if (start > 0 && start <= acc_len) {
                    memmove(acc, acc + start, (size_t)(acc_len - start));
                    acc_len -= start;
                }
            }
            off += (int)pkt_len;
        }
    }
    return -1;
}

/* 封装发送 EFS2 子系统命令 */
static int send_efs_cmd(const diag_ctx_t *ctx, uint8_t subcmd, const uint8_t *body,
                        int body_len, uint8_t *rsp, int rsp_cap) {
    uint8_t cmd[2048];
    if (4 + body_len > (int)sizeof(cmd)) return -1;
    cmd[0] = DIAG_SUBSYS_CMD;
    cmd[1] = DIAG_SUBSYS_FS;
    cmd[2] = subcmd;
    cmd[3] = 0x00;
    if (body_len > 0) memcpy(cmd + 4, body, (size_t)body_len);
    return diag_transact(ctx, cmd, 4 + body_len, rsp, rsp_cap, 1500);
}

/* ===================== EFS2 文件读写、校验与同步层 ===================== */

/* 检查 EFS 路径是否在允许写入的双卡白名单内 */
static int is_allowed_write_path(const char *efs_path) {
    if (!efs_path) return 0;
    return strcmp(efs_path, EFS_PATH_SLOT0) == 0 ||
           strcmp(efs_path, EFS_PATH_SLOT1) == 0 ||
           strcmp(efs_path, EFS_SE_PATH_SLOT0) == 0 ||
           strcmp(efs_path, EFS_SE_PATH_SLOT1) == 0;
}

/* 查询 EFS 文件属性；成功返回 0，不存在返回 -2 (diag_errno=ENOENT)，其他错误返回 -1 */
static int efs_stat(const diag_ctx_t *ctx, const char *efs_path, uint32_t *out_mode, uint32_t *out_size) {
    uint8_t rsp[1024];
    int r = send_efs_cmd(ctx, EFS2_DIAG_STAT, (const uint8_t *)efs_path, (int)strlen(efs_path) + 1, rsp, sizeof(rsp));
    if (r < 8) return -1;
    int32_t diag_errno = 0;
    memcpy(&diag_errno, rsp + 4, 4);
    if (diag_errno != 0) {
        return (diag_errno == EFS2_DIAG_ENOENT) ? -2 : -1;
    }
    uint32_t fmode = 0, fsize = 0;
    if (r >= 12) memcpy(&fmode, rsp + 8, 4);
    if (r >= 16) memcpy(&fsize, rsp + 12, 4);
    if (out_mode) *out_mode = fmode;
    if (out_size) *out_size = fsize;
    return 0;
}

/* 从 EFS 读取完整文件字节到动态分配缓冲区 */
static int efs_read_all_mem(const diag_ctx_t *ctx, const char *efs_path, uint8_t **out_buf, size_t *out_len) {
    uint8_t body[1024], rsp[4096];
    int plen = (int)strlen(efs_path) + 1;
    if (8 + plen > (int)sizeof(body)) return -1;

    int32_t oflag = 0, fmode = 0;
    memcpy(body, &oflag, 4);
    memcpy(body + 4, &fmode, 4);
    memcpy(body + 8, efs_path, (size_t)plen);

    int r = send_efs_cmd(ctx, EFS2_DIAG_OPEN, body, 8 + plen, rsp, sizeof(rsp));
    if (r < 12) return -1;
    int32_t fd_efs = -1, diag_errno = -1;
    memcpy(&fd_efs, rsp + 4, 4);
    memcpy(&diag_errno, rsp + 8, 4);
    if (fd_efs < 0 || diag_errno != 0) {
        return (diag_errno == EFS2_DIAG_ENOENT) ? -2 : -1;
    }

    size_t cap = 4096, total = 0;
    uint8_t *buf = (uint8_t *)malloc(cap);
    if (!buf) {
        send_efs_cmd(ctx, EFS2_DIAG_CLOSE, (const uint8_t *)&fd_efs, 4, rsp, sizeof(rsp));
        return -1;
    }

    uint32_t offset = 0, nbyte = 512;
    int read_ok = 1;
    while (1) {
        memcpy(body, &fd_efs, 4);
        memcpy(body + 4, &nbyte, 4);
        memcpy(body + 8, &offset, 4);
        r = send_efs_cmd(ctx, EFS2_DIAG_READ, body, 12, rsp, sizeof(rsp));
        if (r < 20) { read_ok = 0; break; }
        int32_t bytes_read = 0, r_errno = 0;
        memcpy(&bytes_read, rsp + 12, 4);
        memcpy(&r_errno, rsp + 16, 4);
        if (r_errno != 0) { read_ok = 0; break; }
        if (bytes_read <= 0) break;
        if (20 + bytes_read > r) bytes_read = r - 20;
        if (total + (size_t)bytes_read + 1 > cap) {
            size_t new_cap = cap * 2 + (size_t)bytes_read + 1;
            uint8_t *nb = (uint8_t *)realloc(buf, new_cap);
            if (!nb) { read_ok = 0; break; }
            buf = nb;
            cap = new_cap;
        }
        memcpy(buf + total, rsp + 20, (size_t)bytes_read);
        total += (size_t)bytes_read;
        offset += (uint32_t)bytes_read;
        if ((uint32_t)bytes_read < nbyte) break;
    }
    send_efs_cmd(ctx, EFS2_DIAG_CLOSE, (const uint8_t *)&fd_efs, 4, rsp, sizeof(rsp));
    if (!read_ok) {
        free(buf);
        return -1;
    }
    buf[total] = '\0';
    *out_buf = buf;
    *out_len = total;
    return 0;
}

/* 向白名单内的 EFS 路径写入内容并强制 STAT 与逐字节回读校验 */
static int efs_write_and_verify(const diag_ctx_t *ctx, const char *efs_path, const uint8_t *data, size_t len) {
    if (!is_allowed_write_path(efs_path)) {
        fprintf(stderr, "REFUSED: %s 不在允许写入的白名单路径内\n", efs_path);
        return -1;
    }
    if (len > 1024 * 1024) {
        fprintf(stderr, "REFUSED: 写入数据长度异常 (%zu bytes)\n", len);
        return -1;
    }

    uint8_t body[1024], rsp[4096];
    int plen = (int)strlen(efs_path) + 1;
    if (8 + plen > (int)sizeof(body)) return -1;

    int32_t oflag = EFS2_DIAG_OPEN_WRONLY_TRUNC;
    int32_t fmode = EFS2_DEFAULT_MODE;
    memcpy(body, &oflag, 4);
    memcpy(body + 4, &fmode, 4);
    memcpy(body + 8, efs_path, (size_t)plen);

    int r = send_efs_cmd(ctx, EFS2_DIAG_OPEN, body, 8 + plen, rsp, sizeof(rsp));
    if (r < 12) return -1;
    int32_t fd_efs = -1, diag_errno = -1;
    memcpy(&fd_efs, rsp + 4, 4);
    memcpy(&diag_errno, rsp + 8, 4);
    if (fd_efs < 0 || diag_errno != 0) {
        fprintf(stderr, "OPEN(%s, WRONLY|TRUNC) failed: fd=%d errno=%d\n", efs_path, fd_efs, diag_errno);
        return -1;
    }

    size_t offset = 0;
    int write_ok = 1;
    while (offset < len) {
        size_t chunk = len - offset;
        if (chunk > 512) chunk = 512;

        uint8_t wbody[8 + 512];
        uint32_t uoff = (uint32_t)offset;
        memcpy(wbody, &fd_efs, 4);
        memcpy(wbody + 4, &uoff, 4);
        memcpy(wbody + 8, data + offset, chunk);

        r = send_efs_cmd(ctx, EFS2_DIAG_WRITE, wbody, (int)(8 + chunk), rsp, sizeof(rsp));
        if (r < 20) { write_ok = 0; break; }
        int32_t rsp_fd = -1, bytes_written = -1, w_errno = -1;
        uint32_t rsp_off = 0;
        memcpy(&rsp_fd, rsp + 4, 4);
        memcpy(&rsp_off, rsp + 8, 4);
        memcpy(&bytes_written, rsp + 12, 4);
        memcpy(&w_errno, rsp + 16, 4);
        if (w_errno != 0 || bytes_written != (int32_t)chunk || rsp_fd != fd_efs || rsp_off != uoff) {
            write_ok = 0;
            break;
        }
        offset += chunk;
    }

    int cr = send_efs_cmd(ctx, EFS2_DIAG_CLOSE, (const uint8_t *)&fd_efs, 4, rsp, sizeof(rsp));
    int32_t c_errno = (cr >= 8) ? *(int32_t *)(rsp + 4) : -1;
    if (cr < 8 || c_errno != 0 || !write_ok) {
        fprintf(stderr, "WRITE/CLOSE(%s) failed\n", efs_path);
        return -1;
    }

    uint32_t st_mode = 0, st_size = 0;
    if (efs_stat(ctx, efs_path, &st_mode, &st_size) != 0 || (size_t)st_size != len) {
        fprintf(stderr, "VERIFY FAILED: STAT %s size=%u != expected=%zu\n", efs_path, st_size, len);
        return -1;
    }

    uint8_t *verify_buf = NULL;
    size_t verify_len = 0;
    if (efs_read_all_mem(ctx, efs_path, &verify_buf, &verify_len) != 0 || verify_len != len ||
        memcmp(verify_buf, data, len) != 0) {
        fprintf(stderr, "VERIFY FAILED: read-back mismatch on %s\n", efs_path);
        free(verify_buf);
        return -1;
    }
    free(verify_buf);
    printf("VERIFY PASSED: %s (%zu bytes)\n", efs_path, len);
    return 0;
}

/* 发起 EFS2_DIAG_SYNC_NO_WAIT 并等待 EFS_SYNC_COMPLETE=1 落盘 */
static int efs_sync(const diag_ctx_t *ctx) {
    uint8_t body[16], rsp[1024];
    uint16_t seq = 1;
    memcpy(body, &seq, 2);
    body[2] = '/';
    body[3] = '\0';

    int r = send_efs_cmd(ctx, EFS2_DIAG_SYNC_NO_WAIT, body, 4, rsp, sizeof(rsp));
    if (r < 14) return -1;

    uint32_t sync_token = 0;
    int32_t diag_errno = -1;
    memcpy(&sync_token, rsp + 6, 4);
    memcpy(&diag_errno, rsp + 10, 4);
    if (diag_errno != 0) return -1;

    for (int attempt = 1; attempt <= 30; attempt++) {
        usleep(500 * 1000);
        uint16_t qseq = (uint16_t)(seq + attempt);
        uint8_t qbody[8];
        memcpy(qbody, &qseq, 2);
        memcpy(qbody + 2, &sync_token, 4);
        qbody[6] = '/';
        qbody[7] = '\0';

        r = send_efs_cmd(ctx, EFS2_DIAG_GET_SYNC_STATUS, qbody, 8, rsp, sizeof(rsp));
        if (r < 11) return -1;

        uint8_t sync_status = 0xFF;
        int32_t st_errno = -1;
        if (r == 11) {
            sync_status = rsp[6];
            memcpy(&st_errno, rsp + 7, 4);
        } else if (r >= 15) {
            sync_status = rsp[10];
            memcpy(&st_errno, rsp + 11, 4);
        } else {
            return -1;
        }
        if (st_errno != 0) return -1;
        if (sync_status == 1) {
            printf("SYNC COMPLETE: EFS_SYNC_COMPLETE=1\n");
            return 0;
        }
    }
    fprintf(stderr, "EFS sync timeout\n");
    return -1;
}

/* ===================== 非破坏性 INI 节级就地合并引擎 ===================== */

typedef struct {
    char **items;
    size_t count;
    size_t cap;
} str_list_t;

static int str_list_push(str_list_t *list, const char *s) {
    if (list->count == list->cap) {
        size_t ncap = list->cap ? list->cap * 2 : 64;
        char **ni = (char **)realloc(list->items, ncap * sizeof(char *));
        if (!ni) return -1;
        list->items = ni;
        list->cap = ncap;
    }
    list->items[list->count] = strdup(s ? s : "");
    if (!list->items[list->count]) return -1;
    list->count++;
    return 0;
}

static void str_list_free(str_list_t *list) {
    for (size_t i = 0; i < list->count; i++) free(list->items[i]);
    free(list->items);
    memset(list, 0, sizeof(*list));
}

/* 去除字符串首尾空白 */
static char *trim_span(char *s) {
    while (*s && isspace((unsigned char)*s)) s++;
    size_t n = strlen(s);
    while (n > 0 && isspace((unsigned char)s[n - 1])) s[--n] = '\0';
    return s;
}

/* 判断某行是否为标准 [Section] 头（跳过 SILENTREDIAL_OVERRIDE 起止块避免误插） */
static int parse_section_header(const char *line, char *out_sec, size_t cap) {
    char tmp[512];
    snprintf(tmp, sizeof(tmp), "%s", line);
    char *t = trim_span(tmp);
    size_t len = strlen(t);
    if (len < 3 || t[0] != '[' || t[len - 1] != ']') return 0;
    if (strcmp(t, "[SILENTREDIAL_OVERRIDE]") == 0 || strcmp(t, "[SILENTREDIAL_OVERRIDE_END]") == 0) {
        return 0;
    }
    size_t n = len - 2;
    if (n >= cap) n = cap - 1;
    memcpy(out_sec, t + 1, n);
    out_sec[n] = '\0';
    char *inner = trim_span(out_sec);
    if (inner != out_sec) memmove(out_sec, inner, strlen(inner) + 1);
    return out_sec[0] != '\0';
}

/* 解析 *Key = Value 或 Key = Value 行，提取键名与去空白后的值 */
static int parse_kv_line(const char *line, char *out_key, size_t kcap, char *out_val, size_t vcap) {
    char tmp[2048];
    snprintf(tmp, sizeof(tmp), "%s", line);
    char *t = trim_span(tmp);
    if (t[0] == '\0' || t[0] == '#' || t[0] == ';' || t[0] == '[') return 0;
    if (strncmp(t, "0 ^", 3) == 0 || strncmp(t, "1 ^", 3) == 0) return 0;
    char *eq = strchr(t, '=');
    if (!eq) return 0;

    *eq = '\0';
    char *k = trim_span(t);
    if (k[0] == '*') {
        k++;
        k = trim_span(k);
    }
    if (k[0] == '\0') return 0;
    char *v = trim_span(eq + 1);
    snprintf(out_key, kcap, "%s", k);
    snprintf(out_val, vcap, "%s", v);
    return 1;
}

/* 判断去除空白后是否为 SILENTREDIAL_OVERRIDE 特殊块标记 */
static int is_silentredial_marker(const char *line) {
    char tmp[512];
    snprintf(tmp, sizeof(tmp), "%s", line);
    char *t = trim_span(tmp);
    return strcmp(t, "[SILENTREDIAL_OVERRIDE]") == 0 || strcmp(t, "[SILENTREDIAL_OVERRIDE_END]") == 0;
}

/* 将输入缓冲区剥离 \r 后逐行拆分 */
static int split_lines_no_cr(const uint8_t *buf, size_t len, str_list_t *lines) {
    memset(lines, 0, sizeof(*lines));
    size_t start = 0;
    char line_buf[2048];
    for (size_t i = 0; i <= len; i++) {
        if (i == len || buf[i] == '\n') {
            if (i == len && start == len) break;
            size_t out_len = 0;
            for (size_t p = start; p < i && out_len + 1 < sizeof(line_buf); p++) {
                if (buf[p] != '\r') line_buf[out_len++] = (char)buf[p];
            }
            line_buf[out_len] = '\0';
            if (str_list_push(lines, line_buf) != 0) {
                str_list_free(lines);
                return -1;
            }
            start = i + 1;
        }
    }
    return 0;
}

/* 判断给定补丁条目数组中是否包含某个节名 */
static int entries_have_section(const ini_patch_entry_t *entries, size_t nentries, const char *sec) {
    for (size_t i = 0; i < nentries; i++) {
        if (strcmp(entries[i].section, sec) == 0) return 1;
    }
    return 0;
}

/* 根据当前节名与键名查找目标补丁索引，未命中返回 -1 */
static int find_patch_index(const ini_patch_entry_t *entries, size_t nentries,
                            const char *sec, const char *key) {
    for (size_t i = 0; i < nentries; i++) {
        if (strcmp(entries[i].section, sec) == 0 && strcmp(entries[i].key, key) == 0) {
            return (int)i;
        }
    }
    return -1;
}

/* 严格检查 EFS 文本是否已完全满足所有目标补丁且无多余重复节头 */
static int ini_check_patched(const uint8_t *buf, size_t len,
                             const ini_patch_entry_t *entries, size_t nentries) {
    str_list_t lines;
    if (split_lines_no_cr(buf, len, &lines) != 0) return 0;

    int matched[32];
    int sec_counts[32];
    if (nentries > 32) { str_list_free(&lines); return 0; }
    memset(matched, 0, sizeof(matched));
    memset(sec_counts, 0, sizeof(sec_counts));

    char cur_sec[256] = "";
    char sec[256], k[256], v[512];
    int dup_section = 0;

    for (size_t i = 0; i < lines.count; i++) {
        if (parse_section_header(lines.items[i], sec, sizeof(sec))) {
            snprintf(cur_sec, sizeof(cur_sec), "%s", sec);
            for (size_t j = 0; j < nentries; j++) {
                if (strcmp(entries[j].section, cur_sec) == 0) {
                    sec_counts[j]++;
                    if (sec_counts[j] > 1) dup_section = 1;
                }
            }
            continue;
        }
        /* 若当前行是 SILENTREDIAL_OVERRIDE 起止标记，重置 cur_sec 避免归属错误 */
        if (is_silentredial_marker(lines.items[i])) {
            cur_sec[0] = '\0';
            continue;
        }
        if (cur_sec[0] && parse_kv_line(lines.items[i], k, sizeof(k), v, sizeof(v))) {
            int idx = find_patch_index(entries, nentries, cur_sec, k);
            if (idx >= 0) {
                if (strcmp(v, entries[idx].value) == 0) {
                    matched[idx]++;
                } else {
                    str_list_free(&lines);
                    return 0;
                }
            }
        }
    }
    str_list_free(&lines);
    if (dup_section) return 0;
    for (size_t j = 0; j < nentries; j++) {
        if (matched[j] != 1) return 0;
    }
    return 1;
}

/* 在离开首次目标节前，将该节末尾缺失的目标键紧跟在最后一个非空行之后插入 */
static void flush_section_missing_keys(str_list_t *out_lines, const char *cur_sec,
                                       int cur_sec_is_dup,
                                       const ini_patch_entry_t *entries, size_t nentries,
                                       int *done) {
    if (!cur_sec[0] || cur_sec_is_dup || !entries_have_section(entries, nentries, cur_sec)) {
        return;
    }
    size_t insert_at = out_lines->count;
    while (insert_at > 0 && out_lines->items[insert_at - 1][0] == '\0') {
        insert_at--;
    }
    str_list_t tail = {0};
    for (size_t t = insert_at; t < out_lines->count; t++) {
        str_list_push(&tail, out_lines->items[t]);
        free(out_lines->items[t]);
    }
    out_lines->count = insert_at;

    char fmt[1024];
    for (size_t e = 0; e < nentries; e++) {
        if (strcmp(entries[e].section, cur_sec) == 0 && !done[e]) {
            snprintf(fmt, sizeof(fmt), "*%s = %s", entries[e].key, entries[e].value);
            str_list_push(out_lines, fmt);
            done[e] = 1;
        }
    }
    for (size_t t = 0; t < tail.count; t++) {
        str_list_push(out_lines, tail.items[t]);
    }
    str_list_free(&tail);
}

/* 非破坏性就地合并补丁：原样保留其他全部节与块，消除目标重复节，保证输出纯 LF */
static int ini_merge(const uint8_t *buf, size_t len,
                     const ini_patch_entry_t *entries, size_t nentries,
                     uint8_t **out_buf, size_t *out_len) {
    str_list_t in_lines, out_lines;
    if (split_lines_no_cr(buf, len, &in_lines) != 0) return -1;
    memset(&out_lines, 0, sizeof(out_lines));

    int done[32];
    char seen_sec[32][256];
    size_t seen_cnt = 0;
    if (nentries > 32) { str_list_free(&in_lines); return -1; }
    memset(done, 0, sizeof(done));

    /* 将重复出现的目标节合并到首次目标节内，其余非目标节与特殊块原样保留 */
    char cur_sec[256] = "";
    int cur_sec_is_dup = 0;
    char sec[256], k[256], v[512], fmt[1024];

    for (size_t i = 0; i < in_lines.count; i++) {
        const char *line = in_lines.items[i];
        if (parse_section_header(line, sec, sizeof(sec))) {
            flush_section_missing_keys(&out_lines, cur_sec, cur_sec_is_dup, entries, nentries, done);

            snprintf(cur_sec, sizeof(cur_sec), "%s", sec);
            cur_sec_is_dup = 0;
            if (entries_have_section(entries, nentries, cur_sec)) {
                for (size_t s = 0; s < seen_cnt; s++) {
                    if (strcmp(seen_sec[s], cur_sec) == 0) {
                        cur_sec_is_dup = 1;
                        break;
                    }
                }
                if (!cur_sec_is_dup && seen_cnt < 32) {
                    snprintf(seen_sec[seen_cnt++], sizeof(seen_sec[0]), "%s", cur_sec);
                }
            }
            if (!cur_sec_is_dup) str_list_push(&out_lines, line);
            continue;
        }

        if (is_silentredial_marker(line)) {
            flush_section_missing_keys(&out_lines, cur_sec, cur_sec_is_dup, entries, nentries, done);
            cur_sec[0] = '\0';
            cur_sec_is_dup = 0;
            str_list_push(&out_lines, line);
            continue;
        }

        if (cur_sec_is_dup) {
            /* 重复目标节内跳过已被接管的目标键，保留该节内其他非目标未知键（若有） */
            if (parse_kv_line(line, k, sizeof(k), v, sizeof(v))) {
                int idx = find_patch_index(entries, nentries, cur_sec, k);
                if (idx >= 0) continue;
            }
            if (line[0] == '\0') continue;
            str_list_push(&out_lines, line);
            continue;
        }

        if (cur_sec[0] && parse_kv_line(line, k, sizeof(k), v, sizeof(v))) {
            int idx = find_patch_index(entries, nentries, cur_sec, k);
            if (idx >= 0) {
                if (!done[idx]) {
                    snprintf(fmt, sizeof(fmt), "*%s = %s", entries[idx].key, entries[idx].value);
                    str_list_push(&out_lines, fmt);
                    done[idx] = 1;
                }
                continue;
            }
        }
        str_list_push(&out_lines, line);
    }

    flush_section_missing_keys(&out_lines, cur_sec, cur_sec_is_dup, entries, nentries, done);

    /* 若原文件中完全不存在某些目标节，在末尾统一干净追加 */
    for (size_t e = 0; e < nentries; e++) {
        if (done[e]) continue;
        const char *new_sec = entries[e].section;
        if (out_lines.count > 0 && out_lines.items[out_lines.count - 1][0] != '\0') {
            str_list_push(&out_lines, "");
        }
        snprintf(fmt, sizeof(fmt), "[%s]", new_sec);
        str_list_push(&out_lines, fmt);
        str_list_push(&out_lines, "");
        for (size_t j = e; j < nentries; j++) {
            if (!done[j] && strcmp(entries[j].section, new_sec) == 0) {
                snprintf(fmt, sizeof(fmt), "*%s = %s", entries[j].key, entries[j].value);
                str_list_push(&out_lines, fmt);
                done[j] = 1;
            }
        }
    }

    /* 序列化为纯 LF 字节缓冲区 */
    size_t total = 0;
    for (size_t i = 0; i < out_lines.count; i++) {
        total += strlen(out_lines.items[i]) + 1;
    }
    uint8_t *res = (uint8_t *)malloc(total + 1);
    if (!res) {
        str_list_free(&in_lines);
        str_list_free(&out_lines);
        return -1;
    }
    size_t pos = 0;
    for (size_t i = 0; i < out_lines.count; i++) {
        size_t l = strlen(out_lines.items[i]);
        memcpy(res + pos, out_lines.items[i], l);
        pos += l;
        res[pos++] = '\n';
    }
    res[pos] = '\0';
    str_list_free(&in_lines);
    str_list_free(&out_lines);
    *out_buf = res;
    *out_len = pos;
    return 0;
}

/* ===================== 本地备份与还原文件管理 ===================== */

static int ensure_dir_p(const char *path) {
    char tmp[512];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
            *p = '/';
        }
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
    return 0;
}

static int read_local_file(const char *path, uint8_t **out_buf, size_t *out_len) {
    FILE *fp = fopen(path, "rb");
    if (!fp) return -1;
    if (fseek(fp, 0, SEEK_END) != 0) { fclose(fp); return -1; }
    long sz = ftell(fp);
    if (sz < 0 || sz > 1024 * 1024) { fclose(fp); return -1; }
    rewind(fp);
    uint8_t *buf = (uint8_t *)malloc((size_t)sz + 1);
    if (!buf) { fclose(fp); return -1; }
    if (sz > 0 && fread(buf, 1, (size_t)sz, fp) != (size_t)sz) {
        free(buf);
        fclose(fp);
        return -1;
    }
    buf[sz] = '\0';
    fclose(fp);
    *out_buf = buf;
    *out_len = (size_t)sz;
    return 0;
}

static int write_local_file(const char *path, const uint8_t *buf, size_t len) {
    FILE *fp = fopen(path, "wb");
    if (!fp) return -1;
    if (len > 0 && fwrite(buf, 1, len, fp) != len) {
        fclose(fp);
        return -1;
    }
    fclose(fp);
    return 0;
}

/* 写前保存原件；若换插新运营商卡致使未打补丁原件变化，自动归档旧原件后更新当前原件 */
static int backup_original_if_needed(const char *backup_dir, const char *slot_name,
                                     const uint8_t *orig_buf, size_t orig_len) {
    if (ensure_dir_p(backup_dir) != 0) {
        fprintf(stderr, "mkdir(%s) failed: %s\n", backup_dir, strerror(errno));
        return -1;
    }
    char orig_path[512];
    snprintf(orig_path, sizeof(orig_path), "%s/%s_orig.bin", backup_dir, slot_name);

    uint8_t *old_buf = NULL;
    size_t old_len = 0;
    if (read_local_file(orig_path, &old_buf, &old_len) == 0) {
        if (old_len == orig_len && memcmp(old_buf, orig_buf, orig_len) == 0) {
            free(old_buf);
            return 0;
        }
        char archive_path[512];
        snprintf(archive_path, sizeof(archive_path), "%s/%s_orig.%ld.bin",
                 backup_dir, slot_name, (long)time(NULL));
        if (rename(orig_path, archive_path) == 0) {
            printf("ARCHIVED previous backup -> %s\n", archive_path);
        }
        free(old_buf);
    }

    if (write_local_file(orig_path, orig_buf, orig_len) != 0) {
        fprintf(stderr, "save backup %s failed\n", orig_path);
        return -1;
    }
    printf("BACKUP SAVED: %s (%zu bytes)\n", orig_path, orig_len);
    return 0;
}

/* ===================== qp_ims_service_enablement_config 定点 3 字节解锁 ===================== */

/*
 * 判断高通 qp_ims_service_enablement_config (1024B) 是否仍被运营商锁定了 VoWiFi 开关：
 * - offset 0x08 (8): wifi_call 总开关 (需为 0x01 开启)
 * - offset 0x09 (9): wifi_call_preference (0x00=未设, 0x03=移动优先, 0x04=仅移动 -> 均改为 0x01 Wi-Fi 优先)
 * - offset 0x0D (13): wifi_call_roaming 漫游开关 (0x02=漫游强制关闭 -> 改为 0x01 开启；保留 0x00/0x01)
 */
static int se_needs_patch(const uint8_t *buf, size_t len) {
    if (!buf || len < 16) return 0;
    if (buf[8] != 0x01) return 1;
    if (buf[9] == 0x00 || buf[9] == 0x03 || buf[9] == 0x04) return 1;
    if (buf[13] == 0x02) return 1;
    return 0;
}

/* 就地对 1024B 副本执行最小 3 字节修改，不改变文件总长度与其他字节 */
static int se_apply_patch(uint8_t *buf, size_t len) {
    if (!buf || len < 16) return -1;
    int mods = 0;
    if (buf[8] != 0x01) {
        buf[8] = 0x01;
        mods++;
    }
    if (buf[9] == 0x00 || buf[9] == 0x03 || buf[9] == 0x04) {
        buf[9] = 0x01;
        mods++;
    }
    if (buf[13] == 0x02) {
        buf[13] = 0x01;
        mods++;
    }
    return mods;
}

/* 对共享锁文件加阻塞互斥锁，保障与后台守护及 App 串行访问 /dev/diag 和 QMI 刷新 */
static int acquire_diag_lock(const char *lock_path) {
    const char *slash = strrchr(lock_path, '/');
    if (slash && slash > lock_path) {
        char dir[512];
        size_t dlen = (size_t)(slash - lock_path);
        if (dlen >= sizeof(dir)) dlen = sizeof(dir) - 1;
        memcpy(dir, lock_path, dlen);
        dir[dlen] = '\0';
        if (ensure_dir_p(dir) != 0) {
            fprintf(stderr, "mkdir(%s) for lock failed: %s\n", dir, strerror(errno));
            return -1;
        }
    }
    int lfd = open(lock_path, O_CREAT | O_RDWR | O_CLOEXEC, 0644);
    if (lfd < 0) {
        fprintf(stderr, "open(%s) failed: %s\n", lock_path, strerror(errno));
        return -1;
    }
    while (flock(lfd, LOCK_EX) != 0) {
        if (errno == EINTR) continue;
        fprintf(stderr, "flock(%s) failed: %s\n", lock_path, strerror(errno));
        close(lfd);
        return -1;
    }
    return lfd;
}

/* ===================== 本地自测单元（验证跨运营商合并逻辑） ===================== */

/* ===================== QRTR / QMI VS 原生 EFS 热刷新 ===================== */

struct sockaddr_qrtr_uapi {
    uint16_t sq_family;
    uint16_t sq_pad;
    uint32_t sq_node;
    uint32_t sq_port;
};

struct qrtr_ctrl_pkt_uapi {
    uint32_t cmd;
    union {
        struct {
            uint32_t service;
            uint32_t instance;
            uint32_t node;
            uint32_t port;
        } server;
        uint32_t proc[4];
    };
};

struct __attribute__((packed)) qmi_msg_hdr {
    uint8_t flags;
    uint16_t txn_id;
    uint16_t msg_id;
    uint16_t msg_len;
};

/* 构造 QMI_VS_REFRESH_MODEM_EFS_REQ_MSG_V01 (0x008e) 请求报文 */
static int qmi_build_refresh_efs_req(uint16_t txn_id, uint32_t config_type, uint32_t activation_type,
                                     uint8_t *out, size_t cap) {
    if (!out || cap < sizeof(struct qmi_msg_hdr) + 14) return -1;
    struct qmi_msg_hdr *hdr = (struct qmi_msg_hdr *)out;
    hdr->flags = QMI_HDR_FLAG_REQ;
    hdr->txn_id = txn_id;
    hdr->msg_id = QMI_VS_MSG_REFRESH_EFS;
    hdr->msg_len = 14;

    uint8_t *p = out + sizeof(struct qmi_msg_hdr);
    /* TLV 0x01: config_type (4B LE) */
    p[0] = 0x01;
    p[1] = 0x04;
    p[2] = 0x00;
    p[3] = (uint8_t)(config_type & 0xFF);
    p[4] = (uint8_t)((config_type >> 8) & 0xFF);
    p[5] = (uint8_t)((config_type >> 16) & 0xFF);
    p[6] = (uint8_t)((config_type >> 24) & 0xFF);

    /* TLV 0x02: activation_type (4B LE) */
    p[7] = 0x02;
    p[8] = 0x04;
    p[9] = 0x00;
    p[10] = (uint8_t)(activation_type & 0xFF);
    p[11] = (uint8_t)((activation_type >> 8) & 0xFF);
    p[12] = (uint8_t)((activation_type >> 16) & 0xFF);
    p[13] = (uint8_t)((activation_type >> 24) & 0xFF);
    return (int)(sizeof(struct qmi_msg_hdr) + 14);
}

/* 解析 QMI_VS 应答报文中的 qmi_response_type_v01 (TLV 0x01 或 0x02) */
static int qmi_parse_void_resp(const uint8_t *buf, size_t len, uint16_t expected_txn,
                               uint16_t expected_msg, uint32_t *out_res, uint32_t *out_err) {
    if (!buf || len < sizeof(struct qmi_msg_hdr)) return -1;
    const struct qmi_msg_hdr *rh = (const struct qmi_msg_hdr *)buf;
    if (rh->flags != QMI_HDR_FLAG_RESP || rh->txn_id != expected_txn || rh->msg_id != expected_msg) {
        return -2; /* 非目标事务的报文或事件 */
    }
    if (sizeof(struct qmi_msg_hdr) + (size_t)rh->msg_len > len) return -1;

    size_t off = sizeof(struct qmi_msg_hdr);
    size_t end = off + (size_t)rh->msg_len;
    while (off + 3 <= end) {
        uint8_t t = buf[off];
        uint16_t l = (uint16_t)(buf[off + 1] | ((uint16_t)buf[off + 2] << 8));
        off += 3;
        if (off + l > end) return -1;
        /* QMI_VS 定制响应的 TLV 0x01 含 12 字节结构，前两 u32 为 result 与 error；也兼容标准 4 字节版 */
        if (t == 0x01 || t == 0x02) {
            if (l >= 8) {
                uint32_t r = (uint32_t)buf[off] | ((uint32_t)buf[off + 1] << 8) |
                             ((uint32_t)buf[off + 2] << 16) | ((uint32_t)buf[off + 3] << 24);
                uint32_t e = (uint32_t)buf[off + 4] | ((uint32_t)buf[off + 5] << 8) |
                             ((uint32_t)buf[off + 6] << 16) | ((uint32_t)buf[off + 7] << 24);
                if (out_res) *out_res = r;
                if (out_err) *out_err = e;
                return 0;
            } else if (l >= 4) {
                uint32_t r = (uint32_t)(buf[off] | ((uint16_t)buf[off + 1] << 8));
                uint32_t e = (uint32_t)(buf[off + 2] | ((uint16_t)buf[off + 3] << 8));
                if (out_res) *out_res = r;
                if (out_err) *out_err = e;
                return 0;
            }
        }
        off += l;
    }
    return -1;
}

/* 查询内核 QRTR 路由表定位目标 QMI 服务节点 */
static int qrtr_lookup_service(int fd, uint32_t service, uint32_t *out_node, uint32_t *out_port) {
    struct sockaddr_qrtr_uapi sq;
    socklen_t sl = sizeof(sq);
    memset(&sq, 0, sizeof(sq));
    if (getsockname(fd, (struct sockaddr *)&sq, &sl) < 0) {
        fprintf(stderr, "getsockname(AF_QIPCRTR) failed: %s\n", strerror(errno));
        return -1;
    }
    struct sockaddr_qrtr_uapi ctrl;
    memset(&ctrl, 0, sizeof(ctrl));
    ctrl.sq_family = AF_QIPCRTR;
    ctrl.sq_node = sq.sq_node;
    ctrl.sq_port = QRTR_PORT_CTRL;

    struct qrtr_ctrl_pkt_uapi pkt;
    memset(&pkt, 0, sizeof(pkt));
    pkt.cmd = QRTR_TYPE_NEW_LOOKUP;
    pkt.server.service = service;
    if (sendto(fd, &pkt, sizeof(pkt), 0, (const struct sockaddr *)&ctrl, sizeof(ctrl)) < 0) {
        fprintf(stderr, "QRTR lookup sendto failed: %s\n", strerror(errno));
        return -1;
    }

    int found = 0;
    for (;;) {
        ssize_t n = recv(fd, &pkt, sizeof(pkt), 0);
        if (n < (ssize_t)sizeof(pkt)) break;
        if (pkt.cmd == QRTR_TYPE_NEW_SERVER && pkt.server.service == 0 &&
            pkt.server.instance == 0 && pkt.server.node == 0 && pkt.server.port == 0) {
            break;
        }
        if (pkt.cmd == QRTR_TYPE_NEW_SERVER && pkt.server.service == service && !found) {
            *out_node = pkt.server.node;
            *out_port = pkt.server.port;
            found = 1;
        }
    }
    return found ? 0 : -1;
}

/* 通过纯内核 QRTR 发送高通 MCFG/EFS 热刷新指令（不触发整机重启或 Diag 访问） */
static int qmi_trigger_modem_efs_refresh(uint32_t config_type, uint32_t activation_type) {
    int fd = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (fd < 0) {
        fprintf(stderr, "socket(AF_QIPCRTR) failed: %s\n", strerror(errno));
        return -1;
    }

    struct timeval tv;
    tv.tv_sec = 5;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    uint32_t node = 0, port = 0;
    if (qrtr_lookup_service(fd, QMI_VS_SERVICE_ID, &node, &port) != 0) {
        fprintf(stderr, "QRTR service 0x%x not online\n", QMI_VS_SERVICE_ID);
        close(fd);
        return -1;
    }

    uint8_t req[64];
    const uint16_t txn_id = 1;
    int req_len = qmi_build_refresh_efs_req(txn_id, config_type, activation_type, req, sizeof(req));
    if (req_len < 0) {
        close(fd);
        return -1;
    }

    struct sockaddr_qrtr_uapi dst;
    memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = node;
    dst.sq_port = port;
    if (sendto(fd, req, (size_t)req_len, 0, (const struct sockaddr *)&dst, sizeof(dst)) < 0) {
        fprintf(stderr, "QMI refresh sendto failed: %s\n", strerror(errno));
        close(fd);
        return -1;
    }

    uint8_t rsp[512];
    for (int tries = 0; tries < 8; tries++) {
        ssize_t n = recv(fd, rsp, sizeof(rsp), 0);
        if (n < 0) {
            fprintf(stderr, "QMI refresh recv timeout/error: %s\n", strerror(errno));
            close(fd);
            return -1;
        }
        uint32_t qres = 0xFFFFFFFFU, qerr = 0xFFFFFFFFU;
        int pr = qmi_parse_void_resp(rsp, (size_t)n, txn_id, QMI_VS_MSG_REFRESH_EFS, &qres, &qerr);
        if (pr == -2) continue; /* 忽略路过事件包或非目标响应 */
        close(fd);
        if (pr != 0 || qres != 0 || qerr != 0) {
            fprintf(stderr, "QMI refresh rejected: parse=%d qmi_res=%u qmi_err=%u\n", pr, qres, qerr);
            return -1;
        }
        printf("EFS_REFRESH_OK=1 node=%u port=%u cfg=%u act=%u\n",
               node, port, config_type, activation_type);
        return 0;
    }

    fprintf(stderr, "QMI refresh failed: no matching response\n");
    close(fd);
    return -1;
}

static int run_self_tests(void) {
    const ini_patch_entry_t *entries = UNIVERSAL_PATCHES;
    size_t nentries = sizeof(UNIVERSAL_PATCHES) / sizeof(UNIVERSAL_PATCHES[0]);

    /* 用例 1：EE 原件样式，含 SILENTREDIAL_OVERRIDE 块，无 ILS 与 IsCNIEnabled */
    const char *ee_sample =
        "\n[SIPConfig:GeoLocation]\n*iDisableLocInfoInNormalCall = 1\n"
        "[Registration:RegistrationFeatureFlags]\n*specialHandlingFor403RegFailure = 1\n"
        "[SILENTREDIAL_OVERRIDE]\n0 ^ 1 = SIP_ERROR_403\n1 ^ 1 = SIP_ERROR_403\n[SILENTREDIAL_OVERRIDE_END]\n";
    uint8_t *out1 = NULL;
    size_t len1 = 0;
    if (ini_merge((const uint8_t *)ee_sample, strlen(ee_sample), entries, nentries, &out1, &len1) != 0) return 1;
    if (!ini_check_patched(out1, len1, entries, nentries)) { fprintf(stderr, "FAIL T1 check\n"); return 1; }
    if (!strstr((const char *)out1, "[SILENTREDIAL_OVERRIDE]\n0 ^ 1 = SIP_ERROR_403\n")) {
        fprintf(stderr, "FAIL T1 silentredial lost\n"); return 1;
    }
    if (!strstr((const char *)out1, "*specialHandlingFor403RegFailure = 1")) {
        fprintf(stderr, "FAIL T1 preserved key lost\n"); return 1;
    }

    /* 用例 2：Orange France 样板（自带 [ILS:IlsGeoLocImsWlan] *firstPriorityForLocation = 1） */
    const char *orange_fr =
        "[QIPCALL:ImsVideoConfig]\n*vtImpactedByDataSetting = 0\n"
        "[ILS:IlsGeoLocImsWlan]\n*firstPriorityForLocation = 1\n"
        "[ILS:BootupRetry]\n*bootupQueryAllowed = 0\n";
    uint8_t *out2 = NULL;
    size_t len2 = 0;
    if (ini_merge((const uint8_t *)orange_fr, strlen(orange_fr), entries, nentries, &out2, &len2) != 0) return 1;
    if (!ini_check_patched(out2, len2, entries, nentries)) { fprintf(stderr, "FAIL T2 orange_fr check\n"); return 1; }
    if (strstr((const char *)out2, "*firstPriorityForLocation = 1")) {
        fprintf(stderr, "FAIL T2 stale firstPriorityForLocation=1\n"); return 1;
    }
    /* 确保只出现 1 次 [ILS:IlsGeoLocImsWlan] */
    const char *p = strstr((const char *)out2, "[ILS:IlsGeoLocImsWlan]");
    if (!p || strstr(p + 1, "[ILS:IlsGeoLocImsWlan]")) {
        fprintf(stderr, "FAIL T2 duplicate section\n"); return 1;
    }

    /* 用例 3：Orange Poland + ChunghwaTel 混合样式（含 CRLF、已有 *iEnableCellInfoInPANI = 1 与 *IsCNIEnabled = 0） */
    const char *mixed_crlf =
        "[SIPConfig:GeoLocation]\r\n*iEnableCellInfoInPANI = 1\r\n"
        "[SIPConfig:FeatureFlags]\r\n*IsCNIEnabled = 0\r\n";
    uint8_t *out3 = NULL;
    size_t len3 = 0;
    if (ini_merge((const uint8_t *)mixed_crlf, strlen(mixed_crlf), entries, nentries, &out3, &len3) != 0) return 1;
    if (memchr(out3, '\r', len3) != NULL) { fprintf(stderr, "FAIL T3 CR found\n"); return 1; }
    if (!ini_check_patched(out3, len3, entries, nentries)) { fprintf(stderr, "FAIL T3 mixed check\n"); return 1; }

    /* 用例 4：幂等性校验（二次合并结果应完全不变） */
    uint8_t *out4 = NULL;
    size_t len4 = 0;
    if (ini_merge(out3, len3, entries, nentries, &out4, &len4) != 0) return 1;
    if (len4 != len3 || memcmp(out4, out3, len3) != 0) {
        fprintf(stderr, "FAIL T4 idempotent merge mismatch\n"); return 1;
    }

    /* 用例 5：异常重复目标节归一化与 --ils-only 4 键集校验 */
    const char *dup_sample =
        "[ILS:IlsGeoLocImsWlan]\n*firstPriorityForLocation = 1\n\n"
        "[QIPCALL:ImsVoiceConfig]\n*enableCWI = 1\n\n"
        "[ILS:IlsGeoLocImsWlan]\n*firstPriorityForLocation = 2\n*OverrideMCCCountryCode = 0\n";
    uint8_t *out5 = NULL;
    size_t len5 = 0;
    if (ini_merge((const uint8_t *)dup_sample, strlen(dup_sample), entries, nentries, &out5, &len5) != 0) return 1;
    if (!ini_check_patched(out5, len5, entries, nentries)) { fprintf(stderr, "FAIL T5 dup normalize check\n"); return 1; }
    const ini_patch_entry_t *ils_entries = ILS_ONLY_PATCHES;
    size_t ils_n = sizeof(ILS_ONLY_PATCHES) / sizeof(ILS_ONLY_PATCHES[0]);
    uint8_t *out5_ils = NULL;
    size_t len5_ils = 0;
    if (ini_merge((const uint8_t *)ee_sample, strlen(ee_sample), ils_entries, ils_n, &out5_ils, &len5_ils) != 0) return 1;
    if (!ini_check_patched(out5_ils, len5_ils, ils_entries, ils_n)) { fprintf(stderr, "FAIL T5 ils-only check\n"); return 1; }

    free(out1); free(out2); free(out3); free(out4); free(out5); free(out5_ils);

    /* 用例 6：QMI VS 热刷新请求编码与响应解析校验 */
    uint8_t qreq[32];
    int qlen = qmi_build_refresh_efs_req(7, MCFG_TYPE_SW, MCFG_ACTIVATION_REFRESH_ONLY, qreq, sizeof(qreq));
    const uint8_t expected_qreq[21] = {
        0x00, 0x07, 0x00, 0x8e, 0x00, 0x0e, 0x00,
        0x01, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00,
        0x02, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00
    };
    if (qlen != 21 || memcmp(qreq, expected_qreq, 21) != 0) {
        fprintf(stderr, "FAIL T6 qmi request encode mismatch\n");
        return 1;
    }
    const uint8_t ok_qrsp[22] = {
        0x02, 0x07, 0x00, 0x8e, 0x00, 0x0f, 0x00,
        0x01, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    uint32_t qr = 99, qe = 99;
    if (qmi_parse_void_resp(ok_qrsp, sizeof(ok_qrsp), 7, QMI_VS_MSG_REFRESH_EFS, &qr, &qe) != 0 ||
        qr != 0 || qe != 0) {
        fprintf(stderr, "FAIL T6 qmi ok response parse\n");
        return 1;
    }
    if (qmi_parse_void_resp(ok_qrsp, sizeof(ok_qrsp), 8, QMI_VS_MSG_REFRESH_EFS, &qr, &qe) != -2) {
        fprintf(stderr, "FAIL T6 qmi txn mismatch guard\n");
        return 1;
    }
    uint8_t err_qrsp[22];
    memcpy(err_qrsp, ok_qrsp, sizeof(err_qrsp));
    err_qrsp[14] = 0x03; /* qmi_err = 3 */
    if (qmi_parse_void_resp(err_qrsp, sizeof(err_qrsp), 7, QMI_VS_MSG_REFRESH_EFS, &qr, &qe) != 0 ||
        qe != 3) {
        fprintf(stderr, "FAIL T6 qmi err decode\n");
        return 1;
    }

    /* 用例 7：qp_ims_service_enablement_config 3 字节定点解锁、幂等与路径白名单校验 */
    uint8_t se_ais[1024];
    memset(se_ais, 0, sizeof(se_ais));
    se_ais[1] = 0x01; se_ais[3] = 0x01; se_ais[8] = 0x01; se_ais[9] = 0x03; se_ais[13] = 0x02;
    se_ais[34] = 0x01; se_ais[44] = 0x04;
    if (!se_needs_patch(se_ais, sizeof(se_ais))) { fprintf(stderr, "FAIL T7 ais needs\n"); return 1; }
    if (se_apply_patch(se_ais, sizeof(se_ais)) != 2 ||
        se_ais[8] != 0x01 || se_ais[9] != 0x01 || se_ais[13] != 0x01 ||
        se_ais[34] != 0x01 || se_ais[44] != 0x04) {
        fprintf(stderr, "FAIL T7 ais apply\n"); return 1;
    }
    if (se_needs_patch(se_ais, sizeof(se_ais)) || se_apply_patch(se_ais, sizeof(se_ais)) != 0) {
        fprintf(stderr, "FAIL T7 ais idempotent\n"); return 1;
    }

    uint8_t se_smarty[1024];
    memset(se_smarty, 0, sizeof(se_smarty));
    se_smarty[8] = 0x01; se_smarty[9] = 0x03; se_smarty[13] = 0x00;
    if (!se_needs_patch(se_smarty, sizeof(se_smarty)) ||
        se_apply_patch(se_smarty, sizeof(se_smarty)) != 1 ||
        se_smarty[9] != 0x01 || se_smarty[13] != 0x00) {
        fprintf(stderr, "FAIL T7 smarty preserve roam=0\n"); return 1;
    }

    uint8_t se_ee[1024];
    memset(se_ee, 0, sizeof(se_ee));
    se_ee[0] = 0x02; se_ee[8] = 0x01; se_ee[9] = 0x01; se_ee[13] = 0x01;
    if (se_needs_patch(se_ee, sizeof(se_ee)) || se_apply_patch(se_ee, sizeof(se_ee)) != 0) {
        fprintf(stderr, "FAIL T7 ee clean\n"); return 1;
    }

    uint8_t se_row[1024];
    memset(se_row, 0, sizeof(se_row));
    se_row[8] = 0x02; se_row[9] = 0x03; se_row[13] = 0x01;
    if (!se_needs_patch(se_row, sizeof(se_row)) ||
        se_apply_patch(se_row, sizeof(se_row)) != 2 ||
        se_row[8] != 0x01 || se_row[9] != 0x01 || se_row[13] != 0x01) {
        fprintf(stderr, "FAIL T7 row apply\n"); return 1;
    }

    if (se_needs_patch(se_ais, 15) || se_apply_patch(se_ais, 15) != -1) {
        fprintf(stderr, "FAIL T7 short len guard\n"); return 1;
    }
    if (!is_allowed_write_path(EFS_PATH_SLOT0) || !is_allowed_write_path(EFS_PATH_SLOT1) ||
        !is_allowed_write_path(EFS_SE_PATH_SLOT0) || !is_allowed_write_path(EFS_SE_PATH_SLOT1) ||
        is_allowed_write_path("/nv/item_files/modem/mmode/mode_pref")) {
        fprintf(stderr, "FAIL T7 whitelist check\n"); return 1;
    }

    printf("SELF-TEST PASSED: all 7 INI/SE merge & QMI refresh cases OK\n");
    return 0;
}

/* ===================== 子命令分发 ===================== */

static int select_slots(const char *target, const slot_target_t **out_list, size_t *out_cnt) {
    if (!target || strcmp(target, "all") == 0) {
        *out_list = ALL_SLOTS;
        *out_cnt = 2;
        return 0;
    }
    if (strcmp(target, "slot0") == 0) {
        *out_list = &ALL_SLOTS[0];
        *out_cnt = 1;
        return 0;
    }
    if (strcmp(target, "slot1") == 0) {
        *out_list = &ALL_SLOTS[1];
        *out_cnt = 1;
        return 0;
    }
    return -1;
}

/* 按备份前缀从本地还原单个 EFS 目标文件，成功写入返回 0，失败返回 -1 */
static int restore_one_target(const diag_ctx_t *ctx, const char *backup_dir,
                              const char *bak_prefix, const char *efs_path, int *changed) {
    char orig_path[512];
    snprintf(orig_path, sizeof(orig_path), "%s/%s_orig.bin", backup_dir, bak_prefix);
    uint8_t *bak = NULL;
    size_t bak_len = 0;
    if (read_local_file(orig_path, &bak, &bak_len) != 0) {
        printf("%s: SKIP restore (no backup at %s)\n", bak_prefix, orig_path);
        return 0;
    }
    uint32_t m = 0, sz = 0;
    if (efs_stat(ctx, efs_path, &m, &sz) == -2) {
        printf("%s (%s): SKIP restore (target not found in EFS)\n", bak_prefix, efs_path);
        free(bak);
        return 0;
    }
    if (efs_write_and_verify(ctx, efs_path, bak, bak_len) != 0) {
        free(bak);
        return -1;
    }
    printf("RESTORED %s <- %s (%zu bytes)\n", efs_path, orig_path, bak_len);
    free(bak);
    (*changed)++;
    return 0;
}

int main(int argc, char **argv) {
    int ils_only = 0;
    int no_lock = 0;
    diag_mode_opt_t mode_opt = DIAG_MODE_AUTO;
    const char *backup_dir = DEFAULT_BACKUP_DIR;

    const char *pos_args[8];
    int pos_argc = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--self-test") == 0) {
            return run_self_tests();
        } else if (strcmp(argv[i], "--ils-only") == 0) {
            ils_only = 1;
        } else if (strcmp(argv[i], "--no-lock") == 0) {
            no_lock = 1;
        } else if (strcmp(argv[i], "--remote") == 0) {
            mode_opt = DIAG_MODE_REMOTE;
        } else if (strcmp(argv[i], "--local") == 0) {
            mode_opt = DIAG_MODE_LOCAL;
        } else if (strcmp(argv[i], "--backup-dir") == 0 && i + 1 < argc) {
            backup_dir = argv[++i];
        } else if (pos_argc < 8) {
            pos_args[pos_argc++] = argv[i];
        }
    }

    if (pos_argc < 1) {
        fprintf(stderr,
                "Usage: %s [--ils-only] [--no-lock] [--remote|--local] [--backup-dir <dir>] "
                "probe|status [slot0|slot1|all]|patch [slot0|slot1|all]|restore [slot0|slot1|all]|"
                "refresh [cfg act]|stat <efs_path>|read <efs_path> <local_out>|--self-test\n",
                argv[0]);
        return 2;
    }

    const char *cmd = pos_args[0];

    /* 获取与守护进程和 App 共享的互斥锁，保障 /dev/diag 与 QMI 刷新严格串行 */
    int lock_fd = -1;
    if (!no_lock) {
        lock_fd = acquire_diag_lock(DEFAULT_LOCK_PATH);
        if (lock_fd < 0) return 1;
    }

    /* refresh 走纯内核 QRTR 通路，严禁打开 /dev/diag，避免与刚落盘或重启的基带产生并发冲突 */
    if (strcmp(cmd, "refresh") == 0) {
        uint32_t cfg = MCFG_TYPE_SW;
        uint32_t act = MCFG_ACTIVATION_REFRESH_ONLY;
        int rrc = 0;
        if (pos_argc >= 3) {
            cfg = (uint32_t)strtoul(pos_args[1], NULL, 0);
            act = (uint32_t)strtoul(pos_args[2], NULL, 0);
        } else if (pos_argc == 2) {
            fprintf(stderr, "refresh requires either 0 args (default 1 1) or 2 args (<cfg> <act>)\n");
            if (lock_fd >= 0) close(lock_fd);
            return 2;
        }
        rrc = (qmi_trigger_modem_efs_refresh(cfg, act) == 0) ? 0 : 1;
        if (lock_fd >= 0) close(lock_fd);
        return rrc;
    }

    const ini_patch_entry_t *entries = ils_only ? ILS_ONLY_PATCHES : UNIVERSAL_PATCHES;
    size_t nentries = ils_only
        ? (sizeof(ILS_ONLY_PATCHES) / sizeof(ILS_ONLY_PATCHES[0]))
        : (sizeof(UNIVERSAL_PATCHES) / sizeof(UNIVERSAL_PATCHES[0]));

    diag_ctx_t ctx;
    if (diag_open(&ctx, mode_opt) != 0) {
        if (lock_fd >= 0) close(lock_fd);
        return 1;
    }

    int rc = 0;

    if (strcmp(cmd, "probe") == 0) {
        uint8_t rsp[512], ext_id = DIAG_EXT_BUILD_ID;
        int r = diag_transact(&ctx, &ext_id, 1, rsp, sizeof(rsp), 1500);
        printf("mode=%s remote_mask=0x%04x ext_build_id_len=%d sw=%s\n",
               ctx.is_remote ? "remote_mdm" : "local_mpss",
               ctx.remote_mask, r, (r > 12) ? (const char *)(rsp + 12) : "<none>");
        rc = (r > 12) ? 0 : 1;
    } else if (strcmp(cmd, "stat") == 0 && pos_argc >= 2) {
        uint32_t m = 0, sz = 0;
        int sr = efs_stat(&ctx, pos_args[1], &m, &sz);
        if (sr == 0) {
            printf("STAT %s -> OK mode=0%o size=%u\n", pos_args[1], m, sz);
        } else if (sr == -2) {
            printf("STAT %s -> NOT_FOUND (ENOENT)\n", pos_args[1]);
        } else {
            printf("STAT %s -> ERROR\n", pos_args[1]);
            rc = 1;
        }
    } else if (strcmp(cmd, "read") == 0 && pos_argc >= 3) {
        uint8_t *buf = NULL;
        size_t blen = 0;
        if (efs_read_all_mem(&ctx, pos_args[1], &buf, &blen) != 0) {
            fprintf(stderr, "READ %s failed\n", pos_args[1]);
            rc = 1;
        } else {
            if (write_local_file(pos_args[2], buf, blen) != 0) rc = 1;
            else printf("READ %s -> %s (%zu bytes)\n", pos_args[1], pos_args[2], blen);
            free(buf);
        }
    } else if (strcmp(cmd, "status") == 0) {
        const slot_target_t *slots = NULL;
        size_t scnt = 0;
        const char *arg_t = (pos_argc >= 2) ? pos_args[1] : "all";
        if (select_slots(arg_t, &slots, &scnt) != 0) { rc = 2; goto out; }
        for (size_t i = 0; i < scnt; i++) {
            uint8_t *buf = NULL;
            size_t blen = 0;
            int rr = efs_read_all_mem(&ctx, slots[i].efs_path, &buf, &blen);
            if (rr == -2) {
                printf("%s (%s): NOT_FOUND\n", slots[i].slot_name, slots[i].efs_path);
                printf("%s (%s): SKIP_INACTIVE\n", slots[i].se_backup_prefix, slots[i].se_path);
                continue;
            } else if (rr != 0) {
                printf("%s (%s): READ_ERROR\n", slots[i].slot_name, slots[i].efs_path);
                rc = 1;
                continue;
            }
            int patched = ini_check_patched(buf, blen, entries, nentries);
            printf("%s (%s): size=%zu status=%s\n",
                   slots[i].slot_name, slots[i].efs_path, blen,
                   patched ? "ALREADY_PATCHED" : "UNPATCHED");
            free(buf);

            /* 活跃卡槽同步检查 qp_ims_service_enablement_config 漫游与优选字节 */
            uint8_t *se_buf = NULL;
            size_t se_len = 0;
            int sr = efs_read_all_mem(&ctx, slots[i].se_path, &se_buf, &se_len);
            if (sr == -2) {
                printf("%s (%s): NOT_FOUND\n", slots[i].se_backup_prefix, slots[i].se_path);
            } else if (sr != 0 || se_len < 16) {
                printf("%s (%s): READ_ERROR\n", slots[i].se_backup_prefix, slots[i].se_path);
                rc = 1;
            } else {
                int se_need = se_needs_patch(se_buf, se_len);
                printf("%s (%s): size=%zu wfc=0x%02x pref=0x%02x roam=0x%02x status=%s\n",
                       slots[i].se_backup_prefix, slots[i].se_path, se_len,
                       se_buf[8], se_buf[9], se_buf[13],
                       se_need ? "UNPATCHED" : "ALREADY_PATCHED");
            }
            free(se_buf);
        }
    } else if (strcmp(cmd, "patch") == 0) {
        const slot_target_t *slots = NULL;
        size_t scnt = 0;
        const char *arg_t = (pos_argc >= 2) ? pos_args[1] : "all";
        if (select_slots(arg_t, &slots, &scnt) != 0) { rc = 2; goto out; }

        int changed = 0;
        for (size_t i = 0; i < scnt; i++) {
            uint8_t *orig = NULL;
            size_t orig_len = 0;
            int rr = efs_read_all_mem(&ctx, slots[i].efs_path, &orig, &orig_len);
            if (rr == -2) {
                printf("%s (%s): SKIP (not found)\n", slots[i].slot_name, slots[i].efs_path);
                continue;
            } else if (rr != 0) {
                fprintf(stderr, "%s (%s): READ_ERROR\n", slots[i].slot_name, slots[i].efs_path);
                rc = 1;
                goto out;
            }

            if (ini_check_patched(orig, orig_len, entries, nentries)) {
                printf("%s (%s): ALREADY_PATCHED (size=%zu)\n",
                       slots[i].slot_name, slots[i].efs_path, orig_len);
                free(orig);
            } else {
                if (backup_original_if_needed(backup_dir, slots[i].slot_name, orig, orig_len) != 0) {
                    free(orig);
                    rc = 1;
                    goto out;
                }

                uint8_t *merged = NULL;
                size_t merged_len = 0;
                if (ini_merge(orig, orig_len, entries, nentries, &merged, &merged_len) != 0) {
                    fprintf(stderr, "%s: ini_merge failed\n", slots[i].slot_name);
                    free(orig);
                    rc = 1;
                    goto out;
                }
                free(orig);

                if (efs_write_and_verify(&ctx, slots[i].efs_path, merged, merged_len) != 0) {
                    free(merged);
                    rc = 1;
                    goto out;
                }
                printf("PATCHED %s -> %zu bytes\n", slots[i].efs_path, merged_len);
                free(merged);
                changed++;
            }

            /* 对活跃卡槽检查并解锁 qp_ims_service_enablement_config 漫游开关与默认 Wi-Fi 偏好 */
            uint8_t *se_orig = NULL;
            size_t se_len = 0;
            int sr = efs_read_all_mem(&ctx, slots[i].se_path, &se_orig, &se_len);
            if (sr == -2) {
                printf("%s (%s): SKIP (not found)\n", slots[i].se_backup_prefix, slots[i].se_path);
                continue;
            } else if (sr != 0) {
                fprintf(stderr, "%s (%s): READ_ERROR\n", slots[i].se_backup_prefix, slots[i].se_path);
                rc = 1;
                goto out;
            } else if (se_len < 16) {
                printf("%s (%s): SKIP (len=%zu < 16)\n",
                       slots[i].se_backup_prefix, slots[i].se_path, se_len);
                free(se_orig);
                continue;
            }

            if (!se_needs_patch(se_orig, se_len)) {
                printf("%s (%s): ALREADY_PATCHED (wfc=0x%02x pref=0x%02x roam=0x%02x)\n",
                       slots[i].se_backup_prefix, slots[i].se_path,
                       se_orig[8], se_orig[9], se_orig[13]);
                free(se_orig);
                continue;
            }

            if (backup_original_if_needed(backup_dir, slots[i].se_backup_prefix, se_orig, se_len) != 0) {
                free(se_orig);
                rc = 1;
                goto out;
            }

            uint8_t *se_mod = (uint8_t *)malloc(se_len);
            if (!se_mod) {
                free(se_orig);
                rc = 1;
                goto out;
            }
            memcpy(se_mod, se_orig, se_len);
            free(se_orig);

            if (se_apply_patch(se_mod, se_len) < 0) {
                free(se_mod);
                rc = 1;
                goto out;
            }
            if (efs_write_and_verify(&ctx, slots[i].se_path, se_mod, se_len) != 0) {
                free(se_mod);
                rc = 1;
                goto out;
            }
            printf("PATCHED %s -> %zu bytes (wfc=0x%02x pref=0x%02x roam=0x%02x)\n",
                   slots[i].se_path, se_len, se_mod[8], se_mod[9], se_mod[13]);
            free(se_mod);
            changed++;
        }

        if (changed > 0) {
            if (efs_sync(&ctx) != 0) {
                rc = 1;
                goto out;
            }
        }
        printf("CHANGED=%d REBOOT_REQUIRED=%d\n", changed, changed > 0 ? 1 : 0);
    } else if (strcmp(cmd, "restore") == 0) {
        const slot_target_t *slots = NULL;
        size_t scnt = 0;
        const char *arg_t = (pos_argc >= 2) ? pos_args[1] : "all";
        if (select_slots(arg_t, &slots, &scnt) != 0) { rc = 2; goto out; }

        int changed = 0;
        for (size_t i = 0; i < scnt; i++) {
            if (restore_one_target(&ctx, backup_dir, slots[i].slot_name, slots[i].efs_path, &changed) != 0) {
                rc = 1;
                goto out;
            }
            if (restore_one_target(&ctx, backup_dir, slots[i].se_backup_prefix, slots[i].se_path, &changed) != 0) {
                rc = 1;
                goto out;
            }
        }
        if (changed > 0) {
            if (efs_sync(&ctx) != 0) {
                rc = 1;
                goto out;
            }
        }
        printf("CHANGED=%d REBOOT_REQUIRED=%d\n", changed, changed > 0 ? 1 : 0);
    } else {
        rc = 2;
    }

out:
    diag_close(&ctx);
    if (lock_fd >= 0) close(lock_fd);
    return rc;
}
