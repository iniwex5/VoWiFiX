#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define DIAG_IOCTL_SWITCH_LOGGING 7
#define DIAG_IOCTL_REMOTE_DEV 32
#define DIAG_IOCTL_REGISTER_CALLBACK 37
#define DIAG_IOCTL_QUERY_MD_PID 41

#define MEMORY_DEVICE_MODE 2
#define CALLBACK_MODE 6
#define NO_LOGGING_MODE 7
#define USER_SPACE_DATA_TYPE 0x20
#define DIAG_REMOTE_MDM1_BIT 0x0001U
#define DIAG_CON_MPSS 0x0002U
#define DIAG_CON_ALL 0x00FFU
#define DIAG_CON_ALL_EXT 0x03FFU
#define DIAG_CON_LEGACY_ALL 0x0FFFU

#define EXIT_CODE_LOCK_BUSY 99
#define EXIT_CODE_TIMEOUT 124
#define DEFAULT_READ_TIMEOUT_SEC 12
#define DEFAULT_WRITE_TIMEOUT_SEC 45

/* GKI 用户态 diag-router UNIX SEQPACKET 控制信令字 */
#define DIAG_ROUTER_CMD_REMOTE_DEV 0x33U
#define DIAG_ROUTER_RSP_REMOTE_DEV 0x00010000U
#define DIAG_ROUTER_RSP_SWITCH_LOGGING 0x00160000U

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
#define EFS_S2B_PATH_SLOT0 "/data/iwlan_s2b_config.xml"
#define EFS_S2B_PATH_SLOT1 "/data/iwlan_s2b_config.xml_Subscription01"
#define DEFAULT_BACKUP_DIR "/data/adb/vowifix"
#define DEFAULT_LOCK_PATH "/data/adb/vowifix/diag.lock"
#define DEFAULT_BACKEND_CONF "/data/adb/vowifix/backend.conf"

/* Qualcomm QRTR 与 QMI VS EFS 热刷新参数（无需加载外部动态库） */
#ifndef AF_QIPCRTR
#define AF_QIPCRTR 42
#endif
#define QRTR_PORT_CTRL 0xfffffffeU
#define QRTR_TYPE_NEW_SERVER 4
#define QRTR_TYPE_NEW_LOOKUP 10
#define QMI_VS_SERVICE_ID 0x00e5U
#define QMI_VS_MSG_BIND_SUBS 0x0021U
#define QMI_VS_MSG_REFRESH_EFS 0x008eU
#define QMI_NAS_SERVICE_ID 0x0003U
#define QMI_NAS_MSG_GET_SERVING_SYSTEM 0x0024U
#define QMI_NAS_MSG_GET_SYS_SEL_PREF 0x0034U
#define QMI_NAS_MSG_BIND_SUBS 0x00AFU
#define QMI_SUBS_PRIMARY 0U
#define QMI_SUBS_SECONDARY 1U
#define QMI_HDR_FLAG_REQ 0x00U
#define QMI_HDR_FLAG_RESP 0x02U
#define MCFG_TYPE_SW 1U
#define MCFG_ACTIVATION_REFRESH_ONLY 1U

typedef enum {
    REFRESH_SLOT_ALL = -1,
    REFRESH_SLOT_0 = 0,
    REFRESH_SLOT_1 = 1
} refresh_slot_opt_t;

/* GKI 用户态 diag-router SWITCH_LOGGING 24B 载荷结构（偏移 16=diag_id, 20=pd_val） */
struct __attribute__((packed)) diag_logging_mode_param_t {
    uint32_t req_mode;
    uint32_t peripheral_mask;
    uint32_t pd_mask;
    uint8_t mode_param;
    uint8_t pad[3];
    int32_t diag_id;
    uint32_t pd_val;
};

/* 传统内核态 /dev/diag (diagchar.h) SWITCH_LOGGING 参数结构体（24B：偏移 13/14 单字节，16/20 peripheral/device_mask） */
struct __attribute__((packed)) diagchar_logging_mode_param_t {
    uint32_t req_mode;
    uint32_t peripheral_mask;
    uint32_t pd_mask;
    uint8_t mode_param;
    uint8_t diag_id;
    uint8_t pd_val;
    uint8_t reserved;
    int32_t peripheral;
    int32_t device_mask;
};

/* 内核 DIAG_IOCTL_QUERY_MD_PID (41) 会话 PID 查询结构（16B） */
struct diag_query_pid_t {
    uint32_t peripheral_mask;
    uint32_t pd_mask;
    int32_t pid;
    int32_t device_mask;
};

typedef enum {
    DIAG_MODE_AUTO = 0,
    DIAG_MODE_REMOTE = 1,
    DIAG_MODE_LOCAL = 2
} diag_mode_opt_t;

typedef enum {
    DIAG_BACKEND_AUTO = 0,
    DIAG_BACKEND_DIAGCHAR,     /* 传统内核 /dev/diag 字符设备 */
    DIAG_BACKEND_DIAG_ROUTER,  /* GKI 用户态 diag-router AF_UNIX SOCK_SEQPACKET 通道 */
    DIAG_BACKEND_LIBDIAG,      /* 预留动态库桥接后端（当前静态版未启用） */
    DIAG_BACKEND_NONE          /* 无可用 Diag 通道 / 模拟无通道降级 */
} diag_backend_t;

struct diag_ctx;

/* 多后端 Diag 传输层统一接口表 */
typedef struct diag_transport_ops {
    const char *name;
    diag_backend_t id;
    int (*detect)(void);
    int (*open)(struct diag_ctx *ctx, diag_mode_opt_t mode_opt);
    int (*transact)(const struct diag_ctx *ctx, const uint8_t *cmd, int cmd_len,
                    uint8_t *rsp_out, int rsp_cap, int timeout_ms);
    void (*close)(struct diag_ctx *ctx);
} diag_transport_ops_t;

typedef struct {
    const char *slot_name;
    const char *efs_path;
    const char *se_path;
    const char *se_backup_prefix;
    const char *s2b_path;
    const char *s2b_backup_prefix;
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
    { "slot0", EFS_PATH_SLOT0, EFS_SE_PATH_SLOT0, "slot0_se", EFS_S2B_PATH_SLOT0, "slot0_s2b" },
    { "slot1", EFS_PATH_SLOT1, EFS_SE_PATH_SLOT1, "slot1_se", EFS_S2B_PATH_SLOT1, "slot1_s2b" },
};

typedef struct diag_ctx {
    int fd;
    int32_t token;       /* 外挂 MDM 使用 -1，内置 MPSS 使用 0 */
    uint16_t remote_mask;
    int is_remote;
    const diag_transport_ops_t *ops;
} diag_ctx_t;

/* ===================== HDLC 与多后端 Diag 传输层 ===================== */

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

/* ===================== 运行时防阻塞超时与共享协议帧编码 ===================== */

static volatile sig_atomic_t g_io_timed_out = 0;
static volatile sig_atomic_t g_cmd_timed_out = 0;
static volatile sig_atomic_t g_saw_diag_timeout = 0;
static volatile sig_atomic_t g_efs_write_critical = 0;
static long g_cmd_deadline_ms = 0;
static int g_alarm_installed = 0;

static long diag_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + (long)(ts.tv_nsec / 1000000L);
}

static void on_sigalrm(int sig) {
    (void)sig;
    g_io_timed_out = 1;
}

static void install_sigalrm_handler(void) {
    if (g_alarm_installed) return;
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_sigalrm;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0; /* 禁用 SA_RESTART，确保阻塞在 diagchar_read 的 read() 立即被 EINTR 打断 */
    if (sigaction(SIGALRM, &sa, NULL) == 0) {
        g_alarm_installed = 1;
    }
}

static void set_cmd_timeout_sec(int sec) {
    g_io_timed_out = 0;
    g_cmd_timed_out = 0;
    g_saw_diag_timeout = 0;
    g_efs_write_critical = 0;
    g_cmd_deadline_ms = (sec > 0) ? (diag_now_ms() + (long)sec * 1000L) : 0L;
}

static int compute_effective_timeout_ms(int req_timeout_ms) {
    if (req_timeout_ms <= 0) req_timeout_ms = 1;
    if (g_cmd_deadline_ms > 0L && !g_efs_write_critical) {
        long rem = g_cmd_deadline_ms - diag_now_ms();
        if (rem <= 0) {
            g_cmd_timed_out = 1;
            return 0;
        }
        if (rem < (long)req_timeout_ms) {
            return (int)rem;
        }
    }
    return req_timeout_ms;
}

/* 带硬超时保护的 read()：同时打断无 .poll 回调的阻塞式 diagchar_read 并返回 -2 */
static ssize_t diag_read_with_alarm(int fd, void *buf, size_t cap, int timeout_ms) {
    int eff_ms = compute_effective_timeout_ms(timeout_ms);
    if (eff_ms <= 0) return -2;

    install_sigalrm_handler();
    g_io_timed_out = 0;

    struct itimerval itv;
    memset(&itv, 0, sizeof(itv));
    itv.it_value.tv_sec = eff_ms / 1000;
    itv.it_value.tv_usec = (eff_ms % 1000) * 1000;
    if (itv.it_value.tv_sec == 0 && itv.it_value.tv_usec == 0) {
        itv.it_value.tv_usec = 1000;
    }
    itv.it_interval.tv_usec = 200000; /* 兜底间隔：防止信号恰在进入 read() 前触发造成挂起 */
    setitimer(ITIMER_REAL, &itv, NULL);

    ssize_t n = -1;
    int saved_errno = 0;
    for (;;) {
        if (g_io_timed_out) {
            n = -1;
            saved_errno = EINTR;
            break;
        }
        n = read(fd, buf, cap);
        saved_errno = errno;
        if (n >= 0 || saved_errno != EINTR || g_io_timed_out) {
            break;
        }
    }

    struct itimerval zero_itv;
    memset(&zero_itv, 0, sizeof(zero_itv));
    setitimer(ITIMER_REAL, &zero_itv, NULL);

    /* 仅当 read() 确因信号中断返回 -1 (EINTR) 且闹钟已触发时判为超时，正常 EOF (n==0) 原样返回 */
    if (n < 0 && saved_errno == EINTR && g_io_timed_out) {
        if (g_cmd_deadline_ms > 0L && !g_efs_write_critical && diag_now_ms() >= g_cmd_deadline_ms) {
            g_cmd_timed_out = 1;
        }
        errno = ETIMEDOUT;
        return -2;
    }
    errno = saved_errno;
    return n;
}

/* 构造 USER_SPACE_DATA_TYPE (0x20) 公共 TX 帧（diagchar 与 diag-router 共用）：
 * 内置 MPSS (is_remote=0) 为 [0x20][HDLC]（严禁插入 4B token=0）；
 * 外挂 MDM (is_remote=1) 为 [0x20][token=-1][HDLC]。 */
static int diag_build_tx_pkt(int is_remote, int32_t token,
                             const uint8_t *cmd, int cmd_len,
                             uint8_t *out, int out_cap) {
    uint8_t payload[4096];
    int pay_len = build_hdlc(cmd, cmd_len, payload, (int)sizeof(payload));
    if (pay_len < 0) return -1;

    int hdr_len = is_remote ? 8 : 4;
    if (hdr_len + pay_len > out_cap) return -1;

    uint32_t dt = USER_SPACE_DATA_TYPE;
    memcpy(out, &dt, 4);
    if (is_remote) {
        memcpy(out + 4, &token, 4);
    }
    memcpy(out + hdr_len, payload, (size_t)pay_len);
    return hdr_len + pay_len;
}

/* 解析 USER_SPACE_DATA_TYPE (0x20) 公共 RX 批次包（diagchar 与 diag-router 共用）：
 * 内置 MPSS (is_remote=0) 每条记录为 [u32 len][HDLC]；
 * 外挂 MDM (is_remote=1) 每条记录为 [i32 token][u32 len][HDLC]。
 * 返回 >0 为匹配响应长度，0 表示广播/未匹配包或分片待续，-1 表示匹配的 Diag 错误响应。 */
static int diag_parse_rx_pkt(int is_remote, int32_t expected_token,
                             const uint8_t *rbuf, int n,
                             const uint8_t *cmd, int cmd_len,
                             uint8_t *acc, int *acc_len, int acc_cap,
                             uint8_t *rsp_out, int rsp_cap) {
    if (!rbuf || !acc || !acc_len || !rsp_out || n < 12) return 0;

    uint32_t r_dt = 0;
    uint32_t num_pkts = 0;
    memcpy(&r_dt, rbuf, 4);
    memcpy(&num_pkts, rbuf + 4, 4);
    if (r_dt != USER_SPACE_DATA_TYPE || num_pkts == 0 || num_pkts > 4096) {
        return 0;
    }

    int off = 8;
    for (uint32_t p = 0; p < num_pkts; p++) {
        int32_t pkt_tok = 0;
        uint32_t pkt_len = 0;
        if (is_remote) {
            if (off + 8 > n) break;
            memcpy(&pkt_tok, rbuf + off, 4);
            memcpy(&pkt_len, rbuf + off + 4, 4);
            off += 8;
        } else {
            if (off + 4 > n) break;
            memcpy(&pkt_len, rbuf + off, 4);
            off += 4;
        }

        if (pkt_len == 0 || pkt_len > (uint32_t)(n - off)) break;

        if (!is_remote || pkt_tok == expected_token) {
            if (*acc_len + (int)pkt_len > acc_cap) *acc_len = 0;
            memcpy(acc + *acc_len, rbuf + off, pkt_len);
            *acc_len += (int)pkt_len;

            int start = 0;
            for (int i = 0; i < *acc_len; i++) {
                if (acc[i] == 0x7E) {
                    if (i > start) {
                        uint8_t cand[8192];
                        int clen = unwrap_one_hdlc_with_crc(acc + start, i - start,
                                                            cand, (int)sizeof(cand));
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
            if (start > 0 && start <= *acc_len) {
                memmove(acc, acc + start, (size_t)(*acc_len - start));
                *acc_len -= start;
            }
        }
        off += (int)pkt_len;
    }
    return 0;
}

/* 根据命令行模式选项与远端掩码判断是否启用外挂基带 MDM1：
 * 仅 bit0 (0x0001) 代表外挂主基带 MDM1；0x0002 等第二协处理器位不得误判为外挂基带。 */
static int diag_resolve_is_remote(diag_mode_opt_t mode_opt, uint16_t remote_mask) {
    if (mode_opt == DIAG_MODE_REMOTE) return 1;
    if (mode_opt == DIAG_MODE_LOCAL) return 0;
    return (remote_mask & DIAG_REMOTE_MDM1_BIT) ? 1 : 0;
}

/* 计算 device_mask / pd_val：仅激活本地 APSS/MPSS (1u) 或再叠加外挂 MDM1 (3u)，
 * 绝不混入 0x0002 等无关远端掩码位。 */
static uint32_t diag_compute_pd_val(int is_remote) {
    return is_remote ? (1u | (DIAG_REMOTE_MDM1_BIT << 1)) : 1u;
}

/* 构造传统内核 diagchar 的 SWITCH_LOGGING 参数（兼容 Linux 4.14 偏移 13/14/16/20 布局） */
static void diagchar_build_switch_logging_param(uint32_t req_mode, uint32_t peripheral_mask,
                                                int is_remote,
                                                struct diagchar_logging_mode_param_t *out) {
    memset(out, 0, sizeof(*out));
    out->req_mode = req_mode;
    out->peripheral_mask = peripheral_mask;
    out->pd_mask = 0;
    out->mode_param = 1;
    out->diag_id = 0;
    out->pd_val = 0;
    out->peripheral = -22; /* -EINVAL：无 PD 映射需求 */
    out->device_mask = (int32_t)diag_compute_pd_val(is_remote);
}

/* 无副作用轻量探测 /dev/diag 字符设备是否存在（不切换 logging mode） */
static int diagchar_detect(void) {
    struct stat st;
    if (stat("/dev/diag", &st) != 0) return 0;
    return S_ISCHR(st.st_mode) ? 1 : 0;
}

/* 校验内核 MPSS 会话当前占用 PID（兼容跨掩码占用探测）：
 * 返回 0 表示归属当前进程或老内核不支持 ioctl(41)；1 表示驱动未建立本进程会话需回退；-1 表示被他进程占用。 */
static int diagchar_verify_mpss_session(int fd, uint32_t req_mask) {
    struct diag_query_pid_t q;
    memset(&q, 0, sizeof(q));
    q.peripheral_mask = req_mask;
    q.pd_mask = 0;
    q.pid = 0;
    q.device_mask = 1;
    if (ioctl(fd, DIAG_IOCTL_QUERY_MD_PID, &q) < 0) {
        return 0; /* 旧内核未实现 DIAG_IOCTL_QUERY_MD_PID (41) 时保留兼容放行 */
    }
    int32_t self_pid = (int32_t)getpid();
    if (q.pid == self_pid) {
        return 0;
    }
    if (q.pid > 0) {
        fprintf(stderr, "diagchar MPSS session busy (owner pid=%d)\n", q.pid);
        return -1;
    }

    /* 内核要求查询掩码与建连掩码严格相等，补充探测常见全外设掩码是否已被 diag_mdlog 等进程占用 */
    static const uint32_t probe_masks[] = {
        DIAG_CON_MPSS, DIAG_CON_ALL, DIAG_CON_ALL_EXT, DIAG_CON_LEGACY_ALL
    };
    for (size_t i = 0; i < sizeof(probe_masks) / sizeof(probe_masks[0]); i++) {
        if (probe_masks[i] == req_mask) continue;
        memset(&q, 0, sizeof(q));
        q.peripheral_mask = probe_masks[i];
        q.device_mask = 1;
        if (ioctl(fd, DIAG_IOCTL_QUERY_MD_PID, &q) >= 0 && q.pid > 0 && q.pid != self_pid) {
            fprintf(stderr, "diagchar session busy (mask=0x%x, owner pid=%d)\n", probe_masks[i], q.pid);
            return -1;
        }
    }
    return 1;
}

/* 自动或按需开启 /dev/diag：内置 MPSS 默认使用 MEMORY_DEVICE_MODE + DIAG_CON_MPSS，并校验会话归属（保留旧内核与外挂 MDM 回退） */
static int diagchar_open(diag_ctx_t *ctx, diag_mode_opt_t mode_opt) {
    ctx->fd = -1;
    ctx->token = 0;
    ctx->remote_mask = 0;
    ctx->is_remote = 0;

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
    ctx->is_remote = diag_resolve_is_remote(mode_opt, remote_mask);
    ctx->token = ctx->is_remote ? -1 : 0;

    struct diagchar_logging_mode_param_t mode;
    if (!ctx->is_remote) {
        diagchar_build_switch_logging_param(MEMORY_DEVICE_MODE, DIAG_CON_MPSS, 0, &mode);
        if (ioctl(ctx->fd, DIAG_IOCTL_SWITCH_LOGGING, &mode) >= 0) {
            int vrc = diagchar_verify_mpss_session(ctx->fd, DIAG_CON_MPSS);
            if (vrc == 0) return 0;
            if (vrc < 0) {
                close(ctx->fd);
                ctx->fd = -1;
                return -1;
            }
        }
    }

    /* 外挂 MDM 或不支持 MEMORY_DEVICE_MODE 的旧版内核，回退 REGISTER_CALLBACK + CALLBACK_MODE */
    int client_id = 0;
    ioctl(ctx->fd, DIAG_IOCTL_REGISTER_CALLBACK, &client_id);
    uint32_t cb_mask = ctx->is_remote ? DIAG_CON_LEGACY_ALL : DIAG_CON_MPSS;
    diagchar_build_switch_logging_param(CALLBACK_MODE, cb_mask, ctx->is_remote, &mode);
    int sw_rc = ioctl(ctx->fd, DIAG_IOCTL_SWITCH_LOGGING, &mode);
    if (sw_rc < 0 && !ctx->is_remote) {
        cb_mask = DIAG_CON_ALL;
        diagchar_build_switch_logging_param(CALLBACK_MODE, cb_mask, 0, &mode);
        sw_rc = ioctl(ctx->fd, DIAG_IOCTL_SWITCH_LOGGING, &mode);
    }
    if (sw_rc < 0) {
        fprintf(stderr, "ioctl(DIAG_IOCTL_SWITCH_LOGGING) failed: errno=%d (%s)\n", errno, strerror(errno));
        close(ctx->fd);
        ctx->fd = -1;
        return -1;
    }
    if (!ctx->is_remote && diagchar_verify_mpss_session(ctx->fd, cb_mask) < 0) {
        close(ctx->fd);
        ctx->fd = -1;
        return -1;
    }
    return 0;
}

static void diagchar_close(diag_ctx_t *ctx) {
    if (ctx && ctx->fd >= 0) {
        close(ctx->fd);
        ctx->fd = -1;
    }
}

/* 向 /dev/diag 目标基带通道收发单条同步 Diag 指令（受 poll + SIGALRM 双重超时保护） */
static int diagchar_transact(const diag_ctx_t *ctx, const uint8_t *cmd, int cmd_len,
                             uint8_t *rsp_out, int rsp_cap, int timeout_ms) {
    uint8_t send_buf[4096];
    int tx_len = diag_build_tx_pkt(ctx->is_remote, ctx->token,
                                   cmd, cmd_len, send_buf, (int)sizeof(send_buf));
    if (tx_len < 0) return -1;

    if (write(ctx->fd, send_buf, (size_t)tx_len) != (ssize_t)tx_len) {
        fprintf(stderr, "diag write failed: errno=%d (%s)\n", errno, strerror(errno));
        return -1;
    }

    static uint8_t rbuf[65536];
    static uint8_t acc[65536];
    int acc_len = 0;
    int eff_ms = compute_effective_timeout_ms(timeout_ms);
    if (eff_ms <= 0) {
        g_saw_diag_timeout = 1;
        return -2;
    }
    long deadline = diag_now_ms() + (long)eff_ms;

    for (;;) {
        long rem = deadline - diag_now_ms();
        if (rem <= 0) break;

        struct pollfd pfd = { .fd = ctx->fd, .events = POLLIN };
        int pr = poll(&pfd, 1, (int)rem);
        if (pr == 0) break;
        if (pr < 0) {
            if (errno == EINTR) continue;
            return -1;
        }

        rem = deadline - diag_now_ms();
        if (rem <= 0) break;

        ssize_t n = diag_read_with_alarm(ctx->fd, rbuf, sizeof(rbuf), (int)rem);
        if (n == -2) {
            g_saw_diag_timeout = 1;
            return -2;
        }
        if (n <= 0) {
            return -1;
        }

        int parsed = diag_parse_rx_pkt(ctx->is_remote, ctx->token, rbuf, (int)n,
                                       cmd, cmd_len, acc, &acc_len, (int)sizeof(acc),
                                       rsp_out, rsp_cap);
        if (parsed != 0) return parsed;
    }

    if (g_cmd_deadline_ms > 0L && !g_efs_write_critical && diag_now_ms() >= g_cmd_deadline_ms) {
        g_cmd_timed_out = 1;
    }
    g_saw_diag_timeout = 1;
    return -2;
}

/* ===================== GKI diag-router SOCK_SEQPACKET 后端 ===================== */

/* 构造 diag-router 的 DIAG_IOCTL_SWITCH_LOGGING (data_type=7) 32 字节请求帧（末尾含 4B PID） */
static void diag_router_build_switch_logging_req(uint32_t req_mode, int is_remote,
                                                 int32_t pid, uint8_t out[32]) {
    memset(out, 0, 32);
    uint32_t dt = DIAG_IOCTL_SWITCH_LOGGING;
    struct diag_logging_mode_param_t mode;
    memset(&mode, 0, sizeof(mode));
    mode.req_mode = req_mode;
    mode.peripheral_mask = 0xFFF;
    mode.pd_mask = 0;
    mode.mode_param = 1;
    mode.diag_id = -22;
    mode.pd_val = diag_compute_pd_val(is_remote);

    int32_t eff_pid = (pid >= 1) ? pid : 1;
    memcpy(out, &dt, 4);
    memcpy(out + 4, &mode, sizeof(mode));
    memcpy(out + 28, &eff_pid, 4);
}

/* diag-router 协议编解码薄包装（直接复用公共帧编解码逻辑） */
static int diag_router_build_tx_pkt(int is_remote, int32_t token,
                                    const uint8_t *cmd, int cmd_len,
                                    uint8_t *out, int out_cap) {
    return diag_build_tx_pkt(is_remote, token, cmd, cmd_len, out, out_cap);
}

static int diag_router_parse_rx_pkt(int is_remote, int32_t expected_token,
                                    const uint8_t *rbuf, int n,
                                    const uint8_t *cmd, int cmd_len,
                                    uint8_t *acc, int *acc_len, int acc_cap,
                                    uint8_t *rsp_out, int rsp_cap) {
    return diag_parse_rx_pkt(is_remote, expected_token, rbuf, n,
                             cmd, cmd_len, acc, acc_len, acc_cap, rsp_out, rsp_cap);
}

/* 连接高通 GKI diag-router 抽象命名空间 UNIX SOCK_SEQPACKET 套接字 */
static int diag_router_connect_socket(int timeout_ms) {
#ifdef __linux__
    struct sockaddr_un addrs[3];
    socklen_t lens[3];

    /* 候选 1：全零 108B 抽象命名空间（匹配高通 strlcpy("\0diag") 实测 bind 行为） */
    memset(&addrs[0], 0, sizeof(addrs[0]));
    addrs[0].sun_family = AF_UNIX;
    lens[0] = (socklen_t)sizeof(addrs[0]);

    /* 候选 2/3：兼容 "\0diag" 紧凑长度与完整 sockaddr_un 长度 */
    memset(&addrs[1], 0, sizeof(addrs[1]));
    addrs[1].sun_family = AF_UNIX;
    memcpy(addrs[1].sun_path, "\0diag", 5);
    lens[1] = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 5);

    addrs[2] = addrs[1];
    lens[2] = (socklen_t)sizeof(addrs[2]);

    for (int i = 0; i < 3; i++) {
        int eff_ms = compute_effective_timeout_ms(timeout_ms);
        if (eff_ms <= 0) {
            g_saw_diag_timeout = 1;
            return -1;
        }
        int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
        if (fd < 0) return -1;

        int flags = fcntl(fd, F_GETFL, 0);
        if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);

        int rc = connect(fd, (const struct sockaddr *)&addrs[i], lens[i]);
        if (rc < 0 && (errno == EINPROGRESS || errno == EAGAIN)) {
            struct pollfd pfd = { .fd = fd, .events = POLLOUT };
            int pr = poll(&pfd, 1, eff_ms);
            if (pr > 0) {
                int so_err = 0;
                socklen_t elen = sizeof(so_err);
                if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_err, &elen) == 0 && so_err == 0) {
                    rc = 0;
                }
            } else if (pr == 0) {
                g_saw_diag_timeout = 1;
            }
        }
        if (rc == 0) {
            if (flags >= 0) fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);
            return fd;
        }
        close(fd);
    }
    return -1;
#else
    (void)timeout_ms;
    return -1;
#endif
}

/* 跳过 diag-router 建连广播帧并等待目标控制应答包（接入全局超时与 g_saw_diag_timeout） */
static int diag_router_recv_ctrl(int fd, uint32_t expected_type,
                                 uint8_t *out, size_t min_len, size_t cap, int timeout_ms) {
    int eff_ms = compute_effective_timeout_ms(timeout_ms);
    if (eff_ms <= 0) {
        g_saw_diag_timeout = 1;
        return -1;
    }

    uint8_t buf[16384];
    long deadline = diag_now_ms() + eff_ms;

    for (;;) {
        long rem = deadline - diag_now_ms();
        if (rem <= 0) {
            g_saw_diag_timeout = 1;
            break;
        }
        struct pollfd pfd = { .fd = fd, .events = POLLIN };
        int pr = poll(&pfd, 1, (int)rem);
        if (pr == 0) {
            g_saw_diag_timeout = 1;
            break;
        }
        if (pr < 0) {
            if (errno == EINTR && !g_cmd_timed_out) continue;
            break;
        }

        rem = deadline - diag_now_ms();
        if (rem <= 0) {
            g_saw_diag_timeout = 1;
            break;
        }

        ssize_t n = diag_read_with_alarm(fd, buf, sizeof(buf), (int)rem);
        if (n == -2) {
            g_saw_diag_timeout = 1;
            break;
        }
        if (n <= 0) break;
        if ((size_t)n < min_len || (size_t)n < 4) continue;

        uint32_t dt = 0;
        memcpy(&dt, buf, 4);
        if (dt == expected_type) {
            size_t cp = ((size_t)n < cap) ? (size_t)n : cap;
            memcpy(out, buf, cp);
            return (int)cp;
        }
    }
    if (g_cmd_deadline_ms > 0L && !g_efs_write_critical && diag_now_ms() >= g_cmd_deadline_ms) {
        g_cmd_timed_out = 1;
    }
    return -1;
}

static int diag_router_detect(void) {
    int fd = diag_router_connect_socket(200);
    if (fd < 0) return 0;
    close(fd);
    return 1;
}

static int diag_router_open(diag_ctx_t *ctx, diag_mode_opt_t mode_opt) {
    ctx->fd = -1;
    ctx->token = 0;
    ctx->remote_mask = 0;
    ctx->is_remote = 0;

    ctx->fd = diag_router_connect_socket(1000);
    if (ctx->fd < 0) {
        fprintf(stderr, "connect(diag-router) failed: errno=%d (%s)\n", errno, strerror(errno));
        return -1;
    }

    uint16_t remote_mask = 0;
    uint32_t q_remote = DIAG_ROUTER_CMD_REMOTE_DEV;
    uint8_t ctrl_rsp[64];
    if (write(ctx->fd, &q_remote, sizeof(q_remote)) == (ssize_t)sizeof(q_remote)) {
        if (diag_router_recv_ctrl(ctx->fd, DIAG_ROUTER_RSP_REMOTE_DEV,
                                  ctrl_rsp, 6, sizeof(ctrl_rsp), 800) >= 6) {
            memcpy(&remote_mask, ctrl_rsp + 4, 2);
        }
    }
    ctx->remote_mask = remote_mask;
    ctx->is_remote = diag_resolve_is_remote(mode_opt, remote_mask);
    ctx->token = ctx->is_remote ? -1 : 0;

    uint8_t sw_req[32];
    int32_t self_pid = (int32_t)getpid();
    diag_router_build_switch_logging_req(CALLBACK_MODE, ctx->is_remote, self_pid, sw_req);
    if (write(ctx->fd, sw_req, sizeof(sw_req)) != (ssize_t)sizeof(sw_req)) {
        fprintf(stderr, "diag-router write(switch_logging) failed: errno=%d (%s)\n",
                errno, strerror(errno));
        close(ctx->fd);
        ctx->fd = -1;
        return -1;
    }

    if (diag_router_recv_ctrl(ctx->fd, DIAG_ROUTER_RSP_SWITCH_LOGGING,
                              ctrl_rsp, 8, sizeof(ctrl_rsp), 1500) < 8) {
        fprintf(stderr, "diag-router switch_logging timed out\n");
        close(ctx->fd);
        ctx->fd = -1;
        return -1;
    }
    int32_t sw_status = -1;
    memcpy(&sw_status, ctrl_rsp + 4, 4);
    if (sw_status != 0) {
        fprintf(stderr, "diag-router switch_logging failed: status=%d\n", sw_status);
        close(ctx->fd);
        ctx->fd = -1;
        return -1;
    }
    return 0;
}

static int diag_router_transact(const diag_ctx_t *ctx, const uint8_t *cmd, int cmd_len,
                                uint8_t *rsp_out, int rsp_cap, int timeout_ms) {
    uint8_t send_buf[4096];
    int tx_len = diag_router_build_tx_pkt(ctx->is_remote, ctx->token,
                                          cmd, cmd_len, send_buf, (int)sizeof(send_buf));
    if (tx_len < 0) return -1;

    if (write(ctx->fd, send_buf, (size_t)tx_len) != (ssize_t)tx_len) {
        fprintf(stderr, "diag-router write failed: errno=%d (%s)\n", errno, strerror(errno));
        return -1;
    }

    static uint8_t rbuf[65536];
    static uint8_t acc[65536];
    int acc_len = 0;
    int eff_ms = compute_effective_timeout_ms(timeout_ms);
    if (eff_ms <= 0) {
        g_saw_diag_timeout = 1;
        return -2;
    }
    long deadline = diag_now_ms() + (long)eff_ms;

    for (;;) {
        long rem = deadline - diag_now_ms();
        if (rem <= 0) break;
        struct pollfd pfd = { .fd = ctx->fd, .events = POLLIN };
        int pr = poll(&pfd, 1, (int)rem);
        if (pr == 0) break;
        if (pr < 0) {
            if (errno == EINTR) continue;
            return -1;
        }

        rem = deadline - diag_now_ms();
        if (rem <= 0) break;

        ssize_t n = diag_read_with_alarm(ctx->fd, rbuf, sizeof(rbuf), (int)rem);
        if (n == -2) {
            g_saw_diag_timeout = 1;
            return -2;
        }
        if (n <= 0) return -1;

        int parsed = diag_router_parse_rx_pkt(ctx->is_remote, ctx->token, rbuf, (int)n,
                                              cmd, cmd_len, acc, &acc_len, (int)sizeof(acc),
                                              rsp_out, rsp_cap);
        if (parsed != 0) return parsed;
    }

    if (g_cmd_deadline_ms > 0L && !g_efs_write_critical && diag_now_ms() >= g_cmd_deadline_ms) {
        g_cmd_timed_out = 1;
    }
    g_saw_diag_timeout = 1;
    return -2;
}

/* 关闭 diag-router 套接字：直接 close() 触发对端 Empty read (EOF)，
 * 由 diag_close_logging_process() 自动清理本 PID 会话并恢复 USB_MODE (0)；
 * 不可主动发送 NO_LOGGING_MODE，否则会提前清空 PID 并使全局 curr_mode 滞留为 4。 */
static void diag_router_close(diag_ctx_t *ctx) {
    if (ctx && ctx->fd >= 0) {
        close(ctx->fd);
        ctx->fd = -1;
    }
}

/* 预留动态库桥接后端占位实现（当前静态版未启用，使用 diagchar 或 diag-router） */
static int placeholder_backend_detect(void) {
    return 0;
}

static int placeholder_backend_open(diag_ctx_t *ctx, diag_mode_opt_t mode_opt) {
    (void)mode_opt;
    const char *name = (ctx && ctx->ops && ctx->ops->name) ? ctx->ops->name : "unknown";
    fprintf(stderr, "diag backend '%s' is not enabled in static build (use diagchar or diag-router)\n", name);
    return -1;
}

static int placeholder_backend_transact(const diag_ctx_t *ctx, const uint8_t *cmd, int cmd_len,
                                        uint8_t *rsp_out, int rsp_cap, int timeout_ms) {
    (void)ctx;
    (void)cmd;
    (void)cmd_len;
    (void)rsp_out;
    (void)rsp_cap;
    (void)timeout_ms;
    return -1;
}

static void placeholder_backend_close(diag_ctx_t *ctx) {
    if (ctx && ctx->fd >= 0) {
        close(ctx->fd);
        ctx->fd = -1;
    }
}

static const diag_transport_ops_t DIAGCHAR_OPS = {
    .name = "diagchar",
    .id = DIAG_BACKEND_DIAGCHAR,
    .detect = diagchar_detect,
    .open = diagchar_open,
    .transact = diagchar_transact,
    .close = diagchar_close,
};

static const diag_transport_ops_t DIAG_ROUTER_OPS = {
    .name = "diag-router",
    .id = DIAG_BACKEND_DIAG_ROUTER,
    .detect = diag_router_detect,
    .open = diag_router_open,
    .transact = diag_router_transact,
    .close = diag_router_close,
};

static const diag_transport_ops_t LIBDIAG_OPS = {
    .name = "libdiag",
    .id = DIAG_BACKEND_LIBDIAG,
    .detect = placeholder_backend_detect,
    .open = placeholder_backend_open,
    .transact = placeholder_backend_transact,
    .close = placeholder_backend_close,
};

static const diag_transport_ops_t *const ALL_DIAG_BACKENDS[] = {
    &DIAGCHAR_OPS,
    &DIAG_ROUTER_OPS,
    &LIBDIAG_OPS,
};

/* 解析命令行 --backend 参数值 */
static int parse_diag_backend(const char *arg, diag_backend_t *out) {
    if (!arg || !out) return -1;
    if (strcmp(arg, "auto") == 0) {
        *out = DIAG_BACKEND_AUTO;
        return 0;
    }
    if (strcmp(arg, "diagchar") == 0) {
        *out = DIAG_BACKEND_DIAGCHAR;
        return 0;
    }
    if (strcmp(arg, "diag-router") == 0 || strcmp(arg, "diag_router") == 0) {
        *out = DIAG_BACKEND_DIAG_ROUTER;
        return 0;
    }
    if (strcmp(arg, "libdiag") == 0) {
        *out = DIAG_BACKEND_LIBDIAG;
        return 0;
    }
    if (strcmp(arg, "none") == 0) {
        *out = DIAG_BACKEND_NONE;
        return 0;
    }
    return -1;
}

static const diag_transport_ops_t *find_diag_backend_by_id(diag_backend_t id) {
    size_t n = sizeof(ALL_DIAG_BACKENDS) / sizeof(ALL_DIAG_BACKENDS[0]);
    for (size_t i = 0; i < n; i++) {
        if (ALL_DIAG_BACKENDS[i]->id == id) {
            return ALL_DIAG_BACKENDS[i];
        }
    }
    return NULL;
}

static const char *resolve_backend_conf_path(void) {
    const char *env = getenv("VOWIFIX_BACKEND_CONF");
    return (env && *env) ? env : DEFAULT_BACKEND_CONF;
}

/* 读取显式后端降级配置（支持 BACKEND=none|diagchar|diag-router|auto 或裸值），命中有效配置返回 1 */
static int read_backend_conf_override(const char *path, diag_backend_t *out) {
    if (!path || !out) return 0;
    FILE *fp = fopen(path, "r");
    if (!fp) return 0;

    char line[256];
    int found = 0;
    while (fgets(line, (int)sizeof(line), fp) != NULL) {
        char *p = line;
        while (*p && isspace((unsigned char)*p)) p++;
        if (*p == '\0' || *p == '#') continue;

        char *end = p + strlen(p);
        while (end > p && isspace((unsigned char)end[-1])) {
            *--end = '\0';
        }
        if (strncmp(p, "BACKEND=", 8) == 0) {
            p += 8;
            while (*p && isspace((unsigned char)*p)) p++;
        }
        diag_backend_t parsed = DIAG_BACKEND_AUTO;
        if (parse_diag_backend(p, &parsed) == 0) {
            *out = parsed;
            found = 1;
            break;
        }
    }
    fclose(fp);
    return found;
}

/* 按优先级无副作用探测当前设备可用的 Diag 后端；若无可用通道返回 NULL */
static const diag_transport_ops_t *resolve_diag_backend(diag_backend_t req) {
    if (req == DIAG_BACKEND_NONE) return NULL;
    if (req == DIAG_BACKEND_AUTO) {
        size_t n = sizeof(ALL_DIAG_BACKENDS) / sizeof(ALL_DIAG_BACKENDS[0]);
        for (size_t i = 0; i < n; i++) {
            if (ALL_DIAG_BACKENDS[i]->detect && ALL_DIAG_BACKENDS[i]->detect()) {
                return ALL_DIAG_BACKENDS[i];
            }
        }
        return NULL;
    }
    const diag_transport_ops_t *ops = find_diag_backend_by_id(req);
    if (ops && ops->detect && ops->detect()) {
        return ops;
    }
    return NULL;
}

/* 通过多后端虚表开启 Diag 会话 */
static int diag_open(diag_ctx_t *ctx, diag_backend_t req_backend, diag_mode_opt_t mode_opt) {
    if (!ctx) return -1;
    memset(ctx, 0, sizeof(*ctx));
    ctx->fd = -1;

    if (req_backend == DIAG_BACKEND_NONE) {
        fprintf(stderr, "diag backend disabled (--backend=none)\n");
        return -1;
    }

    const diag_transport_ops_t *ops = (req_backend == DIAG_BACKEND_AUTO)
        ? resolve_diag_backend(DIAG_BACKEND_AUTO)
        : find_diag_backend_by_id(req_backend);
    if (!ops) {
        fprintf(stderr, "no available Diag backend detected (neither /dev/diag nor diag-router reachable)\n");
        return -1;
    }

    ctx->ops = ops;
    if (!ops->open || ops->open(ctx, mode_opt) != 0) {
        ctx->ops = NULL;
        return -1;
    }
    return 0;
}

static int diag_transact(const diag_ctx_t *ctx, const uint8_t *cmd, int cmd_len,
                         uint8_t *rsp_out, int rsp_cap, int timeout_ms) {
    if (!ctx || !ctx->ops || !ctx->ops->transact) return -1;
    return ctx->ops->transact(ctx, cmd, cmd_len, rsp_out, rsp_cap, timeout_ms);
}

static void diag_close(diag_ctx_t *ctx) {
    if (ctx && ctx->ops && ctx->ops->close) {
        ctx->ops->close(ctx);
    }
    if (ctx) {
        ctx->ops = NULL;
    }
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
    return diag_transact(ctx, cmd, 4 + body_len, rsp, rsp_cap, 2000);
}

/* ===================== EFS2 文件读写、校验与同步层 ===================== */

/* 检查 EFS 路径是否在允许写入的双卡白名单内 */
static int is_allowed_write_path(const char *efs_path) {
    if (!efs_path) return 0;
    return strcmp(efs_path, EFS_PATH_SLOT0) == 0 ||
           strcmp(efs_path, EFS_PATH_SLOT1) == 0 ||
           strcmp(efs_path, EFS_SE_PATH_SLOT0) == 0 ||
           strcmp(efs_path, EFS_SE_PATH_SLOT1) == 0 ||
           strcmp(efs_path, EFS_S2B_PATH_SLOT0) == 0 ||
           strcmp(efs_path, EFS_S2B_PATH_SLOT1) == 0;
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
    if (read_ok || (!g_cmd_timed_out && !g_saw_diag_timeout)) {
        send_efs_cmd(ctx, EFS2_DIAG_CLOSE, (const uint8_t *)&fd_efs, 4, rsp, sizeof(rsp));
    }
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

    int res = -1;
    uint8_t *verify_buf = NULL;
    size_t verify_len = 0;

    /* 写入与校验临界区：禁用外层短时限中途打断，确保半截写入不会直接被中断 */
    g_efs_write_critical = 1;

    int32_t oflag = EFS2_DIAG_OPEN_WRONLY_TRUNC;
    int32_t fmode = EFS2_DEFAULT_MODE;
    memcpy(body, &oflag, 4);
    memcpy(body + 4, &fmode, 4);
    memcpy(body + 8, efs_path, (size_t)plen);

    int r = send_efs_cmd(ctx, EFS2_DIAG_OPEN, body, 8 + plen, rsp, sizeof(rsp));
    if (r < 12) goto write_done;
    int32_t fd_efs = -1, diag_errno = -1;
    memcpy(&fd_efs, rsp + 4, 4);
    memcpy(&diag_errno, rsp + 8, 4);
    if (fd_efs < 0 || diag_errno != 0) {
        fprintf(stderr, "OPEN(%s, WRONLY|TRUNC) failed: fd=%d errno=%d\n", efs_path, fd_efs, diag_errno);
        goto write_done;
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
        goto write_done;
    }

    uint32_t st_mode = 0, st_size = 0;
    if (efs_stat(ctx, efs_path, &st_mode, &st_size) != 0 || (size_t)st_size != len) {
        fprintf(stderr, "VERIFY FAILED: STAT %s size=%u != expected=%zu\n", efs_path, st_size, len);
        goto write_done;
    }

    if (efs_read_all_mem(ctx, efs_path, &verify_buf, &verify_len) != 0 || verify_len != len ||
        memcmp(verify_buf, data, len) != 0) {
        fprintf(stderr, "VERIFY FAILED: read-back mismatch on %s\n", efs_path);
        goto write_done;
    }
    printf("VERIFY PASSED: %s (%zu bytes)\n", efs_path, len);
    res = 0;

write_done:
    free(verify_buf);
    g_efs_write_critical = 0;
    return res;
}

/* 发起 EFS2_DIAG_SYNC_NO_WAIT 并等待 EFS_SYNC_COMPLETE=1 落盘 */
static int efs_sync(const diag_ctx_t *ctx) {
    uint8_t body[16], rsp[1024];
    uint16_t seq = 1;
    memcpy(body, &seq, 2);
    body[2] = '/';
    body[3] = '\0';

    int res = -1;
    g_efs_write_critical = 1;

    int r = send_efs_cmd(ctx, EFS2_DIAG_SYNC_NO_WAIT, body, 4, rsp, sizeof(rsp));
    if (r < 14) goto sync_done;

    uint32_t sync_token = 0;
    int32_t diag_errno = -1;
    memcpy(&sync_token, rsp + 6, 4);
    memcpy(&diag_errno, rsp + 10, 4);
    if (diag_errno != 0) goto sync_done;

    for (int attempt = 1; attempt <= 30; attempt++) {
        usleep(500 * 1000);
        uint16_t qseq = (uint16_t)(seq + attempt);
        uint8_t qbody[8];
        memcpy(qbody, &qseq, 2);
        memcpy(qbody + 2, &sync_token, 4);
        qbody[6] = '/';
        qbody[7] = '\0';

        r = send_efs_cmd(ctx, EFS2_DIAG_GET_SYNC_STATUS, qbody, 8, rsp, sizeof(rsp));
        if (r < 11) goto sync_done;

        uint8_t sync_status = 0xFF;
        int32_t st_errno = -1;
        if (r == 11) {
            sync_status = rsp[6];
            memcpy(&st_errno, rsp + 7, 4);
        } else if (r >= 15) {
            sync_status = rsp[10];
            memcpy(&st_errno, rsp + 11, 4);
        } else {
            goto sync_done;
        }
        if (st_errno != 0) goto sync_done;
        if (sync_status == 1) {
            printf("SYNC COMPLETE: EFS_SYNC_COMPLETE=1\n");
            res = 0;
            goto sync_done;
        }
    }
    fprintf(stderr, "EFS sync timeout\n");

sync_done:
    g_efs_write_critical = 0;
    return res;
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

/* ===================== iwlan_s2b_config.xml 静态 ePDG FQDN 定点解锁 ===================== */

typedef enum {
    S2B_STATE_NO_STATIC_FQDN = 0,
    S2B_STATE_ALREADY_ENABLED = 1,
    S2B_STATE_NEEDS_PATCH = 2
} s2b_fqdn_state_t;

static const uint8_t *mem_find_sub(const uint8_t *hay, size_t hay_len, const char *needle) {
    size_t nlen = strlen(needle);
    if (!hay || nlen == 0 || hay_len < nlen) return NULL;
    for (size_t i = 0; i <= hay_len - nlen; i++) {
        if (hay[i] == (uint8_t)needle[0] && memcmp(hay + i, needle, nlen) == 0) {
            return hay + i;
        }
    }
    return NULL;
}

/* 在 XML 缓冲区中匹配非注释区（跳过所有 <!-- ... --> 块）内的子串 */
static const uint8_t *mem_find_sub_skip_xml_comments(const uint8_t *hay, size_t hay_len, const char *needle) {
    size_t nlen = needle ? strlen(needle) : 0;
    if (!hay || nlen == 0 || hay_len < nlen) return NULL;
    size_t i = 0;
    while (i < hay_len) {
        if (i + 4 <= hay_len && memcmp(hay + i, "<!--", 4) == 0) {
            const uint8_t *end_cmt = mem_find_sub(hay + i + 4, hay_len - (i + 4), "-->");
            if (!end_cmt) return NULL;
            i = (size_t)(end_cmt - hay) + 3;
            continue;
        }
        if (i + nlen <= hay_len && hay[i] == (uint8_t)needle[0] && memcmp(hay + i, needle, nlen) == 0) {
            return hay + i;
        }
        i++;
    }
    return NULL;
}

static void trim_ascii_span(const uint8_t *buf, size_t len, size_t *out_off, size_t *out_len) {
    size_t s = 0, e = len;
    while (s < e && isspace((unsigned char)buf[s])) s++;
    while (e > s && isspace((unsigned char)buf[e - 1])) e--;
    *out_off = s;
    *out_len = e - s;
}

static int ascii_eq_nocase(const uint8_t *buf, size_t len, const char *lit) {
    size_t llen = strlen(lit);
    if (!buf || len != llen) return 0;
    for (size_t i = 0; i < len; i++) {
        if (tolower((unsigned char)buf[i]) != tolower((unsigned char)lit[i])) {
            return 0;
        }
    }
    return 1;
}

/* 在 XML 区间内查找首对非注释且匹配的 <tag_name ...>...</tag_name>（忽略自闭合标签与近似前缀标签）；
 * 找到返回 1，同时可选通过 elem_end_out 返回闭合标签结束偏移以便连续扫描同级块。 */
static int xml_find_element_from(const uint8_t *scope, size_t scope_len, size_t start_off,
                                 const char *tag_name, const uint8_t **val_start, size_t *val_len,
                                 size_t *elem_end_out) {
    char open_prefix[96];
    char close_tag[96];
    int op_n = snprintf(open_prefix, sizeof(open_prefix), "<%s", tag_name);
    int cl_n = snprintf(close_tag, sizeof(close_tag), "</%s>", tag_name);
    if (op_n <= 1 || cl_n <= 3 || !scope || start_off >= scope_len) return 0;

    size_t pos = start_off;
    while (pos < scope_len) {
        const uint8_t *p = mem_find_sub_skip_xml_comments(scope + pos, scope_len - pos, open_prefix);
        if (!p) return 0;
        size_t off = (size_t)(p - scope);
        size_t after = off + (size_t)op_n;
        if (after >= scope_len) return 0;

        uint8_t next_ch = scope[after];
        if (next_ch != '>' && next_ch != '/' && !isspace((unsigned char)next_ch)) {
            pos = after;
            continue;
        }

        const uint8_t *gt = (const uint8_t *)memchr(scope + after, '>', scope_len - after);
        if (!gt) return 0;
        size_t gt_off = (size_t)(gt - scope);
        if (gt_off > after && scope[gt_off - 1] == '/') {
            /* 自闭合标签如 <fqdn/> 不含文本值，跳过 */
            pos = gt_off + 1;
            continue;
        }
        if (next_ch == '/') {
            pos = gt_off + 1;
            continue;
        }

        size_t body_start = gt_off + 1;
        const uint8_t *close_p = mem_find_sub_skip_xml_comments(scope + body_start, scope_len - body_start, close_tag);
        if (!close_p) return 0;
        size_t body_end = (size_t)(close_p - scope);

        if (val_start) *val_start = scope + body_start;
        if (val_len) *val_len = body_end - body_start;
        if (elem_end_out) *elem_end_out = body_end + (size_t)cl_n;
        return 1;
    }
    return 0;
}

static s2b_fqdn_state_t s2b_inspect_static_fqdn_loc(const uint8_t *buf, size_t len,
                                                     char *fqdn_out, size_t fqdn_cap,
                                                     size_t *flag_off_out, size_t *flag_len_out) {
    if (fqdn_out && fqdn_cap > 0) fqdn_out[0] = '\0';
    if (!buf || len == 0) return S2B_STATE_NO_STATIC_FQDN;

    size_t scan_off = 0;
    const uint8_t *blk = NULL;
    size_t blk_len = 0;
    size_t next_off = 0;
    int saw_already_enabled = 0;

    while (xml_find_element_from(buf, len, scan_off, "epdg_addr_info", &blk, &blk_len, &next_off)) {
        scan_off = next_off;

        const uint8_t *fqdn_p = NULL;
        size_t fqdn_n = 0;
        if (!xml_find_element_from(blk, blk_len, 0, "fqdn", &fqdn_p, &fqdn_n, NULL)) {
            continue;
        }
        size_t f_off = 0, f_tlen = 0;
        trim_ascii_span(fqdn_p, fqdn_n, &f_off, &f_tlen);
        if (f_tlen == 0) {
            continue;
        }

        const uint8_t *en_p = NULL;
        size_t en_n = 0;
        if (!xml_find_element_from(blk, blk_len, 0, "static_fqdn_enabled", &en_p, &en_n, NULL)) {
            continue;
        }
        size_t e_off = 0, e_tlen = 0;
        trim_ascii_span(en_p, en_n, &e_off, &e_tlen);

        if (fqdn_out && fqdn_cap > 1) {
            size_t cp = f_tlen < fqdn_cap - 1 ? f_tlen : fqdn_cap - 1;
            memcpy(fqdn_out, fqdn_p + f_off, cp);
            fqdn_out[cp] = '\0';
        }

        if (ascii_eq_nocase(en_p + e_off, e_tlen, "FALSE")) {
            if (flag_off_out) *flag_off_out = (size_t)((en_p + e_off) - buf);
            if (flag_len_out) *flag_len_out = e_tlen;
            return S2B_STATE_NEEDS_PATCH;
        }
        if (ascii_eq_nocase(en_p + e_off, e_tlen, "TRUE")) {
            saw_already_enabled = 1;
        }
    }

    return saw_already_enabled ? S2B_STATE_ALREADY_ENABLED : S2B_STATE_NO_STATIC_FQDN;
}

/* 检测 <epdg_addr_info> 是否配有非空静态 <fqdn> 及其启用状态；并输出解析到的 fqdn */
static s2b_fqdn_state_t s2b_inspect_static_fqdn(const uint8_t *buf, size_t len,
                                                char *fqdn_out, size_t fqdn_cap) {
    return s2b_inspect_static_fqdn_loc(buf, len, fqdn_out, fqdn_cap, NULL, NULL);
}

/* 对存在非空 <fqdn> 且 <static_fqdn_enabled> 为 FALSE 的 XML 做最小字节替换（仅改 FALSE -> TRUE）：
 * 成功生成新缓冲区返回 0，无需改动返回 1，参数/内存异常返回 -1。 */
static int s2b_apply_static_fqdn_patch(const uint8_t *orig, size_t orig_len,
                                       uint8_t **out_buf, size_t *out_len) {
    if (!orig || !out_buf || !out_len) return -1;
    *out_buf = NULL;
    *out_len = 0;

    size_t flag_off = 0, flag_len = 0;
    s2b_fqdn_state_t st = s2b_inspect_static_fqdn_loc(orig, orig_len, NULL, 0, &flag_off, &flag_len);
    if (st != S2B_STATE_NEEDS_PATCH) {
        return 1;
    }

    size_t new_len = orig_len - flag_len + 4;
    uint8_t *out = (uint8_t *)malloc(new_len + 1);
    if (!out) return -1;

    memcpy(out, orig, flag_off);
    memcpy(out + flag_off, "TRUE", 4);
    memcpy(out + flag_off + 4, orig + flag_off + flag_len, orig_len - flag_off - flag_len);
    out[new_len] = '\0';

    *out_buf = out;
    *out_len = new_len;
    return 0;
}

/* 对共享锁文件加互斥锁（支持限时非阻塞轮询，防止 CLI 直接调用在外部锁死时无限挂起） */
static int acquire_diag_lock(const char *lock_path, int wait_timeout_ms, int *out_busy) {
    if (out_busy) *out_busy = 0;
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
    if (wait_timeout_ms <= 0) {
        while (flock(lfd, LOCK_EX) != 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "flock(%s) failed: %s\n", lock_path, strerror(errno));
            close(lfd);
            return -1;
        }
        return lfd;
    }

    long deadline = diag_now_ms() + wait_timeout_ms;
    for (;;) {
        if (flock(lfd, LOCK_EX | LOCK_NB) == 0) {
            return lfd;
        }
        if (errno != EWOULDBLOCK && errno != EAGAIN && errno != EINTR) {
            fprintf(stderr, "flock(%s) failed: %s\n", lock_path, strerror(errno));
            close(lfd);
            return -1;
        }
        long rem = deadline - diag_now_ms();
        if (rem <= 0) {
            printf("DIAG_LOCK_BUSY\n");
            fprintf(stderr, "DIAG_LOCK_BUSY: lock %s busy after %dms\n", lock_path, wait_timeout_ms);
            if (out_busy) *out_busy = 1;
            close(lfd);
            return -1;
        }
        struct timespec ts = { .tv_sec = 0, .tv_nsec = (rem < 100 ? rem : 100) * 1000000L };
        nanosleep(&ts, NULL);
    }
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

/* 构造 QMI_VS_BIND_SUBSCRIPTION_REQ_MSG_V01 (0x0021) 请求报文，指定后续刷新目标卡槽订阅 */
static int qmi_build_bind_subs_req(uint16_t txn_id, uint32_t subs_id, uint8_t *out, size_t cap) {
    if (!out || cap < sizeof(struct qmi_msg_hdr) + 7) return -1;
    struct qmi_msg_hdr *hdr = (struct qmi_msg_hdr *)out;
    hdr->flags = QMI_HDR_FLAG_REQ;
    hdr->txn_id = txn_id;
    hdr->msg_id = QMI_VS_MSG_BIND_SUBS;
    hdr->msg_len = 7;

    uint8_t *p = out + sizeof(struct qmi_msg_hdr);
    /* TLV 0x01: subs_id (4B LE: 0=PRIMARY, 1=SECONDARY, 2=TERTIARY) */
    p[0] = 0x01;
    p[1] = 0x04;
    p[2] = 0x00;
    p[3] = (uint8_t)(subs_id & 0xFF);
    p[4] = (uint8_t)((subs_id >> 8) & 0xFF);
    p[5] = (uint8_t)((subs_id >> 16) & 0xFF);
    p[6] = (uint8_t)((subs_id >> 24) & 0xFF);
    return (int)(sizeof(struct qmi_msg_hdr) + 7);
}

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

/* 发送 QMI 请求并阻塞等待目标事务响应，自动跳过路过事件包；返回 0=成功，1=QMI拒绝，-1=通信或解析失败 */
static int qmi_send_and_wait_void(int fd, const struct sockaddr_qrtr_uapi *dst,
                                   const uint8_t *req, size_t req_len,
                                   uint16_t txn_id, uint16_t expected_msg,
                                   uint32_t *out_res, uint32_t *out_err) {
    if (sendto(fd, req, req_len, 0, (const struct sockaddr *)dst, sizeof(*dst)) < 0) {
        fprintf(stderr, "QMI msg 0x%04x sendto failed: %s\n", expected_msg, strerror(errno));
        return -1;
    }

    uint8_t rsp[512];
    for (int tries = 0; tries < 8; tries++) {
        ssize_t n = recv(fd, rsp, sizeof(rsp), 0);
        if (n < 0) {
            fprintf(stderr, "QMI msg 0x%04x recv timeout/error: %s\n", expected_msg, strerror(errno));
            return -1;
        }
        uint32_t qres = 0xFFFFFFFFU, qerr = 0xFFFFFFFFU;
        int pr = qmi_parse_void_resp(rsp, (size_t)n, txn_id, expected_msg, &qres, &qerr);
        if (pr == -2) continue; /* 忽略路过事件包或非目标响应 */
        if (out_res) *out_res = qres;
        if (out_err) *out_err = qerr;
        if (pr != 0) {
            fprintf(stderr, "QMI msg 0x%04x response invalid: parse=%d\n", expected_msg, pr);
            return -1;
        }
        return (qres == 0 && qerr == 0) ? 0 : 1;
    }

    fprintf(stderr, "QMI msg 0x%04x failed: no matching response\n", expected_msg);
    return -1;
}

/* 对单一卡槽订阅创建独立 QRTR 客户端并执行订阅绑定 + MCFG/EFS 热刷新；返回 0=成功，-2=非主卡绑定被拒跳过，-1=失败 */
static int qmi_trigger_single_sub_refresh(uint32_t subs_id, uint32_t config_type,
                                          uint32_t activation_type, int strict_bind) {
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

    struct sockaddr_qrtr_uapi dst;
    memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = node;
    dst.sq_port = port;

    /* 必须先为当前连接绑定目标卡槽订阅，否则 QMI_VS 默认只刷新主卡 (subs_id=0) */
    uint8_t bind_req[32];
    int bind_len = qmi_build_bind_subs_req(1, subs_id, bind_req, sizeof(bind_req));
    if (bind_len < 0) {
        close(fd);
        return -1;
    }
    uint32_t bres = 0xFFFFFFFFU, berr = 0xFFFFFFFFU;
    int brc = qmi_send_and_wait_void(fd, &dst, bind_req, (size_t)bind_len,
                                     1, QMI_VS_MSG_BIND_SUBS, &bres, &berr);
    if (brc != 0) {
        if (subs_id == QMI_SUBS_PRIMARY) {
            /* 新建客户端默认已绑定主卡 0，若旧固件未开放 0x0021 告警后继续尝试刷新 */
            fprintf(stderr, "WARN: QMI_VS bind_subscription(sub=%u) returned rc=%d qmi_res=%u qmi_err=%u; continuing with default primary binding\n",
                    subs_id, brc, bres, berr);
        } else if (strict_bind) {
            fprintf(stderr, "QMI bind_subscription(sub=%u) rejected: rc=%d qmi_res=%u qmi_err=%u\n",
                    subs_id, brc, bres, berr);
            close(fd);
            return -1;
        } else {
            printf("EFS_REFRESH_SKIP sub=%u reason=bind_unsupported (rc=%d qmi_res=%u qmi_err=%u)\n",
                   subs_id, brc, bres, berr);
            close(fd);
            return -2;
        }
    }

    uint8_t ref_req[64];
    int max_attempts = (!strict_bind && subs_id == QMI_SUBS_SECONDARY) ? 2 : 1;
    uint32_t qres = 0xFFFFFFFFU, qerr = 0xFFFFFFFFU;
    int rrc = -1;
    for (int attempt = 0; attempt < max_attempts; attempt++) {
        uint16_t txn = (uint16_t)(2 + attempt);
        int ref_len = qmi_build_refresh_efs_req(txn, config_type, activation_type, ref_req, sizeof(ref_req));
        if (ref_len < 0) {
            close(fd);
            return -1;
        }
        qres = 0xFFFFFFFFU;
        qerr = 0xFFFFFFFFU;
        rrc = qmi_send_and_wait_void(fd, &dst, ref_req, (size_t)ref_len,
                                     txn, QMI_VS_MSG_REFRESH_EFS, &qres, &qerr);
        if (rrc == 0) {
            close(fd);
            printf("EFS_REFRESH_OK=1 sub=%u node=%u port=%u cfg=%u act=%u\n",
                   subs_id, node, port, config_type, activation_type);
            return 0;
        }
        /* all 模式下紧随主卡刷副卡若遭遇 MCFG 瞬时忙，静候 1 秒后重试一次 */
        if (attempt + 1 < max_attempts) {
            fprintf(stderr, "WARN: QMI refresh sub=%u busy/failed (rc=%d qmi_res=%u qmi_err=%u), retrying after 1s...\n",
                    subs_id, rrc, qres, qerr);
            sleep(1);
        }
    }

    fprintf(stderr, "QMI refresh(sub=%u) rejected: rc=%d qmi_res=%u qmi_err=%u\n",
            subs_id, rrc, qres, qerr);
    close(fd);
    return -1;
}

/* 解析 refresh 子命令的可选卡槽参数 */
static int parse_refresh_slot(const char *arg, refresh_slot_opt_t *out) {
    if (!arg || !out) return -1;
    if (strcmp(arg, "all") == 0) {
        *out = REFRESH_SLOT_ALL;
        return 0;
    }
    if (strcmp(arg, "slot0") == 0) {
        *out = REFRESH_SLOT_0;
        return 0;
    }
    if (strcmp(arg, "slot1") == 0) {
        *out = REFRESH_SLOT_1;
        return 0;
    }
    return -1;
}

/* 通过纯内核 QRTR 按目标卡槽（slot0/slot1/all）依次绑定订阅并发送高通 MCFG/EFS 热刷新指令 */
static int qmi_trigger_modem_efs_refresh(refresh_slot_opt_t slot_opt, uint32_t config_type, uint32_t activation_type) {
    if (slot_opt == REFRESH_SLOT_0) {
        return qmi_trigger_single_sub_refresh(QMI_SUBS_PRIMARY, config_type, activation_type, 1);
    }
    if (slot_opt == REFRESH_SLOT_1) {
        return qmi_trigger_single_sub_refresh(QMI_SUBS_SECONDARY, config_type, activation_type, 1);
    }

    int rc0 = qmi_trigger_single_sub_refresh(QMI_SUBS_PRIMARY, config_type, activation_type, 0);
    if (rc0 == 0) {
        /* 双卡依次刷新间预留 500ms 间隔，避免基带 MCFG 队列瞬时拥塞 */
        usleep(500 * 1000);
    }
    int rc1 = qmi_trigger_single_sub_refresh(QMI_SUBS_SECONDARY, config_type, activation_type, 0);
    return (rc0 == 0 || rc1 == 0) ? 0 : -1;
}

/* ===================== QRTR / QMI NAS 只读蜂窝状态核验 ===================== */

/* 构造 QMI_NAS_BIND_SUBSCRIPTION_REQ_MSG_V01 (0x00AF) 请求报文，兼容 4 字节与 1 字节 TLV 0x01 */
static int qmi_build_nas_bind_subs_req(uint16_t txn_id, uint32_t subs_id, int use_u8,
                                       uint8_t *out, size_t cap) {
    size_t vlen = use_u8 ? 1U : 4U;
    if (!out || cap < sizeof(struct qmi_msg_hdr) + 3U + vlen) return -1;
    struct qmi_msg_hdr *hdr = (struct qmi_msg_hdr *)out;
    hdr->flags = QMI_HDR_FLAG_REQ;
    hdr->txn_id = txn_id;
    hdr->msg_id = QMI_NAS_MSG_BIND_SUBS;
    hdr->msg_len = (uint16_t)(3U + vlen);

    uint8_t *p = out + sizeof(struct qmi_msg_hdr);
    p[0] = 0x01;
    p[1] = (uint8_t)(vlen & 0xFF);
    p[2] = 0x00;
    p[3] = (uint8_t)(subs_id & 0xFF);
    if (!use_u8) {
        p[4] = (uint8_t)((subs_id >> 8) & 0xFF);
        p[5] = (uint8_t)((subs_id >> 16) & 0xFF);
        p[6] = (uint8_t)((subs_id >> 24) & 0xFF);
    }
    return (int)(sizeof(struct qmi_msg_hdr) + 3U + vlen);
}

/* 解析标准 QMI TLV 0x02 (qmi_response_type_v01: 2B result + 2B error) */
static int qmi_find_tlv(const uint8_t *buf, size_t len, uint16_t expected_msg,
                        uint8_t target_type, const uint8_t **out_val, uint16_t *out_len) {
    if (!buf || len < sizeof(struct qmi_msg_hdr)) return -1;
    const struct qmi_msg_hdr *rh = (const struct qmi_msg_hdr *)buf;
    if (rh->flags != QMI_HDR_FLAG_RESP || rh->msg_id != expected_msg) return -1;
    if (sizeof(struct qmi_msg_hdr) + (size_t)rh->msg_len > len) return -1;

    size_t off = sizeof(struct qmi_msg_hdr);
    size_t end = off + (size_t)rh->msg_len;
    while (off + 3 <= end) {
        uint8_t t = buf[off];
        uint16_t l = (uint16_t)(buf[off + 1] | ((uint16_t)buf[off + 2] << 8));
        off += 3;
        if (off + l > end) return -1;
        if (t == target_type) {
            if (out_val) *out_val = buf + off;
            if (out_len) *out_len = l;
            return 0;
        }
        off += l;
    }
    return -1;
}

static int qmi_parse_nas_result_tlv(const uint8_t *buf, size_t len, uint16_t expected_msg,
                                    uint32_t *out_res, uint32_t *out_err) {
    const uint8_t *val = NULL;
    uint16_t vlen = 0;
    if (qmi_find_tlv(buf, len, expected_msg, 0x02, &val, &vlen) != 0 || vlen < 4) {
        return -1;
    }
    uint32_t r = (uint32_t)(val[0] | ((uint16_t)val[1] << 8));
    uint32_t e = (uint32_t)(val[2] | ((uint16_t)val[3] << 8));
    if (out_res) *out_res = r;
    if (out_err) *out_err = e;
    return 0;
}

static void format_nas_mode_pref(uint16_t mode_pref, char *out, size_t cap) {
    static const struct {
        uint16_t bit;
        const char *name;
    } BITS[] = {
        {0x0001U, "CDMA_1X"},
        {0x0002U, "EVDO"},
        {0x0004U, "GSM"},
        {0x0008U, "UMTS"},
        {0x0010U, "LTE"},
        {0x0020U, "TDSCDMA"},
        {0x0040U, "NR5G"},
    };
    if (!out || cap == 0) return;
    out[0] = '\0';
    size_t pos = 0;
    uint16_t known = 0;
    for (size_t i = 0; i < sizeof(BITS) / sizeof(BITS[0]); i++) {
        if (mode_pref & BITS[i].bit) {
            known |= BITS[i].bit;
            int n = snprintf(out + pos, cap - pos, "%s%s", pos ? "|" : "", BITS[i].name);
            if (n > 0 && (size_t)n < cap - pos) pos += (size_t)n;
        }
    }
    uint16_t extra = (uint16_t)(mode_pref & ~known);
    if (extra != 0) {
        int n = snprintf(out + pos, cap - pos, "%s0x%04x", pos ? "|" : "", extra);
        if (n > 0 && (size_t)n < cap - pos) pos += (size_t)n;
    }
    if (pos == 0) {
        snprintf(out, cap, "NONE");
    }
}

static const char *nas_reg_state_desc(int st, int roam) {
    switch (st) {
        case 0: return "NOT_REG";
        case 1:
            if (roam == 0) return "REGISTERED_ROAM";
            if (roam == 1) return "REGISTERED_HOME";
            return "REGISTERED";
        case 2: return "SEARCHING";
        case 3: return "DENIED";
        case 4: return "UNKNOWN";
        default: return "OTHER";
    }
}

/* 解析 QMI_NAS_GET_SYSTEM_SELECTION_PREFERENCE_RESP (0x0034) */
static int qmi_parse_nas_sys_sel_pref_resp(const uint8_t *buf, size_t len,
                                           uint16_t *out_mode_pref, int *out_net_sel) {
    uint32_t res = 0xFFFFU, err = 0xFFFFU;
    if (qmi_parse_nas_result_tlv(buf, len, QMI_NAS_MSG_GET_SYS_SEL_PREF, &res, &err) != 0) {
        return -1;
    }
    if (res != 0 || err != 0) return -1;

    const uint8_t *val = NULL;
    uint16_t vlen = 0;
    uint16_t mp = 0;
    if (qmi_find_tlv(buf, len, QMI_NAS_MSG_GET_SYS_SEL_PREF, 0x11, &val, &vlen) == 0 && vlen >= 2) {
        mp = (uint16_t)(val[0] | ((uint16_t)val[1] << 8));
    }
    int nsel = -1;
    if (qmi_find_tlv(buf, len, QMI_NAS_MSG_GET_SYS_SEL_PREF, 0x16, &val, &vlen) == 0 && vlen >= 1) {
        nsel = (int)val[0];
    }
    if (out_mode_pref) *out_mode_pref = mp;
    if (out_net_sel) *out_net_sel = nsel;
    return 0;
}

/* 解析 QMI_NAS_GET_SERVING_SYSTEM_RESP (0x0024)：TLV 0x01 注册态与 TLV 0x10 漫游指示 (0=ROAM,1=HOME) */
static int qmi_parse_nas_serving_system_resp(const uint8_t *buf, size_t len,
                                             int *out_reg_state, int *out_roam,
                                             char *plmn_out, size_t plmn_cap) {
    uint32_t res = 0xFFFFU, err = 0xFFFFU;
    if (qmi_parse_nas_result_tlv(buf, len, QMI_NAS_MSG_GET_SERVING_SYSTEM, &res, &err) != 0) {
        return -1;
    }
    if (res != 0 || err != 0) return -1;

    const uint8_t *val = NULL;
    uint16_t vlen = 0;
    int reg_st = -1;
    if (qmi_find_tlv(buf, len, QMI_NAS_MSG_GET_SERVING_SYSTEM, 0x01, &val, &vlen) == 0 && vlen >= 1) {
        reg_st = (int)val[0];
    }
    int roam = -1;
    if (qmi_find_tlv(buf, len, QMI_NAS_MSG_GET_SERVING_SYSTEM, 0x10, &val, &vlen) == 0 && vlen >= 1) {
        roam = (int)val[0];
    }
    if (out_reg_state) *out_reg_state = reg_st;
    if (out_roam) *out_roam = roam;
    if (plmn_out && plmn_cap > 0) {
        snprintf(plmn_out, plmn_cap, "-");
        if (reg_st == 1 &&
            qmi_find_tlv(buf, len, QMI_NAS_MSG_GET_SERVING_SYSTEM, 0x12, &val, &vlen) == 0 &&
            vlen >= 4) {
            uint16_t mcc = (uint16_t)(val[0] | ((uint16_t)val[1] << 8));
            uint16_t mnc = (uint16_t)(val[2] | ((uint16_t)val[3] << 8));
            if (mcc > 0 && mcc != 0xFFFFU && mnc != 0xFFFFU) {
                snprintf(plmn_out, plmn_cap, "%u/%02u", mcc, mnc);
            }
        }
    }
    return 0;
}

/* 发送 QMI 请求并接收完整响应报文（跳过异步事件与非目标事务） */
static int qmi_send_and_recv_msg(int fd, const struct sockaddr_qrtr_uapi *dst,
                                 const uint8_t *req, size_t req_len,
                                 uint16_t txn_id, uint16_t expected_msg,
                                 uint8_t *rsp_out, size_t rsp_cap, size_t *out_len) {
    if (sendto(fd, req, req_len, 0, (const struct sockaddr *)dst, sizeof(*dst)) < 0) {
        return -1;
    }
    for (int tries = 0; tries < 10; tries++) {
        ssize_t n = recv(fd, rsp_out, rsp_cap, 0);
        if (n < 0) return -1;
        if ((size_t)n < sizeof(struct qmi_msg_hdr)) continue;
        const struct qmi_msg_hdr *rh = (const struct qmi_msg_hdr *)rsp_out;
        if (rh->flags != QMI_HDR_FLAG_RESP || rh->txn_id != txn_id || rh->msg_id != expected_msg) {
            continue;
        }
        if (sizeof(struct qmi_msg_hdr) + (size_t)rh->msg_len > (size_t)n) return -1;
        if (out_len) *out_len = (size_t)n;
        return 0;
    }
    return -1;
}

/* 纯只读查询单卡槽 QMI_NAS (0x03) 绑定、系统选择偏好与当前驻网状态 */
static int qmi_query_single_sub_cell_status(uint32_t subs_id) {
    int fd = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (fd < 0) {
        fprintf(stderr, "socket(AF_QIPCRTR) failed: %s\n", strerror(errno));
        return -1;
    }
    struct timeval tv = { .tv_sec = 4, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    uint32_t node = 0, port = 0;
    if (qrtr_lookup_service(fd, QMI_NAS_SERVICE_ID, &node, &port) != 0) {
        fprintf(stderr, "QRTR service QMI_NAS (0x%x) not online\n", QMI_NAS_SERVICE_ID);
        close(fd);
        return -1;
    }

    struct sockaddr_qrtr_uapi dst;
    memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = node;
    dst.sq_port = port;

    char bind_status[32] = "ERR_SEND";
    int bind_ok = 0;
    uint8_t req[32];
    uint8_t rsp[2048];
    size_t rsp_len = 0;

    int blen = qmi_build_nas_bind_subs_req(1, subs_id, 0, req, sizeof(req));
    if (blen > 0 && qmi_send_and_recv_msg(fd, &dst, req, (size_t)blen, 1,
                                            QMI_NAS_MSG_BIND_SUBS, rsp, sizeof(rsp), &rsp_len) == 0) {
        uint32_t bres = 0xFFFFU, berr = 0xFFFFU;
        if (qmi_parse_nas_result_tlv(rsp, rsp_len, QMI_NAS_MSG_BIND_SUBS, &bres, &berr) == 0) {
            if (bres == 0 && berr == 0) {
                snprintf(bind_status, sizeof(bind_status), "OK_4B");
                bind_ok = 1;
            } else {
                /* 4 字节 TLV 被基带拒绝时尝试 1 字节订阅枚举兼容探针 */
                int blen1 = qmi_build_nas_bind_subs_req(2, subs_id, 1, req, sizeof(req));
                if (blen1 > 0 && qmi_send_and_recv_msg(fd, &dst, req, (size_t)blen1, 2,
                                                         QMI_NAS_MSG_BIND_SUBS, rsp, sizeof(rsp), &rsp_len) == 0) {
                    uint32_t bres1 = 0xFFFFU, berr1 = 0xFFFFU;
                    if (qmi_parse_nas_result_tlv(rsp, rsp_len, QMI_NAS_MSG_BIND_SUBS, &bres1, &berr1) == 0 &&
                        bres1 == 0 && berr1 == 0) {
                        snprintf(bind_status, sizeof(bind_status), "OK_1B");
                        bind_ok = 1;
                    } else {
                        snprintf(bind_status, sizeof(bind_status), "ERR_%u", berr1);
                    }
                } else {
                    snprintf(bind_status, sizeof(bind_status), "ERR_%u", berr);
                }
            }
        } else {
            snprintf(bind_status, sizeof(bind_status), "ERR_PARSE");
        }
    }

    /* 非主卡绑定订阅失败时立即报错退出，避免静默回落读取主卡数据导致串槽误判 */
    if (subs_id != QMI_SUBS_PRIMARY && !bind_ok) {
        fprintf(stderr, "CELL_NAS sub=%u BIND_SUBSCRIPTION failed (%s)\n", subs_id, bind_status);
        close(fd);
        return -1;
    }

    struct qmi_msg_hdr h_pref = { .flags = QMI_HDR_FLAG_REQ, .txn_id = 3, .msg_id = QMI_NAS_MSG_GET_SYS_SEL_PREF, .msg_len = 0 };
    uint16_t mode_pref = 0;
    int net_sel = -1;
    if (qmi_send_and_recv_msg(fd, &dst, (const uint8_t *)&h_pref, sizeof(h_pref), 3,
                              QMI_NAS_MSG_GET_SYS_SEL_PREF, rsp, sizeof(rsp), &rsp_len) != 0 ||
        qmi_parse_nas_sys_sel_pref_resp(rsp, rsp_len, &mode_pref, &net_sel) != 0) {
        fprintf(stderr, "CELL_NAS sub=%u GET_SYS_SEL_PREF failed (bind=%s)\n", subs_id, bind_status);
        close(fd);
        return -1;
    }

    struct qmi_msg_hdr h_ss = { .flags = QMI_HDR_FLAG_REQ, .txn_id = 4, .msg_id = QMI_NAS_MSG_GET_SERVING_SYSTEM, .msg_len = 0 };
    int reg_state = -1;
    int roam = -1;
    char plmn[32] = "-";
    if (qmi_send_and_recv_msg(fd, &dst, (const uint8_t *)&h_ss, sizeof(h_ss), 4,
                              QMI_NAS_MSG_GET_SERVING_SYSTEM, rsp, sizeof(rsp), &rsp_len) != 0 ||
        qmi_parse_nas_serving_system_resp(rsp, rsp_len, &reg_state, &roam, plmn, sizeof(plmn)) != 0) {
        fprintf(stderr, "CELL_NAS sub=%u GET_SERVING_SYSTEM failed (bind=%s)\n", subs_id, bind_status);
        close(fd);
        return -1;
    }

    char mode_str[128];
    format_nas_mode_pref(mode_pref, mode_str, sizeof(mode_str));
    printf("CELL_NAS sub=%u node=%u port=%u bind=%s mode_pref=0x%04x(%s) net_sel=%d reg_state=%d(%s) roam=%d plmn=%s\n",
           subs_id, node, port, bind_status, mode_pref, mode_str, net_sel,
           reg_state, nas_reg_state_desc(reg_state, roam), roam, plmn);
    close(fd);
    return 0;
}

/* 按槽位执行只读 QMI_NAS 核验 */
static int qmi_query_cell_status(refresh_slot_opt_t slot_opt) {
    if (slot_opt == REFRESH_SLOT_0) {
        return qmi_query_single_sub_cell_status(QMI_SUBS_PRIMARY);
    }
    if (slot_opt == REFRESH_SLOT_1) {
        return qmi_query_single_sub_cell_status(QMI_SUBS_SECONDARY);
    }
    int rc0 = qmi_query_single_sub_cell_status(QMI_SUBS_PRIMARY);
    int rc1 = qmi_query_single_sub_cell_status(QMI_SUBS_SECONDARY);
    return (rc0 == 0 && rc1 == 0) ? 0 : -1;
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

    /* 用例 6：QMI VS 订阅绑定与热刷新请求编码与响应解析校验 */
    uint8_t breq0[32], breq1[32];
    int blen0 = qmi_build_bind_subs_req(3, QMI_SUBS_PRIMARY, breq0, sizeof(breq0));
    int blen1 = qmi_build_bind_subs_req(4, QMI_SUBS_SECONDARY, breq1, sizeof(breq1));
    const uint8_t expected_breq0[14] = {
        0x00, 0x03, 0x00, 0x21, 0x00, 0x07, 0x00,
        0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    const uint8_t expected_breq1[14] = {
        0x00, 0x04, 0x00, 0x21, 0x00, 0x07, 0x00,
        0x01, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00
    };
    if (blen0 != 14 || memcmp(breq0, expected_breq0, 14) != 0 ||
        blen1 != 14 || memcmp(breq1, expected_breq1, 14) != 0) {
        fprintf(stderr, "FAIL T6 qmi bind_subs request encode mismatch\n");
        return 1;
    }

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

    /* 用例 8：多后端解析、探测规则与传输虚表空保护校验 */
    diag_backend_t bk = DIAG_BACKEND_AUTO;
    if (parse_diag_backend("auto", &bk) != 0 || bk != DIAG_BACKEND_AUTO ||
        parse_diag_backend("diagchar", &bk) != 0 || bk != DIAG_BACKEND_DIAGCHAR ||
        parse_diag_backend("diag-router", &bk) != 0 || bk != DIAG_BACKEND_DIAG_ROUTER ||
        parse_diag_backend("diag_router", &bk) != 0 || bk != DIAG_BACKEND_DIAG_ROUTER ||
        parse_diag_backend("libdiag", &bk) != 0 || bk != DIAG_BACKEND_LIBDIAG ||
        parse_diag_backend("none", &bk) != 0 || bk != DIAG_BACKEND_NONE) {
        fprintf(stderr, "FAIL T8 parse valid backend\n"); return 1;
    }
    if (parse_diag_backend(NULL, &bk) == 0 || parse_diag_backend("", &bk) == 0 ||
        parse_diag_backend("unknown_drv", &bk) == 0) {
        fprintf(stderr, "FAIL T8 reject invalid backend\n"); return 1;
    }
    if (resolve_diag_backend(DIAG_BACKEND_NONE) != NULL ||
        resolve_diag_backend(DIAG_BACKEND_DIAG_ROUTER) != (DIAG_ROUTER_OPS.detect() ? &DIAG_ROUTER_OPS : NULL) ||
        resolve_diag_backend(DIAG_BACKEND_LIBDIAG) != NULL ||
        LIBDIAG_OPS.detect() != 0) {
        fprintf(stderr, "FAIL T8 resolve guard\n"); return 1;
    }
    diag_ctx_t empty_ctx;
    memset(&empty_ctx, 0, sizeof(empty_ctx));
    empty_ctx.fd = -1;
    uint8_t dummy_cmd = DIAG_EXT_BUILD_ID;
    uint8_t dummy_rsp[16];
    if (diag_transact(&empty_ctx, &dummy_cmd, 1, dummy_rsp, sizeof(dummy_rsp), 10) != -1 ||
        diag_transact(NULL, &dummy_cmd, 1, dummy_rsp, sizeof(dummy_rsp), 10) != -1) {
        fprintf(stderr, "FAIL T8 null ops transact guard\n"); return 1;
    }
    diag_close(&empty_ctx);
    diag_close(NULL);

    /* 用例 9：GKI diag-router 协议封包/解包（32B switch_logging、无 token 12B 内置头 vs 16B 外挂头）校验 */
    uint8_t sw_local[32], sw_remote[32];
    diag_router_build_switch_logging_req(CALLBACK_MODE, 0, 12345, sw_local);
    uint32_t sw_dt = 0, sw_reqm = 0, sw_pmask = 0, sw_pdv = 0;
    int32_t sw_id = 0, sw_pid = 0;
    memcpy(&sw_dt, sw_local, 4);
    memcpy(&sw_reqm, sw_local + 4, 4);
    memcpy(&sw_pmask, sw_local + 8, 4);
    memcpy(&sw_id, sw_local + 20, 4);
    memcpy(&sw_pdv, sw_local + 24, 4);
    memcpy(&sw_pid, sw_local + 28, 4);
    if (sw_dt != DIAG_IOCTL_SWITCH_LOGGING || sw_reqm != CALLBACK_MODE ||
        sw_pmask != 0xFFFU || sw_id != -22 || sw_pdv != 1u || sw_pid != 12345) {
        fprintf(stderr, "FAIL T9 switch_logging local encode\n"); return 1;
    }
    diag_router_build_switch_logging_req(CALLBACK_MODE, 1, 0, sw_remote);
    memcpy(&sw_reqm, sw_remote + 4, 4);
    memcpy(&sw_pdv, sw_remote + 24, 4);
    memcpy(&sw_pid, sw_remote + 28, 4);
    if (sw_reqm != CALLBACK_MODE || sw_pdv != 3u || sw_pid != 1) {
        fprintf(stderr, "FAIL T9 switch_logging remote & pid guard\n"); return 1;
    }

    uint8_t tx_buf[64];
    int tx_loc_len = diag_router_build_tx_pkt(0, 0, &dummy_cmd, 1, tx_buf, (int)sizeof(tx_buf));
    if (tx_loc_len != 8 || tx_buf[0] != USER_SPACE_DATA_TYPE || tx_buf[4] != DIAG_EXT_BUILD_ID || tx_buf[7] != 0x7E) {
        fprintf(stderr, "FAIL T9 local tx must omit token\n"); return 1;
    }
    int tx_rem_len = diag_router_build_tx_pkt(1, -1, &dummy_cmd, 1, tx_buf, (int)sizeof(tx_buf));
    int32_t tx_tok = 0;
    memcpy(&tx_tok, tx_buf + 4, 4);
    if (tx_rem_len != 12 || tx_buf[0] != USER_SPACE_DATA_TYPE || tx_tok != -1 || tx_buf[8] != DIAG_EXT_BUILD_ID) {
        fprintf(stderr, "FAIL T9 remote tx token mismatch\n"); return 1;
    }

    /* 构造 EFS2 STAT 请求及模拟响应并验证 12B 内置头 vs 16B 外挂头与广播过滤 */
    const uint8_t stat_cmd[4] = { DIAG_SUBSYS_CMD, DIAG_SUBSYS_FS, EFS2_DIAG_STAT, 0x00 };
    const uint8_t stat_raw_rsp[8] = { DIAG_SUBSYS_CMD, DIAG_SUBSYS_FS, EFS2_DIAG_STAT, 0x00, 0x00, 0x00, 0x00, 0x00 };
    uint8_t stat_hdlc[64];
    int stat_hdlc_len = build_hdlc(stat_raw_rsp, (int)sizeof(stat_raw_rsp), stat_hdlc, (int)sizeof(stat_hdlc));
    if (stat_hdlc_len <= 0) { fprintf(stderr, "FAIL T9 build hdlc\n"); return 1; }

    uint8_t acc_test[256];
    int acc_len = 0;
    uint8_t rsp_parsed[64];
    uint8_t bcast[16];
    memset(bcast, 0, sizeof(bcast));
    uint32_t btype = 0x00320000U;
    memcpy(bcast, &btype, 4);
    if (diag_router_parse_rx_pkt(0, 0, bcast, (int)sizeof(bcast), stat_cmd, 4,
                                 acc_test, &acc_len, (int)sizeof(acc_test), rsp_parsed, (int)sizeof(rsp_parsed)) != 0) {
        fprintf(stderr, "FAIL T9 ignore non-0x20 packet\n"); return 1;
    }

    uint8_t rx_local[128];
    uint32_t u_dt = USER_SPACE_DATA_TYPE, u_cnt = 1, u_plen = (uint32_t)stat_hdlc_len;
    memcpy(rx_local, &u_dt, 4);
    memcpy(rx_local + 4, &u_cnt, 4);
    memcpy(rx_local + 8, &u_plen, 4);
    memcpy(rx_local + 12, stat_hdlc, (size_t)stat_hdlc_len);
    acc_len = 0;
    int got_loc = diag_router_parse_rx_pkt(0, 0, rx_local, 12 + stat_hdlc_len, stat_cmd, 4,
                                           acc_test, &acc_len, (int)sizeof(acc_test), rsp_parsed, (int)sizeof(rsp_parsed));
    if (got_loc != (int)sizeof(stat_raw_rsp) || memcmp(rsp_parsed, stat_raw_rsp, sizeof(stat_raw_rsp)) != 0) {
        fprintf(stderr, "FAIL T9 local 12B rx parse\n"); return 1;
    }

    uint8_t rx_remote[128];
    int32_t tok_neg1 = -1, tok_neg2 = -2;
    memcpy(rx_remote, &u_dt, 4);
    memcpy(rx_remote + 4, &u_cnt, 4);
    memcpy(rx_remote + 8, &tok_neg2, 4);
    memcpy(rx_remote + 12, &u_plen, 4);
    memcpy(rx_remote + 16, stat_hdlc, (size_t)stat_hdlc_len);
    acc_len = 0;
    if (diag_router_parse_rx_pkt(1, -1, rx_remote, 16 + stat_hdlc_len, stat_cmd, 4,
                                 acc_test, &acc_len, (int)sizeof(acc_test), rsp_parsed, (int)sizeof(rsp_parsed)) != 0) {
        fprintf(stderr, "FAIL T9 remote token filter\n"); return 1;
    }
    memcpy(rx_remote + 8, &tok_neg1, 4);
    acc_len = 0;
    int got_rem = diag_router_parse_rx_pkt(1, -1, rx_remote, 16 + stat_hdlc_len, stat_cmd, 4,
                                           acc_test, &acc_len, (int)sizeof(acc_test), rsp_parsed, (int)sizeof(rsp_parsed));
    if (got_rem != (int)sizeof(stat_raw_rsp) || memcmp(rsp_parsed, stat_raw_rsp, sizeof(stat_raw_rsp)) != 0) {
        fprintf(stderr, "FAIL T9 remote 16B rx parse\n"); return 1;
    }

    /* 用例 10：remote_mask 掩码判定、pd_val 隔离与 diag_router_close 纯 EOF 回归校验 */
    if (diag_resolve_is_remote(DIAG_MODE_AUTO, 0x0000U) != 0 ||
        diag_resolve_is_remote(DIAG_MODE_AUTO, 0x0002U) != 0 ||
        diag_resolve_is_remote(DIAG_MODE_AUTO, 0x8000U) != 0 ||
        diag_resolve_is_remote(DIAG_MODE_AUTO, 0x0001U) != 1 ||
        diag_resolve_is_remote(DIAG_MODE_AUTO, 0x0003U) != 1 ||
        diag_resolve_is_remote(DIAG_MODE_LOCAL, 0x0001U) != 0 ||
        diag_resolve_is_remote(DIAG_MODE_LOCAL, 0x0003U) != 0 ||
        diag_resolve_is_remote(DIAG_MODE_REMOTE, 0x0000U) != 1 ||
        diag_resolve_is_remote(DIAG_MODE_REMOTE, 0x0002U) != 1) {
        fprintf(stderr, "FAIL T10 remote_mask resolution\n"); return 1;
    }
    if (diag_compute_pd_val(0) != 1u || diag_compute_pd_val(1) != 3u) {
        fprintf(stderr, "FAIL T10 pd_val compute\n"); return 1;
    }
    int sv[2] = { -1, -1 };
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
        fprintf(stderr, "FAIL T10 socketpair setup\n"); return 1;
    }
    diag_ctx_t close_ctx;
    memset(&close_ctx, 0, sizeof(close_ctx));
    close_ctx.fd = sv[0];
    close_ctx.remote_mask = 0x0002U;
    close_ctx.is_remote = 0;
    close_ctx.ops = &DIAG_ROUTER_OPS;
    diag_router_close(&close_ctx);
    if (close_ctx.fd != -1) {
        close(sv[1]);
        fprintf(stderr, "FAIL T10 close fd reset\n"); return 1;
    }
    struct pollfd pfd = { .fd = sv[1], .events = POLLIN };
    uint8_t probe_buf[64];
    ssize_t probe_n = -1;
    if (poll(&pfd, 1, 500) <= 0 ||
        (probe_n = read(sv[1], probe_buf, sizeof(probe_buf))) != 0) {
        close(sv[1]);
        fprintf(stderr, "FAIL T10 expected pure EOF on close (got %zd)\n", probe_n); return 1;
    }
    close(sv[1]);

    /* 用例 11：iwlan_s2b_config.xml 静态 ePDG FQDN 定点解锁、空值跳过与白名单校验 */
    const char *s2b_3hk =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><IWLAN_S2B_CONFIG>\r\n"
        "    <GENERIC_VARIANT>\r\n"
        "        <epdg_addr_info>\r\n"
        "            <fqdn>wlan.three.com.hk</fqdn>\r\n"
        "            <static_fqdn_enabled>FALSE</static_fqdn_enabled>\r\n"
        "        </epdg_addr_info>\r\n"
        "        <ikev2_info>\r\n"
        "            <cert_req_enabled>FALSE</cert_req_enabled>\r\n"
        "        </ikev2_info>\r\n"
        "    </GENERIC_VARIANT>\r\n"
        "</IWLAN_S2B_CONFIG>";
    char fqdn_buf[128];
    if (s2b_inspect_static_fqdn((const uint8_t *)s2b_3hk, strlen(s2b_3hk), fqdn_buf, sizeof(fqdn_buf)) != S2B_STATE_NEEDS_PATCH ||
        strcmp(fqdn_buf, "wlan.three.com.hk") != 0) {
        fprintf(stderr, "FAIL T11 inspect 3HK s2b\n"); return 1;
    }
    uint8_t *s2b_out = NULL;
    size_t s2b_out_len = 0;
    if (s2b_apply_static_fqdn_patch((const uint8_t *)s2b_3hk, strlen(s2b_3hk), &s2b_out, &s2b_out_len) != 0 ||
        s2b_out_len != strlen(s2b_3hk) - 1 ||
        !strstr((const char *)s2b_out, "<static_fqdn_enabled>TRUE</static_fqdn_enabled>") ||
        !strstr((const char *)s2b_out, "<cert_req_enabled>FALSE</cert_req_enabled>") ||
        !strstr((const char *)s2b_out, "\r\n")) {
        free(s2b_out);
        fprintf(stderr, "FAIL T11 apply 3HK s2b patch\n"); return 1;
    }
    if (s2b_inspect_static_fqdn(s2b_out, s2b_out_len, fqdn_buf, sizeof(fqdn_buf)) != S2B_STATE_ALREADY_ENABLED) {
        free(s2b_out);
        fprintf(stderr, "FAIL T11 post-patch inspect\n"); return 1;
    }
    uint8_t *s2b_idem = NULL;
    size_t s2b_idem_len = 0;
    if (s2b_apply_static_fqdn_patch(s2b_out, s2b_out_len, &s2b_idem, &s2b_idem_len) != 1 || s2b_idem != NULL) {
        free(s2b_out);
        free(s2b_idem);
        fprintf(stderr, "FAIL T11 idempotent check\n"); return 1;
    }
    free(s2b_out);

    const char *s2b_ct = "<IWLAN_S2B_CONFIG><GENERIC_VARIANT><epdg_addr_info><static_fqdn_enabled>FALSE</static_fqdn_enabled><plmn_list/></epdg_addr_info></GENERIC_VARIANT></IWLAN_S2B_CONFIG>";
    const char *s2b_self = "<IWLAN_S2B_CONFIG><GENERIC_VARIANT><epdg_addr_info><fqdn/><static_fqdn_enabled>FALSE</static_fqdn_enabled></epdg_addr_info></GENERIC_VARIANT></IWLAN_S2B_CONFIG>";
    const char *s2b_blank = "<IWLAN_S2B_CONFIG><GENERIC_VARIANT><epdg_addr_info><fqdn>  \t </fqdn><static_fqdn_enabled>FALSE</static_fqdn_enabled></epdg_addr_info></GENERIC_VARIANT></IWLAN_S2B_CONFIG>";
    if (s2b_inspect_static_fqdn((const uint8_t *)s2b_ct, strlen(s2b_ct), fqdn_buf, sizeof(fqdn_buf)) != S2B_STATE_NO_STATIC_FQDN ||
        s2b_inspect_static_fqdn((const uint8_t *)s2b_self, strlen(s2b_self), fqdn_buf, sizeof(fqdn_buf)) != S2B_STATE_NO_STATIC_FQDN ||
        s2b_inspect_static_fqdn((const uint8_t *)s2b_blank, strlen(s2b_blank), fqdn_buf, sizeof(fqdn_buf)) != S2B_STATE_NO_STATIC_FQDN ||
        s2b_apply_static_fqdn_patch((const uint8_t *)s2b_ct, strlen(s2b_ct), &s2b_idem, &s2b_idem_len) != 1) {
        fprintf(stderr, "FAIL T11 skip empty/missing fqdn\n"); return 1;
    }
    if (!is_allowed_write_path(EFS_S2B_PATH_SLOT0) ||
        !is_allowed_write_path(EFS_S2B_PATH_SLOT1) ||
        is_allowed_write_path("/data/iwlan_s2b_config_Subscription01.xml")) {
        fprintf(stderr, "FAIL T11 s2b whitelist guard\n"); return 1;
    }

    const char *s2b_vdf_comment =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><IWLAN_S2B_CONFIG>\r\n"
        "    <GENERIC_VARIANT>\r\n"
        "        <epdg_addr_info>\r\n"
        "            <!--\r\n"
        "            <static_fqdn_enabled>FALSE</static_fqdn_enabled>\r\n"
        "            -->\r\n"
        "            <static_fqdn_enabled>TRUE</static_fqdn_enabled>\r\n"
        "            <fqdn>epdg.epc.mnc015.mcc234.pub.3gppnetwork.org</fqdn>\r\n"
        "        </epdg_addr_info>\r\n"
        "    </GENERIC_VARIANT>\r\n"
        "</IWLAN_S2B_CONFIG>";
    const char *s2b_commented_fqdn =
        "<IWLAN_S2B_CONFIG><GENERIC_VARIANT><epdg_addr_info>"
        "<!-- <fqdn>fake.example.com</fqdn> -->"
        "<static_fqdn_enabled>FALSE</static_fqdn_enabled>"
        "</epdg_addr_info></GENERIC_VARIANT></IWLAN_S2B_CONFIG>";
    if (s2b_inspect_static_fqdn((const uint8_t *)s2b_vdf_comment, strlen(s2b_vdf_comment), fqdn_buf, sizeof(fqdn_buf)) != S2B_STATE_ALREADY_ENABLED ||
        strcmp(fqdn_buf, "epdg.epc.mnc015.mcc234.pub.3gppnetwork.org") != 0 ||
        s2b_apply_static_fqdn_patch((const uint8_t *)s2b_vdf_comment, strlen(s2b_vdf_comment), &s2b_idem, &s2b_idem_len) != 1 ||
        s2b_inspect_static_fqdn((const uint8_t *)s2b_commented_fqdn, strlen(s2b_commented_fqdn), fqdn_buf, sizeof(fqdn_buf)) != S2B_STATE_NO_STATIC_FQDN ||
        s2b_apply_static_fqdn_patch((const uint8_t *)s2b_commented_fqdn, strlen(s2b_commented_fqdn), &s2b_idem, &s2b_idem_len) != 1) {
        fprintf(stderr, "FAIL T11 xml comment ignore guard\n"); return 1;
    }

    /* 用例 12：QMI_NAS 只读报文构造与解析（BIND_SUBSCRIPTION / GET_SYS_SEL_PREF / GET_SERVING_SYSTEM） */
    uint8_t nas_bind4[16], nas_bind1[16];
    if (qmi_build_nas_bind_subs_req(7, 1, 0, nas_bind4, sizeof(nas_bind4)) != 14 ||
        nas_bind4[7] != 0x01 || nas_bind4[8] != 0x04 || nas_bind4[10] != 0x01 ||
        qmi_build_nas_bind_subs_req(8, 1, 1, nas_bind1, sizeof(nas_bind1)) != 11 ||
        nas_bind1[7] != 0x01 || nas_bind1[8] != 0x01 || nas_bind1[10] != 0x01) {
        fprintf(stderr, "FAIL T12 nas bind req build\n"); return 1;
    }
    const uint8_t nas_pref_sample[] = {
        0x02, 0x03, 0x00, 0x34, 0x00, 0x10, 0x00,
        0x02, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x11, 0x02, 0x00, 0x58, 0x00,
        0x16, 0x01, 0x00, 0x00
    };
    uint16_t mode_pref_t = 0;
    int net_sel_t = -1;
    char mode_str_t[64];
    if (qmi_parse_nas_sys_sel_pref_resp(nas_pref_sample, sizeof(nas_pref_sample), &mode_pref_t, &net_sel_t) != 0 ||
        mode_pref_t != 0x0058U || net_sel_t != 0) {
        fprintf(stderr, "FAIL T12 nas sys_sel_pref parse\n"); return 1;
    }
    format_nas_mode_pref(mode_pref_t, mode_str_t, sizeof(mode_str_t));
    if (strcmp(mode_str_t, "UMTS|LTE|NR5G") != 0) {
        fprintf(stderr, "FAIL T12 nas mode_pref format: %s\n", mode_str_t); return 1;
    }
    const uint8_t nas_ss_roaming[] = {
        0x02, 0x04, 0x00, 0x24, 0x00, 0x1c, 0x00,
        0x02, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x06, 0x00, 0x01, 0x01, 0x01, 0x02, 0x01, 0x08,
        0x10, 0x01, 0x00, 0x00,
        0x12, 0x05, 0x00, 0xcc, 0x01, 0x01, 0x00, 0x00
    };
    int reg_st_t = -1;
    int roam_t = -1;
    char plmn_t[32];
    if (qmi_parse_nas_serving_system_resp(nas_ss_roaming, sizeof(nas_ss_roaming), &reg_st_t, &roam_t, plmn_t, sizeof(plmn_t)) != 0 ||
        reg_st_t != 1 || roam_t != 0 ||
        strcmp(nas_reg_state_desc(reg_st_t, roam_t), "REGISTERED_ROAM") != 0 ||
        strcmp(plmn_t, "460/01") != 0) {
        fprintf(stderr, "FAIL T12 nas serving_system roaming: st=%d roam=%d plmn=%s\n", reg_st_t, roam_t, plmn_t); return 1;
    }
    uint8_t nas_ss_home[sizeof(nas_ss_roaming)];
    memcpy(nas_ss_home, nas_ss_roaming, sizeof(nas_ss_roaming));
    nas_ss_home[26] = 0x01; /* TLV 0x10 roaming_indicator = 1 (HOME) */
    if (qmi_parse_nas_serving_system_resp(nas_ss_home, sizeof(nas_ss_home), &reg_st_t, &roam_t, plmn_t, sizeof(plmn_t)) != 0 ||
        reg_st_t != 1 || roam_t != 1 ||
        strcmp(nas_reg_state_desc(reg_st_t, roam_t), "REGISTERED_HOME") != 0) {
        fprintf(stderr, "FAIL T12 nas serving_system home: st=%d roam=%d\n", reg_st_t, roam_t); return 1;
    }
    const uint8_t nas_ss_notreg[] = {
        0x02, 0x05, 0x00, 0x24, 0x00, 0x0d, 0x00,
        0x02, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x03, 0x00, 0x00, 0x00, 0x00
    };
    if (qmi_parse_nas_serving_system_resp(nas_ss_notreg, sizeof(nas_ss_notreg), &reg_st_t, &roam_t, plmn_t, sizeof(plmn_t)) != 0 ||
        reg_st_t != 0 || roam_t != -1 ||
        strcmp(nas_reg_state_desc(reg_st_t, roam_t), "NOT_REG") != 0 ||
        strcmp(plmn_t, "-") != 0) {
        fprintf(stderr, "FAIL T12 nas serving_system not_reg: st=%d roam=%d plmn=%s\n", reg_st_t, roam_t, plmn_t); return 1;
    }

    /* 用例 13：diagchar 内核 ABI 结构体大小/偏移、切换参数编码与 backend.conf 解析校验 */
    if (sizeof(struct diagchar_logging_mode_param_t) != 24 ||
        offsetof(struct diagchar_logging_mode_param_t, mode_param) != 12 ||
        offsetof(struct diagchar_logging_mode_param_t, diag_id) != 13 ||
        offsetof(struct diagchar_logging_mode_param_t, pd_val) != 14 ||
        offsetof(struct diagchar_logging_mode_param_t, peripheral) != 16 ||
        offsetof(struct diagchar_logging_mode_param_t, device_mask) != 20 ||
        sizeof(struct diag_query_pid_t) != 16) {
        fprintf(stderr, "FAIL T13 diagchar kernel ABI layout mismatch\n"); return 1;
    }
    struct diagchar_logging_mode_param_t kmode_loc, kmode_rem;
    diagchar_build_switch_logging_param(MEMORY_DEVICE_MODE, DIAG_CON_MPSS, 0, &kmode_loc);
    if (kmode_loc.req_mode != MEMORY_DEVICE_MODE ||
        kmode_loc.peripheral_mask != DIAG_CON_MPSS ||
        kmode_loc.pd_mask != 0 || kmode_loc.mode_param != 1 ||
        kmode_loc.diag_id != 0 || kmode_loc.pd_val != 0 ||
        kmode_loc.peripheral != -22 || kmode_loc.device_mask != 1) {
        fprintf(stderr, "FAIL T13 diagchar local switch_logging param\n"); return 1;
    }
    diagchar_build_switch_logging_param(CALLBACK_MODE, 0xFFFU, 1, &kmode_rem);
    if (kmode_rem.req_mode != CALLBACK_MODE ||
        kmode_rem.peripheral_mask != 0xFFFU ||
        kmode_rem.device_mask != 3) {
        fprintf(stderr, "FAIL T13 diagchar remote switch_logging param\n"); return 1;
    }

    char conf_tmp[] = "/tmp/vowifix_bconf_XXXXXX";
    int conf_fd = mkstemp(conf_tmp);
    if (conf_fd < 0) { fprintf(stderr, "FAIL T13 mkstemp\n"); return 1; }
    const char *conf_none = "# override for incompatible kernel\n  \nBACKEND=none\n";
    if (write(conf_fd, conf_none, strlen(conf_none)) != (ssize_t)strlen(conf_none)) {
        close(conf_fd); unlink(conf_tmp);
        fprintf(stderr, "FAIL T13 write conf\n"); return 1;
    }
    close(conf_fd);
    diag_backend_t conf_bk = DIAG_BACKEND_AUTO;
    if (read_backend_conf_override(conf_tmp, &conf_bk) != 1 || conf_bk != DIAG_BACKEND_NONE) {
        unlink(conf_tmp);
        fprintf(stderr, "FAIL T13 parse BACKEND=none conf\n"); return 1;
    }
    unlink(conf_tmp);
    if (read_backend_conf_override("/tmp/vowifix_nonexistent_conf_file", &conf_bk) != 0) {
        fprintf(stderr, "FAIL T13 missing conf guard\n"); return 1;
    }

    /* 用例 14：SIGALRM 硬超时打断阻塞 read()（覆盖管道 read 与 diagchar_transact 端到端超时） */
    int block_pipe[2] = { -1, -1 };
    if (pipe(block_pipe) != 0) { fprintf(stderr, "FAIL T14 pipe\n"); return 1; }
    set_cmd_timeout_sec(0);
    long t_start = diag_now_ms();
    uint8_t sink[32];
    ssize_t pr_n = diag_read_with_alarm(block_pipe[0], sink, sizeof(sink), 120);
    long t_cost = diag_now_ms() - t_start;
    close(block_pipe[0]);
    close(block_pipe[1]);
    if (pr_n != -2 || !g_io_timed_out || t_cost > 1500) {
        fprintf(stderr, "FAIL T14 pipe read alarm (n=%zd timed=%d cost=%ldms)\n",
                pr_n, (int)g_io_timed_out, t_cost);
        return 1;
    }

    int sv_block[2] = { -1, -1 };
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv_block) != 0) {
        fprintf(stderr, "FAIL T14 socketpair\n"); return 1;
    }
    set_cmd_timeout_sec(1);
    diag_ctx_t block_ctx;
    memset(&block_ctx, 0, sizeof(block_ctx));
    block_ctx.fd = sv_block[0];
    block_ctx.is_remote = 0;
    block_ctx.token = 0;
    block_ctx.ops = &DIAGCHAR_OPS;
    uint8_t probe_cmd = DIAG_EXT_BUILD_ID;
    uint8_t probe_rsp[64];
    long tx_start = diag_now_ms();
    int tx_rc = diagchar_transact(&block_ctx, &probe_cmd, 1, probe_rsp, sizeof(probe_rsp), 150);
    long tx_cost = diag_now_ms() - tx_start;
    close(sv_block[0]);
    close(sv_block[1]);
    if (tx_rc != -2 || !g_saw_diag_timeout || tx_cost > 1500) {
        fprintf(stderr, "FAIL T14 diagchar_transact timeout (rc=%d cost=%ldms)\n", tx_rc, tx_cost);
        return 1;
    }
    set_cmd_timeout_sec(0);

    /* 用例 15：纯 EOF 不误判超时、单包超时不提前熔断全局预算、以及 acquire_diag_lock 限时防死锁校验 */
    int eof_pipe[2] = { -1, -1 };
    if (pipe(eof_pipe) != 0) { fprintf(stderr, "FAIL T15 eof pipe\n"); return 1; }
    close(eof_pipe[1]);
    ssize_t eof_n = diag_read_with_alarm(eof_pipe[0], sink, sizeof(sink), 200);
    close(eof_pipe[0]);
    if (eof_n != 0) {
        fprintf(stderr, "FAIL T15 expected pure EOF 0, got %zd\n", eof_n);
        return 1;
    }

    int sv_budget[2] = { -1, -1 };
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv_budget) != 0) {
        fprintf(stderr, "FAIL T15 budget socketpair\n"); return 1;
    }
    set_cmd_timeout_sec(10);
    block_ctx.fd = sv_budget[0];
    int b_rc = diagchar_transact(&block_ctx, &probe_cmd, 1, probe_rsp, sizeof(probe_rsp), 80);
    close(sv_budget[0]);
    close(sv_budget[1]);
    if (b_rc != -2 || !g_saw_diag_timeout || g_cmd_timed_out != 0) {
        fprintf(stderr, "FAIL T15 single packet timeout must not set g_cmd_timed_out (rc=%d cmd_to=%d)\n",
                b_rc, (int)g_cmd_timed_out);
        return 1;
    }
    set_cmd_timeout_sec(0);

    char lck_tmp[] = "/tmp/vowifix_lck_XXXXXX";
    int lck_holder = mkstemp(lck_tmp);
    if (lck_holder < 0) { fprintf(stderr, "FAIL T15 mkstemp lock\n"); return 1; }
    if (flock(lck_holder, LOCK_EX | LOCK_NB) != 0) {
        close(lck_holder); unlink(lck_tmp);
        fprintf(stderr, "FAIL T15 holder flock\n"); return 1;
    }
    int lck_busy = 0;
    int lck_second = acquire_diag_lock(lck_tmp, 120, &lck_busy);
    close(lck_holder);
    unlink(lck_tmp);
    if (lck_second != -1 || lck_busy != 1) {
        if (lck_second >= 0) close(lck_second);
        fprintf(stderr, "FAIL T15 acquire_diag_lock expected busy=-1/1, got fd=%d busy=%d\n",
                lck_second, lck_busy);
        return 1;
    }

    printf("SELF-TEST PASSED: all 15 INI/SE/S2B merge, diagchar ABI/SIGALRM/lock, diag-router & QMI VS/NAS cases OK\n");
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

/* 对单槽 overideconfig 执行备份、合并与写回校验：
 * 返回 0 表示成功（已补丁或无需更改），1 表示目标不存在（跳过该槽 SE 检查），-1 表示读写失败。 */
static int patch_one_ini_target(const diag_ctx_t *ctx, const slot_target_t *slot,
                                const char *backup_dir, const ini_patch_entry_t *entries,
                                size_t nentries, int *changed) {
    uint8_t *orig = NULL;
    size_t orig_len = 0;
    int rr = efs_read_all_mem(ctx, slot->efs_path, &orig, &orig_len);
    if (rr == -2) {
        printf("%s (%s): SKIP (not found)\n", slot->slot_name, slot->efs_path);
        return 1;
    }
    if (rr != 0) {
        fprintf(stderr, "%s (%s): READ_ERROR\n", slot->slot_name, slot->efs_path);
        return -1;
    }

    if (ini_check_patched(orig, orig_len, entries, nentries)) {
        printf("%s (%s): ALREADY_PATCHED (size=%zu)\n",
               slot->slot_name, slot->efs_path, orig_len);
        free(orig);
        return 0;
    }

    if (backup_original_if_needed(backup_dir, slot->slot_name, orig, orig_len) != 0) {
        free(orig);
        return -1;
    }

    uint8_t *merged = NULL;
    size_t merged_len = 0;
    if (ini_merge(orig, orig_len, entries, nentries, &merged, &merged_len) != 0) {
        fprintf(stderr, "%s: ini_merge failed\n", slot->slot_name);
        free(orig);
        return -1;
    }
    free(orig);

    if (efs_write_and_verify(ctx, slot->efs_path, merged, merged_len) != 0) {
        free(merged);
        return -1;
    }
    printf("PATCHED %s -> %zu bytes\n", slot->efs_path, merged_len);
    free(merged);
    (*changed)++;
    return 0;
}

/* 对单槽 qp_ims_service_enablement_config 执行漫游与优选字节解锁：返回 0 成功/跳过，-1 失败。 */
static int patch_one_se_target(const diag_ctx_t *ctx, const slot_target_t *slot,
                               const char *backup_dir, int *changed) {
    uint8_t *se_orig = NULL;
    size_t se_len = 0;
    int sr = efs_read_all_mem(ctx, slot->se_path, &se_orig, &se_len);
    if (sr == -2) {
        printf("%s (%s): SKIP (not found)\n", slot->se_backup_prefix, slot->se_path);
        return 0;
    }
    if (sr != 0) {
        fprintf(stderr, "%s (%s): READ_ERROR\n", slot->se_backup_prefix, slot->se_path);
        return -1;
    }
    if (se_len < 16) {
        printf("%s (%s): SKIP (len=%zu < 16)\n",
               slot->se_backup_prefix, slot->se_path, se_len);
        free(se_orig);
        return 0;
    }

    if (!se_needs_patch(se_orig, se_len)) {
        printf("%s (%s): ALREADY_PATCHED (wfc=0x%02x pref=0x%02x roam=0x%02x)\n",
               slot->se_backup_prefix, slot->se_path,
               se_orig[8], se_orig[9], se_orig[13]);
        free(se_orig);
        return 0;
    }

    if (backup_original_if_needed(backup_dir, slot->se_backup_prefix, se_orig, se_len) != 0) {
        free(se_orig);
        return -1;
    }

    uint8_t *se_mod = (uint8_t *)malloc(se_len);
    if (!se_mod) {
        free(se_orig);
        return -1;
    }
    memcpy(se_mod, se_orig, se_len);
    free(se_orig);

    if (se_apply_patch(se_mod, se_len) < 0) {
        free(se_mod);
        return -1;
    }
    if (efs_write_and_verify(ctx, slot->se_path, se_mod, se_len) != 0) {
        free(se_mod);
        return -1;
    }
    printf("PATCHED %s -> %zu bytes (wfc=0x%02x pref=0x%02x roam=0x%02x)\n",
           slot->se_path, se_len, se_mod[8], se_mod[9], se_mod[13]);
    free(se_mod);
    (*changed)++;
    return 0;
}

/* 对单槽 iwlan_s2b_config.xml 按需启用静态 ePDG FQDN（仅含非空 <fqdn> 时修改）：返回 0 成功/跳过，-1 失败。 */
static int patch_one_s2b_target(const diag_ctx_t *ctx, const slot_target_t *slot,
                                const char *backup_dir, int *changed) {
    uint8_t *s2b_orig = NULL;
    size_t s2b_len = 0;
    int sr = efs_read_all_mem(ctx, slot->s2b_path, &s2b_orig, &s2b_len);
    if (sr == -2) {
        printf("%s (%s): SKIP (not found)\n", slot->s2b_backup_prefix, slot->s2b_path);
        return 0;
    }
    if (sr != 0) {
        fprintf(stderr, "%s (%s): READ_ERROR\n", slot->s2b_backup_prefix, slot->s2b_path);
        return -1;
    }

    char fqdn[128];
    s2b_fqdn_state_t st = s2b_inspect_static_fqdn(s2b_orig, s2b_len, fqdn, sizeof(fqdn));
    if (st == S2B_STATE_NO_STATIC_FQDN) {
        printf("%s (%s): SKIP (no static fqdn)\n", slot->s2b_backup_prefix, slot->s2b_path);
        free(s2b_orig);
        return 0;
    }
    if (st == S2B_STATE_ALREADY_ENABLED) {
        printf("%s (%s): ALREADY_PATCHED (fqdn=%s static_fqdn=TRUE)\n",
               slot->s2b_backup_prefix, slot->s2b_path, fqdn);
        free(s2b_orig);
        return 0;
    }

    if (backup_original_if_needed(backup_dir, slot->s2b_backup_prefix, s2b_orig, s2b_len) != 0) {
        free(s2b_orig);
        return -1;
    }

    uint8_t *s2b_new = NULL;
    size_t s2b_new_len = 0;
    if (s2b_apply_static_fqdn_patch(s2b_orig, s2b_len, &s2b_new, &s2b_new_len) != 0) {
        free(s2b_orig);
        return -1;
    }
    free(s2b_orig);

    if (efs_write_and_verify(ctx, slot->s2b_path, s2b_new, s2b_new_len) != 0) {
        free(s2b_new);
        return -1;
    }
    printf("PATCHED %s -> %zu bytes (fqdn=%s static_fqdn=TRUE)\n",
           slot->s2b_path, s2b_new_len, fqdn);
    free(s2b_new);
    (*changed)++;
    return 0;
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
    int sr = efs_stat(ctx, efs_path, &m, &sz);
    if (sr == -2) {
        printf("%s (%s): SKIP restore (target not found in EFS)\n", bak_prefix, efs_path);
        free(bak);
        return 0;
    }
    if (sr != 0) {
        fprintf(stderr, "%s (%s): STAT_ERROR during restore\n", bak_prefix, efs_path);
        free(bak);
        return -1;
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
    int backend_explicit = 0;
    int timeout_sec = -1;
    diag_mode_opt_t mode_opt = DIAG_MODE_AUTO;
    diag_backend_t backend_opt = DIAG_BACKEND_AUTO;
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
        } else if (strncmp(argv[i], "--backend=", 10) == 0) {
            if (parse_diag_backend(argv[i] + 10, &backend_opt) != 0) {
                fprintf(stderr, "Invalid --backend value: %s\n", argv[i] + 10);
                return 2;
            }
            backend_explicit = 1;
        } else if (strcmp(argv[i], "--backend") == 0) {
            if (i + 1 >= argc || parse_diag_backend(argv[i + 1], &backend_opt) != 0) {
                fprintf(stderr, "Invalid or missing --backend argument\n");
                return 2;
            }
            backend_explicit = 1;
            i++;
        } else if (strncmp(argv[i], "--timeout=", 10) == 0) {
            char *ep = NULL;
            long tv = strtol(argv[i] + 10, &ep, 10);
            if (!ep || ep == argv[i] + 10 || *ep != '\0' || tv < 0 || tv > 600) {
                fprintf(stderr, "Invalid --timeout value: %s\n", argv[i] + 10);
                return 2;
            }
            timeout_sec = (int)tv;
        } else if (strcmp(argv[i], "--timeout") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "Missing --timeout argument\n");
                return 2;
            }
            char *ep = NULL;
            long tv = strtol(argv[i + 1], &ep, 10);
            if (!ep || ep == argv[i + 1] || *ep != '\0' || tv < 0 || tv > 600) {
                fprintf(stderr, "Invalid --timeout value: %s\n", argv[i + 1]);
                return 2;
            }
            timeout_sec = (int)tv;
            i++;
        } else if (strcmp(argv[i], "--backup-dir") == 0 && i + 1 < argc) {
            backup_dir = argv[++i];
        } else if (pos_argc < 8) {
            pos_args[pos_argc++] = argv[i];
        }
    }

    if (pos_argc < 1) {
        fprintf(stderr,
                "Usage: %s [--ils-only] [--no-lock] [--remote|--local] [--timeout <sec>] "
                "[--backend=auto|diagchar|diag-router|libdiag|none] [--backup-dir <dir>] "
                "detect|probe|status [slot0|slot1|all]|patch [slot0|slot1|all]|restore [slot0|slot1|all]|"
                "refresh [slot0|slot1|all] [cfg act]|cell-status [slot0|slot1|all]|stat <efs_path>|read <efs_path> <local_out>|--self-test\n",
                argv[0]);
        return 2;
    }

    if (!backend_explicit) {
        read_backend_conf_override(resolve_backend_conf_path(), &backend_opt);
    }

    const char *cmd = pos_args[0];

    /* detect 为纯无副作用、无锁探测：不抢占 diag.lock 也不切换基带 logging mode */
    if (strcmp(cmd, "detect") == 0) {
        const diag_transport_ops_t *ops = resolve_diag_backend(backend_opt);
        printf("BACKEND=%s\n", ops ? ops->name : "none");
        return 0;
    }

    /* 获取与守护进程和 App 共享的互斥锁（支持限时非阻塞轮询，防止 CLI 直接调用死等） */
    int lock_fd = -1;
    if (!no_lock) {
        int lock_wait_ms = (timeout_sec == 0) ? 0 : ((timeout_sec > 0 ? timeout_sec : 20) * 1000);
        int lock_busy = 0;
        lock_fd = acquire_diag_lock(DEFAULT_LOCK_PATH, lock_wait_ms, &lock_busy);
        if (lock_fd < 0) {
            return lock_busy ? EXIT_CODE_LOCK_BUSY : 1;
        }
    }

    /* refresh 与 cell-status 均走纯内核 QRTR 通路，严禁打开 Diag 通道 */
    if (strcmp(cmd, "cell-status") == 0) {
        refresh_slot_opt_t slot_opt = REFRESH_SLOT_ALL;
        if (pos_argc >= 2 && parse_refresh_slot(pos_args[1], &slot_opt) != 0) {
            fprintf(stderr, "Usage: %s cell-status [slot0|slot1|all]\n", argv[0]);
            if (lock_fd >= 0) close(lock_fd);
            return 2;
        }
        int crc = (qmi_query_cell_status(slot_opt) == 0) ? 0 : 1;
        if (lock_fd >= 0) close(lock_fd);
        return crc;
    }

    /* refresh 走纯内核 QRTR 通路，严禁打开 Diag 通道，避免与刚落盘或重启的基带产生并发冲突 */
    if (strcmp(cmd, "refresh") == 0) {
        refresh_slot_opt_t slot_opt = REFRESH_SLOT_ALL;
        uint32_t cfg = MCFG_TYPE_SW;
        uint32_t act = MCFG_ACTIVATION_REFRESH_ONLY;
        int arg_i = 1;
        if (arg_i < pos_argc && parse_refresh_slot(pos_args[arg_i], &slot_opt) == 0) {
            arg_i++;
        }
        int rem = pos_argc - arg_i;
        if (rem == 2) {
            char *e1 = NULL, *e2 = NULL;
            unsigned long v1 = strtoul(pos_args[arg_i], &e1, 0);
            unsigned long v2 = strtoul(pos_args[arg_i + 1], &e2, 0);
            if (!e1 || e1 == pos_args[arg_i] || *e1 != '\0' ||
                !e2 || e2 == pos_args[arg_i + 1] || *e2 != '\0') {
                fprintf(stderr, "refresh requires valid numeric <cfg> <act>\n");
                if (lock_fd >= 0) close(lock_fd);
                return 2;
            }
            cfg = (uint32_t)v1;
            act = (uint32_t)v2;
        } else if (rem != 0) {
            fprintf(stderr, "Usage: %s refresh [slot0|slot1|all] [cfg act]\n", argv[0]);
            if (lock_fd >= 0) close(lock_fd);
            return 2;
        }
        int rrc = (qmi_trigger_modem_efs_refresh(slot_opt, cfg, act) == 0) ? 0 : 1;
        if (lock_fd >= 0) close(lock_fd);
        return rrc;
    }

    const ini_patch_entry_t *entries = ils_only ? ILS_ONLY_PATCHES : UNIVERSAL_PATCHES;
    size_t nentries = ils_only
        ? (sizeof(ILS_ONLY_PATCHES) / sizeof(ILS_ONLY_PATCHES[0]))
        : (sizeof(UNIVERSAL_PATCHES) / sizeof(UNIVERSAL_PATCHES[0]));

    int is_write_cmd = (strcmp(cmd, "patch") == 0 || strcmp(cmd, "restore") == 0);
    int eff_timeout_sec = (timeout_sec >= 0)
        ? timeout_sec
        : (is_write_cmd ? DEFAULT_WRITE_TIMEOUT_SEC : DEFAULT_READ_TIMEOUT_SEC);
    set_cmd_timeout_sec(eff_timeout_sec);

    diag_ctx_t ctx;
    if (diag_open(&ctx, backend_opt, mode_opt) != 0) {
        if (lock_fd >= 0) close(lock_fd);
        if (g_cmd_timed_out || g_saw_diag_timeout) {
            fprintf(stderr, "DIAG_READ_TIMEOUT: open exceeded timeout limit (%ds)\n", eff_timeout_sec);
            return EXIT_CODE_TIMEOUT;
        }
        return 1;
    }

    int rc = 0;

    if (strcmp(cmd, "probe") == 0) {
        uint8_t rsp[512], ext_id = DIAG_EXT_BUILD_ID;
        int r = diag_transact(&ctx, &ext_id, 1, rsp, sizeof(rsp), 1500);
        if (r == -2 || g_saw_diag_timeout) g_cmd_timed_out = 1;
        printf("backend=%s mode=%s remote_mask=0x%04x ext_build_id_len=%d sw=%s\n",
               ctx.ops ? ctx.ops->name : "unknown",
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
                printf("%s (%s): SKIP_INACTIVE\n", slots[i].s2b_backup_prefix, slots[i].s2b_path);
                continue;
            } else if (rr != 0) {
                printf("%s (%s): READ_ERROR\n", slots[i].slot_name, slots[i].efs_path);
                rc = 1;
                if (g_cmd_timed_out) break;
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
                free(se_buf);
                if (g_cmd_timed_out) break;
                continue;
            } else {
                int se_need = se_needs_patch(se_buf, se_len);
                printf("%s (%s): size=%zu wfc=0x%02x pref=0x%02x roam=0x%02x status=%s\n",
                       slots[i].se_backup_prefix, slots[i].se_path, se_len,
                       se_buf[8], se_buf[9], se_buf[13],
                       se_need ? "UNPATCHED" : "ALREADY_PATCHED");
            }
            free(se_buf);

            /* 活跃卡槽同步检查 iwlan_s2b_config.xml 静态 ePDG FQDN 开关 */
            uint8_t *s2b_buf = NULL;
            size_t s2b_len = 0;
            int s2r = efs_read_all_mem(&ctx, slots[i].s2b_path, &s2b_buf, &s2b_len);
            if (s2r == -2) {
                printf("%s (%s): NOT_FOUND\n", slots[i].s2b_backup_prefix, slots[i].s2b_path);
            } else if (s2r != 0) {
                printf("%s (%s): READ_ERROR\n", slots[i].s2b_backup_prefix, slots[i].s2b_path);
                rc = 1;
                free(s2b_buf);
                if (g_cmd_timed_out) break;
                continue;
            } else {
                char fqdn[128];
                s2b_fqdn_state_t st = s2b_inspect_static_fqdn(s2b_buf, s2b_len, fqdn, sizeof(fqdn));
                if (st == S2B_STATE_NO_STATIC_FQDN) {
                    printf("%s (%s): size=%zu SKIP_NO_FQDN\n",
                           slots[i].s2b_backup_prefix, slots[i].s2b_path, s2b_len);
                } else {
                    printf("%s (%s): size=%zu fqdn=%s static_fqdn=%s status=%s\n",
                           slots[i].s2b_backup_prefix, slots[i].s2b_path, s2b_len, fqdn,
                           (st == S2B_STATE_ALREADY_ENABLED) ? "TRUE" : "FALSE",
                           (st == S2B_STATE_ALREADY_ENABLED) ? "ALREADY_PATCHED" : "UNPATCHED");
                }
            }
            free(s2b_buf);
        }
    } else if (strcmp(cmd, "patch") == 0) {
        const slot_target_t *slots = NULL;
        size_t scnt = 0;
        const char *arg_t = (pos_argc >= 2) ? pos_args[1] : "all";
        if (select_slots(arg_t, &slots, &scnt) != 0) { rc = 2; goto out; }

        int changed = 0;
        for (size_t i = 0; i < scnt; i++) {
            int ini_res = patch_one_ini_target(&ctx, &slots[i], backup_dir,
                                               entries, nentries, &changed);
            if (ini_res < 0) {
                rc = 1;
                if (g_cmd_timed_out) break;
                continue;
            }
            if (ini_res == 1) {
                continue;
            }
            if (patch_one_se_target(&ctx, &slots[i], backup_dir, &changed) != 0) {
                rc = 1;
                if (g_cmd_timed_out) break;
            }
            if (patch_one_s2b_target(&ctx, &slots[i], backup_dir, &changed) != 0) {
                rc = 1;
                if (g_cmd_timed_out) break;
            }
        }

        if (changed > 0) {
            if (efs_sync(&ctx) != 0) {
                rc = 1;
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
                if (g_cmd_timed_out) break;
            }
            if (restore_one_target(&ctx, backup_dir, slots[i].se_backup_prefix, slots[i].se_path, &changed) != 0) {
                rc = 1;
                if (g_cmd_timed_out) break;
            }
            if (restore_one_target(&ctx, backup_dir, slots[i].s2b_backup_prefix, slots[i].s2b_path, &changed) != 0) {
                rc = 1;
                if (g_cmd_timed_out) break;
            }
        }
        if (changed > 0) {
            if (efs_sync(&ctx) != 0) {
                rc = 1;
            }
        }
        printf("CHANGED=%d REBOOT_REQUIRED=%d\n", changed, changed > 0 ? 1 : 0);
    } else {
        rc = 2;
    }

out:
    diag_close(&ctx);
    if (lock_fd >= 0) close(lock_fd);
    if (g_cmd_timed_out || (rc != 0 && g_saw_diag_timeout)) {
        fprintf(stderr, "DIAG_READ_TIMEOUT: operation exceeded timeout limit (%ds)\n", eff_timeout_sec);
        rc = EXIT_CODE_TIMEOUT;
    }
    return rc;
}
